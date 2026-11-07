import type { client } from '@mercuryworkshop/wisp-js/client';
import type { Adb } from '@yume-chan/adb';
import type { WispConnection } from '../wisp/connection';
import {
  CLOSE_NETWORK_ERROR,
  CLOSE_VOLUNTARY,
  encodeClose,
  encodeContinue,
  encodeData,
  encodePing,
  encodeStart,
  encodeStop,
  FrameDecoder,
  MAX_DATA_BYTES,
  PacketType,
  parseConnect,
  parseHello,
  parseMeta,
  parseStatus,
  PROTOCOL_VERSION,
  type ConnectRequest,
  type Hello,
  type Packet,
  type StreamMeta,
} from './frames';

type AdbSocket = Awaited<ReturnType<Adb['createSocket']>>;

/** Above this much on its way to the phone, the page stops reading from the Wisp server. */
const PHONE_QUEUE_HIGH_BYTES = 2 * 1024 * 1024;
const PHONE_QUEUE_LOW_BYTES = 512 * 1024;
/** Above this much on its way to the server, the page stops reading from the phone. */
const SERVER_QUEUE_HIGH_BYTES = 8 * 1024 * 1024;
const SERVER_POLL_MILLIS = 5;
const HELLO_TIMEOUT_MILLIS = 5_000;
const PING_MILLIS = 2_000;

export interface RelayEvents {
  onStreamOpen(stream: number, request: ConnectRequest): void;
  onStreamMeta(stream: number, meta: StreamMeta): void;
  onStreamData(stream: number, upBytes: number, downBytes: number): void;
  onStreamClose(stream: number, reason: number | null): void;
  /** The VPN came up, went down, or Android refused it. */
  onStatus(code: number, message: string): void;
  /** The socket closed, because the phone was unplugged or Shira was turned off there. */
  onEnded(): void;
}

/**
 * Carries Wisp between the app and the server: the app's CONNECT, DATA and CLOSE go out through wisp-js, and
 * what the server sends comes back. It keeps a count of everything for the log along the way.
 */
export class Relay {
  private readonly writer: WritableStreamDefaultWriter<Uint8Array>;
  private readonly decoder = new FrameDecoder();
  private readonly streams = new Map<number, client.ClientStream>();
  private wisp: WispConnection | null = null;
  private phoneQueuedBytes = 0;
  private drained: (() => void) | null = null;
  private ended = false;
  private ticker: Worker | null = null;
  private helloWaiter: ((hello: Hello) => void) | null = null;
  private hello: Hello | null = null;

  constructor(
    private readonly socket: AdbSocket,
    private readonly events: RelayEvents,
  ) {
    this.writer = socket.writable.getWriter() as unknown as WritableStreamDefaultWriter<Uint8Array>;
    void this.read();
    this.startPings();
  }

  /** What the app said when the socket opened. Throws if it isn't something this page can talk to. */
  async greet(): Promise<Hello> {
    const hello = await new Promise<Hello | null>((resolve) => {
      if (this.hello) {
        resolve(this.hello);
        return;
      }
      this.helloWaiter = resolve;
      setTimeout(() => resolve(null), HELLO_TIMEOUT_MILLIS);
    });
    if (!hello) throw new Error("The app on the phone didn't answer.");
    if (hello.protocol !== PROTOCOL_VERSION) {
      throw new ProtocolMismatchError(hello.appVersion);
    }
    return hello;
  }

  /** Starts using a Wisp connection, and asks the app to bring the VPN up. */
  attach(wisp: WispConnection, serverLabel: string) {
    this.wisp = wisp;
    this.send(encodeStart(wisp.max_buffer_size ?? 0, wisp.udp_enabled, serverLabel));
  }

  /** The Wisp connection is gone, so the VPN goes down until there's another. */
  detach() {
    this.closeAllStreams(CLOSE_NETWORK_ERROR);
    this.wisp = null;
    this.send(encodeStop(false));
  }

  /** Turns Shira off on the phone altogether. */
  deactivate() {
    this.closeAllStreams(CLOSE_VOLUNTARY);
    this.wisp = null;
    this.send(encodeStop(true));
  }

  /** Waits until it's fine to read more from the Wisp server. */
  readonly downloadGate = (): Promise<void> | undefined => {
    if (this.phoneQueuedBytes < PHONE_QUEUE_HIGH_BYTES) return undefined;
    return new Promise((resolve) => {
      this.drained = resolve;
    });
  };

  close() {
    this.finish();
    void Promise.resolve(this.socket.close()).catch(() => undefined);
  }

  private async read() {
    const reader = this.socket.readable.getReader();
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        for (const packet of this.decoder.push(value)) {
          this.onPacket(packet);
        }
        // The server's connection is slower than the phone. Stop here, and the phone holds back too.
        while ((this.wisp?.unsentBytes ?? 0) > SERVER_QUEUE_HIGH_BYTES) {
          await new Promise((resolve) => setTimeout(resolve, SERVER_POLL_MILLIS));
        }
      }
    } catch (error) {
      console.warn('lost the socket to the phone', error);
    } finally {
      reader.releaseLock();
      this.finish();
    }
  }

  private onPacket(packet: Packet) {
    const { type, stream, payload } = packet;
    switch (type) {
      case PacketType.HELLO:
        this.hello = parseHello(payload);
        this.helloWaiter?.(this.hello);
        this.helloWaiter = null;
        break;
      case PacketType.STATUS: {
        const status = parseStatus(payload);
        this.events.onStatus(status.code, status.message);
        break;
      }
      case PacketType.META:
        this.events.onStreamMeta(stream, parseMeta(payload));
        break;
      case PacketType.CONNECT:
        this.openStream(stream, parseConnect(payload));
        break;
      case PacketType.DATA:
        this.streams.get(stream)?.send(payload);
        this.events.onStreamData(stream, payload.length, 0);
        break;
      case PacketType.CLOSE:
        this.closeFromPhone(stream, payload[0] ?? CLOSE_VOLUNTARY);
        break;
    }
  }

  private openStream(id: number, request: ConnectRequest) {
    this.events.onStreamOpen(id, request);
    let stream: client.ClientStream;
    try {
      if (!this.wisp) throw new Error('not connected');
      stream = this.wisp.create_stream(request.host, request.port, request.streamType);
    } catch (error) {
      console.warn('couldn\'t open a stream', error);
      this.send(encodeClose(id, CLOSE_NETWORK_ERROR));
      this.events.onStreamClose(id, CLOSE_NETWORK_ERROR);
      return;
    }
    stream.onmessage = (data) => {
      this.events.onStreamData(id, 0, data.length);
      for (let at = 0; at < data.length; at += MAX_DATA_BYTES) {
        this.send(encodeData(id, data.subarray(at, at + MAX_DATA_BYTES)));
      }
    };
    stream.onclose = (reason) => {
      // Only if it wasn't the phone that closed it.
      if (this.streams.delete(id)) {
        this.send(encodeClose(id, reason ?? CLOSE_NETWORK_ERROR));
        this.events.onStreamClose(id, reason);
      }
    };
    // wisp-js has no event for this, and the phone needs to know how much it can send. The wisp-js stream
    // keeps its own count too, so if the two drift apart it holds packets back rather than overrunning.
    const received = stream.continue_received.bind(stream);
    stream.continue_received = (credits) => {
      received(credits);
      this.send(encodeContinue(id, credits));
    };
    this.streams.set(id, stream);
  }

  private closeFromPhone(id: number, reason: number) {
    const stream = this.streams.get(id);
    this.streams.delete(id);
    stream?.close(reason);
    this.events.onStreamClose(id, reason);
  }

  /** [logged] is what goes in the log. The server always hears that it's a voluntary close. */
  private closeAllStreams(logged: number) {
    const streams = [...this.streams.entries()];
    this.streams.clear();
    for (const [id, stream] of streams) {
      stream.close(CLOSE_VOLUNTARY);
      this.events.onStreamClose(id, logged);
    }
  }

  private send(frame: Uint8Array) {
    if (this.ended) return;
    this.phoneQueuedBytes += frame.length;
    const done = () => {
      this.phoneQueuedBytes -= frame.length;
      if (this.drained && this.phoneQueuedBytes <= PHONE_QUEUE_LOW_BYTES) {
        this.drained();
        this.drained = null;
      }
    };
    this.writer.write(frame).then(done, done);
  }

  /**
   * A closed tab doesn't always close the socket, so the app stops the VPN if these stop coming. They're
   * sent from a worker because browsers slow timers in a hidden tab down to once a minute, and this tab
   * spends most of its time hidden.
   */
  private startPings() {
    this.ticker = new Worker(new URL('./ticker.ts', import.meta.url), { type: 'module' });
    this.ticker.onmessage = () => this.send(encodePing());
    this.ticker.postMessage(PING_MILLIS);
  }

  private finish() {
    if (this.ended) return;
    this.ended = true;
    this.ticker?.terminate();
    this.ticker = null;
    this.drained?.();
    this.drained = null;
    this.closeAllStreams(CLOSE_NETWORK_ERROR);
    this.wisp = null;
    this.events.onEnded();
  }
}

/** The app on the phone is too old or too new for this page. */
export class ProtocolMismatchError extends Error {
  constructor(readonly appVersion: string) {
    super(`Shira ${appVersion} on the phone doesn't match this page.`);
    this.name = 'ProtocolMismatchError';
  }
}
