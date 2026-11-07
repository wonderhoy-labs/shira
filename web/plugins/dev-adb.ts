import type { IncomingMessage } from 'node:http';
import { connect } from 'node:net';
import type { Duplex } from 'node:stream';
import { WebSocketServer } from 'ws';

const ADB_SERVER_PORT = 5037;
const ADB_SERVER_HOST = '127.0.0.1';

/**
 * Lets the page talk to the adb server on this computer, one WebSocket per connection.
 *
 * Emulators don't appear over USB, so this is how the real page reaches one. It's only in the development
 * server, and only ever talks to localhost.
 */
export function createAdbBridge() {
  const bridge = new WebSocketServer({ noServer: true });
  bridge.on('connection', (socket) => {
    const tcp = connect(ADB_SERVER_PORT, ADB_SERVER_HOST);
    tcp.setNoDelay(true);
    tcp.on('data', (data) => socket.send(data));
    tcp.on('close', () => socket.close());
    tcp.on('error', () => socket.close(1011, 'adb server is not running'));
    // adb needs to see its input end while its output is still coming, which a WebSocket can't express
    // by closing. An empty message means that here.
    socket.on('message', (data: Buffer) => {
      if (data.length === 0) tcp.end();
      else tcp.write(data);
    });
    socket.on('close', () => tcp.destroy());
    socket.on('error', () => tcp.destroy());
  });
  return {
    handleUpgrade(request: IncomingMessage, socket: Duplex, head: Buffer) {
      bridge.handleUpgrade(request, socket, head, (upgraded) => bridge.emit('connection', upgraded, request));
    },
  };
}
