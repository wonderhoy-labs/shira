import type { client } from '@mercuryworkshop/wisp-js/client';

interface StreamOpened {
  readable: ReadableStream<ArrayBuffer | Uint8Array | string>;
  writable: WritableStream<Uint8Array>;
  protocol: string;
}

interface WebSocketStreamLike {
  opened: Promise<StreamOpened>;
  closed: Promise<unknown>;
  close(): void;
}

type WebSocketStreamConstructor = new (url: string, options?: { protocols?: string[] }) => WebSocketStreamLike;

function getWebSocketStream(): WebSocketStreamConstructor | undefined {
  return (globalThis as { WebSocketStream?: WebSocketStreamConstructor }).WebSocketStream;
}

/** Waits until it's fine to read more, or returns nothing if it already is. */
export type ReadGate = () => Promise<void> | undefined;

/**
 * A WebSocket that stops reading when the phone can't keep up.
 *
 * Wisp has no way to slow down a server's downloads on one stream. The only brake is the connection
 * itself, by not reading from it, and a plain WebSocket can't do that: it hands over everything as fast
 * as it arrives, however much is piling up. WebSocketStream can, and browsers with WebUSB have it. This
 * makes it look like the WebSocket that wisp-js expects.
 */
export class FlowControlledSocket implements client.WispSocket {
  binaryType = 'arraybuffer';
  bufferedAmount = 0;
  onmessage: client.WispSocket['onmessage'] = null;
  onclose: client.WispSocket['onclose'] = null;
  onerror: client.WispSocket['onerror'] = null;

  private readonly stream: WebSocketStreamLike;
  private writer: WritableStreamDefaultWriter<Uint8Array> | null = null;

  constructor(
    url: string,
    protocol: string | undefined,
    private readonly gate: ReadGate,
    Constructor: WebSocketStreamConstructor,
  ) {
    this.stream = new Constructor(url, protocol ? { protocols: [protocol] } : {});
    this.stream.opened.then(
      ({ readable, writable }) => {
        this.writer = writable.getWriter();
        void this.pump(readable.getReader());
      },
      () => this.onerror?.(),
    );
    const closed = () => this.onclose?.();
    this.stream.closed.then(closed, closed);
  }

  private async pump(reader: ReadableStreamDefaultReader<ArrayBuffer | Uint8Array | string>) {
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) return;
        if (typeof value === 'string') continue;
        this.onmessage?.({ data: value });
        const wait = this.gate();
        if (wait) await wait;
      }
    } catch {
      // The connection failed. `closed` says so too, and that's what the rest of the code listens to.
    }
  }

  send(data: Uint8Array) {
    const writer = this.writer;
    if (!writer) return;
    this.bufferedAmount += data.length;
    const done = () => {
      this.bufferedAmount -= data.length;
    };
    writer.write(data).then(done, done);
  }

  close() {
    this.stream.close();
  }
}

export function createSocket(url: string, protocol: string | undefined, gate: ReadGate): client.WispSocket {
  const Constructor = getWebSocketStream();
  if (Constructor) return new FlowControlledSocket(url, protocol, gate, Constructor);
  const socket = new WebSocket(url, protocol);
  socket.binaryType = 'arraybuffer';
  return socket as unknown as client.WispSocket;
}
