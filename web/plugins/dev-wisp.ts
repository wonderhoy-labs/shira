import type { IncomingMessage } from 'node:http';
import { Socket } from 'node:net';
import type { Duplex } from 'node:stream';
import { extensions, logging, packet, server as wisp } from '@mercuryworkshop/wisp-js/server';
import { WebSocketServer, type WebSocket } from 'ws';
import { definePasswordAuth, PASSWORD_AUTH_ID } from '../src/wisp/password-auth.ts';

const CLOSE_BAD_PASSWORD = 0xc0;
const CLOSE_NO_CREDENTIALS = 0xc2;

const READ_QUEUE_LIMIT = 64;

/**
 * Takes the place of wisp-js's own TCP socket, which has two problems when the destination is faster than
 * the WebSocket. It keeps reading into memory however much is waiting, and when the destination closes, it
 * throws away whatever hasn't been sent on yet, which cuts downloads short without any error.
 */
class LosslessTcpSocket {
  private readonly queue: Buffer[] = [];
  private readonly socket = new Socket();
  private waiting: (() => void) | null = null;
  private ended = false;
  private connected = false;

  constructor(
    readonly hostname: string,
    private readonly port: number,
  ) {
    this.socket.setNoDelay(true);
    this.socket.on('data', (data) => {
      this.queue.push(data as Buffer);
      if (this.queue.length >= READ_QUEUE_LIMIT) this.socket.pause();
      this.wake();
    });
    this.socket.on('close', () => {
      this.ended = true;
      this.wake();
    });
    this.socket.on('error', () => undefined);
  }

  private wake() {
    this.waiting?.();
    this.waiting = null;
  }

  async connect(address: string) {
    await new Promise<void>((resolve, reject) => {
      this.socket.once('connect', () => {
        this.connected = true;
        resolve();
      });
      this.socket.once('close', () => {
        if (!this.connected) reject(new Error('connection failed'));
      });
      this.socket.connect({ host: address, port: this.port });
    });
  }

  /** The next bytes, then undefined once everything's been read. */
  async recv(): Promise<Buffer | undefined> {
    while (this.queue.length === 0) {
      if (this.ended) return undefined;
      await new Promise<void>((resolve) => {
        this.waiting = resolve;
      });
    }
    const data = this.queue.shift();
    if (this.queue.length < READ_QUEUE_LIMIT / 2) this.socket.resume();
    return data;
  }

  async send(data: Uint8Array) {
    await new Promise<void>((resolve) => {
      this.socket.write(data, () => resolve());
    });
  }

  async close() {
    this.socket.end();
  }

  // wisp-js calls these around each send. The queue above does the job instead.
  pause() {}
  resume() {}
}

export interface DevWispServer {
  handleUpgrade(request: IncomingMessage, socket: Duplex, head: Buffer): void;
}

/**
 * The Wisp server for local development, on the same port as the page.
 *
 * Set SHIRA_DEV_WISP_AUTH=user:password to make it require a login. wisp-js has the extension's message
 * but doesn't check anything itself, so that happens here.
 */
export function createWispServer(): DevWispServer {
  logging.set_level(logging.WARN);
  // It only takes connections from this computer's own page, so it may as well reach what's on it.
  wisp.options.allow_loopback_ips = true;
  wisp.options.allow_private_ips = true;

  const [username, password] = splitCredentials(process.env.SHIRA_DEV_WISP_AUTH);
  const PasswordAuth = definePasswordAuth(extensions.PasswordAuthExtension, packet.WispBuffer);

  class CheckedConnection extends wisp.ServerConnection {
    override async setup_wisp_v2() {
      await super.setup_wisp_v2();
      const given = this.client_exts[PASSWORD_AUTH_ID]?.payload as { username: string; password: string } | undefined;
      if (!given) {
        await this.send_close_packet(0, CLOSE_NO_CREDENTIALS);
        throw new Error('no credentials');
      }
      if (given.username !== username || given.password !== password) {
        await this.send_close_packet(0, CLOSE_BAD_PASSWORD);
        throw new Error('wrong credentials');
      }
    }
  }

  const sockets = new WebSocketServer({ noServer: true, handleProtocols: (protocols) => [...protocols][0] ?? false });

  async function serve(socket: WebSocket, request: IncomingMessage) {
    socket.binaryType = 'arraybuffer';
    const version = request.headers['sec-websocket-protocol'] ? 2 : 1;
    const connection = username
      ? new CheckedConnection(socket, request.url ?? '/', {
          TCPSocket: LosslessTcpSocket,
          wisp_version: version,
          wisp_extensions: [
            new extensions.UDPExtension({ server_config: {} }),
            new PasswordAuth({ server_config: { required: 1 } }),
          ],
        })
      : new wisp.ServerConnection(socket, request.url ?? '/', { TCPSocket: LosslessTcpSocket, wisp_version: version });
    try {
      await connection.setup();
      await connection.run();
    } catch {
      socket.close();
    }
  }

  return {
    handleUpgrade(request, socket, head) {
      sockets.handleUpgrade(request, socket, head, (upgraded) => void serve(upgraded, request));
    },
  };
}

function splitCredentials(value: string | undefined): [string, string] | [null, null] {
  if (!value) return [null, null];
  const colon = value.indexOf(':');
  return colon < 0 ? [value, ''] : [value.slice(0, colon), value.slice(colon + 1)];
}
