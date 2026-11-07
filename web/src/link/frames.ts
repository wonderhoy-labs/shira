/**
 * The messages between this page and the app, over one adb socket.
 *
 * Each message is a Wisp packet with its length in front, because a socket has no message boundaries of its
 * own the way a WebSocket does. See docs/protocol.md, and Link.kt for the other end.
 */

/** Has to match PROTOCOL_VERSION in the app. They refuse each other if it doesn't. */
export const PROTOCOL_VERSION = 1;

export const PacketType = {
  CONNECT: 0x01,
  DATA: 0x02,
  CONTINUE: 0x03,
  CLOSE: 0x04,
  HELLO: 0x80,
  START: 0x81,
  STOP: 0x82,
  STATUS: 0x83,
  META: 0x84,
  PING: 0x85,
} as const;

export const StreamType = { TCP: 0x01, UDP: 0x02 } as const;
export const LinkStatus = { DOWN: 0, UP: 1, FAILED: 2, OFF: 3, REPLACED: 4 } as const;
export const MetaKind = { CONNECTION: 0, DNS: 1 } as const;

export const CLOSE_VOLUNTARY = 0x02;
export const CLOSE_NETWORK_ERROR = 0x03;

const LENGTH_BYTES = 4;
const HEADER_BYTES = 5;
const FRAME_OVERHEAD = LENGTH_BYTES + HEADER_BYTES;
const MAX_PACKET_BYTES = 1 << 20;
/** Has to leave room under the app's limit. A server can send one bigger than this, so it gets split. */
export const MAX_DATA_BYTES = 512 * 1024;

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export interface Packet {
  type: number;
  stream: number;
  payload: Uint8Array;
}

function frame(type: number, stream: number, payloadBytes: number): { bytes: Uint8Array; view: DataView } {
  const bytes = new Uint8Array(FRAME_OVERHEAD + payloadBytes);
  const view = new DataView(bytes.buffer);
  view.setUint32(0, HEADER_BYTES + payloadBytes, true);
  view.setUint8(LENGTH_BYTES, type);
  view.setUint32(LENGTH_BYTES + 1, stream, true);
  return { bytes, view };
}

export function encodeData(stream: number, data: Uint8Array): Uint8Array {
  const { bytes } = frame(PacketType.DATA, stream, data.length);
  bytes.set(data, FRAME_OVERHEAD);
  return bytes;
}

export function encodeContinue(stream: number, credits: number): Uint8Array {
  const { bytes, view } = frame(PacketType.CONTINUE, stream, 4);
  view.setUint32(FRAME_OVERHEAD, credits, true);
  return bytes;
}

export function encodeClose(stream: number, reason: number): Uint8Array {
  const { bytes } = frame(PacketType.CLOSE, stream, 1);
  bytes[FRAME_OVERHEAD] = reason;
  return bytes;
}

export function encodeStart(credits: number, udp: boolean, server: string): Uint8Array {
  const label = encoder.encode(server);
  const { bytes, view } = frame(PacketType.START, 0, 5 + label.length);
  view.setUint32(FRAME_OVERHEAD, credits, true);
  bytes[FRAME_OVERHEAD + 4] = udp ? 1 : 0;
  bytes.set(label, FRAME_OVERHEAD + 5);
  return bytes;
}

export function encodeStop(deactivate: boolean): Uint8Array {
  const { bytes } = frame(PacketType.STOP, 0, 1);
  bytes[FRAME_OVERHEAD] = deactivate ? 1 : 0;
  return bytes;
}

export function encodePing(): Uint8Array {
  return frame(PacketType.PING, 0, 0).bytes;
}

export interface ConnectRequest {
  streamType: number;
  port: number;
  host: string;
}

export function parseConnect(payload: Uint8Array): ConnectRequest {
  return {
    streamType: payload[0] ?? 0,
    port: (payload[1] ?? 0) | ((payload[2] ?? 0) << 8),
    host: decoder.decode(payload.subarray(3)),
  };
}

export interface Hello {
  protocol: number;
  appVersion: string;
}

export function parseHello(payload: Uint8Array): Hello {
  return { protocol: payload[0] ?? 0, appVersion: decoder.decode(payload.subarray(1)) };
}

export interface Status {
  code: number;
  message: string;
}

export function parseStatus(payload: Uint8Array): Status {
  return { code: payload[0] ?? 0, message: decoder.decode(payload.subarray(1)) };
}

export interface StreamMeta {
  /** -1 if Android couldn't say. */
  uid: number;
  kind: number;
  queryType: number;
  name: string;
}

export function parseMeta(payload: Uint8Array): StreamMeta {
  const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
  return {
    uid: view.getInt32(0, true),
    kind: view.getUint8(4),
    queryType: view.getUint16(5, true),
    name: decoder.decode(payload.subarray(7)),
  };
}

/** Finds whole packets in what comes off the socket, which can be split up in any way. */
export class FrameDecoder {
  private buffer = new Uint8Array(256 * 1024);
  private filled = 0;

  /** The payloads don't share memory with anything, so they're safe to hold on to. */
  push(chunk: Uint8Array): Packet[] {
    this.ensureRoom(chunk.length);
    this.buffer.set(chunk, this.filled);
    this.filled += chunk.length;

    const packets: Packet[] = [];
    const view = new DataView(this.buffer.buffer);
    let offset = 0;
    while (this.filled - offset >= LENGTH_BYTES) {
      const length = view.getUint32(offset, true);
      if (length < HEADER_BYTES || length > MAX_PACKET_BYTES) {
        throw new Error(`The phone sent a packet of ${length} bytes.`);
      }
      if (this.filled - offset - LENGTH_BYTES < length) break;
      const start = offset + LENGTH_BYTES;
      packets.push({
        type: view.getUint8(start),
        stream: view.getUint32(start + 1, true),
        payload: this.buffer.slice(start + HEADER_BYTES, start + length),
      });
      offset = start + length;
    }
    if (offset > 0) {
      this.buffer.copyWithin(0, offset, this.filled);
      this.filled -= offset;
    }
    return packets;
  }

  private ensureRoom(bytes: number) {
    if (this.filled + bytes <= this.buffer.length) return;
    const grown = new Uint8Array((this.filled + bytes) * 2);
    grown.set(this.buffer.subarray(0, this.filled));
    this.buffer = grown;
  }
}
