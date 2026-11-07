import { client, extensions } from '@mercuryworkshop/wisp-js/client';
import { createSocket, type ReadGate } from './socket';

const TYPE_CLOSE = 0x04;
const TYPE_INFO = 0x05;
const HEADER_BYTES = 5;
const VERSION_BYTES = 2;
const EXTENSION_HEADER_BYTES = 5;
const PASSWORD_AUTH_ID = 0x02;
const KEY_AUTH_ID = 0x03;
const HANDSHAKE_TIMEOUT_MILLIS = 15_000;

const CLOSE_INCOMPATIBLE = 0x04;
const CLOSE_BAD_PASSWORD = 0xc0;
const CLOSE_BAD_SIGNATURE = 0xc1;
const CLOSE_NEEDS_CREDENTIALS = 0xc2;

export interface WispCredentials {
  username: string;
  password: string;
}

export type WispFailure =
  /** The server wants a username and password, and none were given. */
  | 'credentials-needed'
  /** The ones that were given are wrong. */
  | 'credentials-wrong'
  /** The server only takes key-based logins, which wisp-js can't do. */
  | 'key-needed'
  | 'incompatible'
  | 'unreachable';

export class WispError extends Error {
  constructor(
    readonly failure: WispFailure,
    message: string,
  ) {
    super(message);
    this.name = 'WispError';
  }
}

interface ConnectionOptions {
  credentials: WispCredentials | null;
  gate: ReadGate;
}

const connectionOptions = new WeakMap<object, ConnectionOptions>();
let nextOptions: ConnectionOptions | null = null;

/** What a server said it supports in its INFO packet. */
export interface ServerInfo {
  passwordOffered: boolean;
  passwordRequired: boolean;
  keyRequired: boolean;
}

export function parseServerInfo(info: Uint8Array): ServerInfo {
  const result: ServerInfo = { passwordOffered: false, passwordRequired: false, keyRequired: false };
  const view = new DataView(info.buffer, info.byteOffset, info.byteLength);
  let at = HEADER_BYTES + VERSION_BYTES;
  while (at + EXTENSION_HEADER_BYTES <= info.length) {
    const id = view.getUint8(at);
    const length = view.getUint32(at + 1, true);
    const payload = at + EXTENSION_HEADER_BYTES;
    const required = length > 0 && payload < info.length && view.getUint8(payload) !== 0;
    if (id === PASSWORD_AUTH_ID) {
      result.passwordOffered = true;
      result.passwordRequired = required;
    }
    if (id === KEY_AUTH_ID) result.keyRequired = required;
    at = payload + length;
  }
  return result;
}

/**
 * A connection to a Wisp server, using wisp-js for the protocol.
 *
 * wisp-js handles version 2, falling back to version 1, and the UDP and MOTD extensions. This adds two
 * things to its handshake: logging in, because a server's INFO packet decides whether that's needed, and
 * reasons for failing, because wisp-js ignores a CLOSE that arrives during the handshake. It also swaps
 * in a socket that can apply backpressure.
 */
export class WispConnection extends client.ClientConnection {
  failure: WispError | null = null;

  override connect_ws() {
    // The base constructor calls this before this class's fields exist, so these can't be fields. The
    // version 1 fallback calls it again later, when they have to be remembered.
    if (nextOptions) connectionOptions.set(this, nextOptions);
    const gate = connectionOptions.get(this)?.gate ?? (() => undefined);
    const protocol = this.wisp_version === 2 ? 'wisp-v2' : undefined;
    this.ws = createSocket(this.wisp_url, protocol, gate);
    this.connecting = true;
    this.ws.onmessage = (event) => {
      this.on_ws_msg(event);
      if (this.connected && this.connecting) {
        this.connecting = false;
        this.onopen();
      }
    };
    this.ws.onclose = () => {
      this.cleanup();
      this.onclose();
    };
    this.ws.onerror = () => {
      if (this.wisp_version === 2 && !this.connected) {
        // Try falling back to version 1, for a server that doesn't take the subprotocol.
        this.ws.onclose = null;
        this.cleanup();
        this.wisp_version = 1;
        this.connect_ws();
        return;
      }
      this.cleanup();
      this.onerror();
    };
  }

  /** How many bytes are waiting to go out to the server. */
  get unsentBytes(): number {
    return this.ws.bufferedAmount;
  }

  override on_ws_msg(event: { data: ArrayBufferLike | Uint8Array }) {
    if (this.connecting) {
      const bytes = new Uint8Array(event.data as ArrayBuffer);
      const isSession = bytes.length >= HEADER_BYTES && bytes[1] === 0 && bytes[2] === 0 && bytes[3] === 0 && bytes[4] === 0;
      if (isSession && bytes[0] === TYPE_INFO && !this.onServerInfo(bytes)) return;
      if (isSession && bytes[0] === TYPE_CLOSE) this.onHandshakeClosed(bytes[HEADER_BYTES] ?? 0);
    }
    super.on_ws_msg(event);
  }

  /** Decides what to tell the server about this client. False if it can't go on. */
  private onServerInfo(bytes: Uint8Array): boolean {
    const info = parseServerInfo(bytes);
    const credentials = connectionOptions.get(this)?.credentials ?? null;
    if (info.keyRequired && !info.passwordOffered) {
      this.failure = new WispError('key-needed', "This server only takes key-based logins, which Shira doesn't support yet.");
      this.ws.close();
      return false;
    }
    if (info.passwordRequired && !credentials) {
      this.failure = new WispError('credentials-needed', 'This server needs a username and password.');
      this.ws.close();
      return false;
    }
    this.wisp_extensions = [
      new extensions.UDPExtension({ client_config: {} }),
      new extensions.MOTDExtension({ client_config: {} }),
    ];
    // Only to a server that asked for them, so a password can't leak to one that doesn't care.
    if (credentials && info.passwordOffered) {
      this.wisp_extensions.push(new extensions.PasswordAuthExtension({ client_config: credentials }));
    }
    return true;
  }

  private onHandshakeClosed(reason: number) {
    switch (reason) {
      case CLOSE_BAD_PASSWORD:
        this.failure = new WispError('credentials-wrong', 'The server says that username and password are wrong.');
        break;
      case CLOSE_NEEDS_CREDENTIALS:
        this.failure = new WispError('credentials-needed', 'This server needs a username and password.');
        break;
      case CLOSE_BAD_SIGNATURE:
        this.failure = new WispError('key-needed', "This server only takes key-based logins, which Shira doesn't support yet.");
        break;
      case CLOSE_INCOMPATIBLE:
        this.failure = new WispError('incompatible', "This server and Shira don't share the extensions it requires.");
        break;
      default:
        this.failure = new WispError('unreachable', `The server closed the connection (reason 0x${reason.toString(16)}).`);
    }
  }
}

/** Connects and finishes the handshake, or says why it couldn't. */
export function connectWisp(
  url: string,
  credentials: WispCredentials | null,
  gate: ReadGate = () => undefined,
): Promise<WispConnection> {
  return new Promise((resolve, reject) => {
    nextOptions = { credentials, gate };
    let connection: WispConnection;
    try {
      connection = new WispConnection(url);
    } catch (error) {
      reject(error instanceof Error ? new WispError('unreachable', error.message) : error);
      return;
    } finally {
      nextOptions = null;
    }

    const timer = setTimeout(() => {
      fail(new WispError('unreachable', "The server didn't answer."));
      connection.close();
    }, HANDSHAKE_TIMEOUT_MILLIS);

    function fail(error: WispError) {
      clearTimeout(timer);
      reject(error);
    }

    const unreachable = () =>
      fail(connection.failure ?? new WispError('unreachable', "Couldn't connect to that server. Check the address, and that it's a Wisp server."));
    connection.onclose = unreachable;
    connection.onerror = unreachable;
    connection.onopen = () => {
      clearTimeout(timer);
      resolve(connection);
    };
  });
}

/** Tidies what someone typed into a Wisp address, or says what's wrong with it. */
export function normalizeServerUrl(input: string): { url: string } | { problem: string } {
  const text = input.trim();
  if (!text) return { problem: 'Enter the address of a Wisp server.' };
  // A server on this computer almost never has a certificate, and anywhere else almost always should.
  const bareScheme = /^(localhost|127\.0\.0\.1|\[::1\])([:/]|$)/i.test(text) ? 'ws' : 'wss';
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(text) ? text : `${bareScheme}://${text}`;
  let url: URL;
  try {
    url = new URL(withScheme);
  } catch {
    return { problem: "That doesn't look like an address." };
  }
  if (url.protocol === 'https:') url.protocol = 'wss:';
  if (url.protocol === 'http:') url.protocol = 'ws:';
  if (url.protocol !== 'wss:' && url.protocol !== 'ws:') {
    return { problem: 'A Wisp address starts with wss:// or ws://.' };
  }
  const isLocal = url.hostname === 'localhost' || url.hostname === '127.0.0.1' || url.hostname === '[::1]';
  if (url.protocol === 'ws:' && !isLocal && location.protocol === 'https:') {
    return { problem: 'A secure page can only use wss:// addresses, apart from localhost.' };
  }
  // Wisp's own rule: a trailing slash is what tells it apart from wsproxy.
  if (!url.pathname.endsWith('/')) url.pathname += '/';
  return { url: url.toString() };
}
