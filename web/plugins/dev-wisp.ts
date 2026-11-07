import type { IncomingMessage } from 'node:http';
import type { Duplex } from 'node:stream';
import { extensions, logging, server as wisp } from '@mercuryworkshop/wisp-js/server';
import { WebSocketServer, type WebSocket } from 'ws';

const PASSWORD_AUTH_ID = 0x02;
const CLOSE_BAD_PASSWORD = 0xc0;
const CLOSE_NO_CREDENTIALS = 0xc2;

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
          wisp_version: version,
          wisp_extensions: [
            new extensions.UDPExtension({ server_config: {} }),
            new extensions.PasswordAuthExtension({ server_config: { required: 1 } }),
          ],
        })
      : new wisp.ServerConnection(socket, request.url ?? '/', { wisp_version: version });
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
