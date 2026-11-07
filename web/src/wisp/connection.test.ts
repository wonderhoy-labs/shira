import { describe, expect, it, vi } from 'vitest';
import { normalizeServerUrl, parseServerInfo } from './connection';

/** An INFO packet: type, stream 0, version 2.1, then the extensions. */
function info(...extensions: [id: number, payload: number[]][]): Uint8Array {
  const bytes = [0x05, 0, 0, 0, 0, 2, 1];
  for (const [id, payload] of extensions) {
    bytes.push(id, payload.length, 0, 0, 0, ...payload);
  }
  return new Uint8Array(bytes);
}

describe('parseServerInfo', () => {
  it('finds nothing in a server with no extensions', () => {
    expect(parseServerInfo(info())).toEqual({ passwordOffered: false, passwordRequired: false, keyRequired: false });
  });

  it('sees when a password is required', () => {
    expect(parseServerInfo(info([0x01, []], [0x02, [1]]))).toEqual({
      passwordOffered: true,
      passwordRequired: true,
      keyRequired: false,
    });
  });

  it('sees when a password is only offered', () => {
    expect(parseServerInfo(info([0x02, [0]]))).toMatchObject({ passwordOffered: true, passwordRequired: false });
  });

  it('sees when a key is required, and skips what it does not know', () => {
    expect(parseServerInfo(info([0x04, [104, 105]], [0x03, [1, 1, 9, 9, 9]], [0x77, [1, 2, 3]]))).toMatchObject({
      passwordOffered: false,
      keyRequired: true,
    });
  });

  it('copes with a packet that is a view into something bigger', () => {
    const whole = info([0x02, [1]]);
    const padded = new Uint8Array(whole.length + 10);
    padded.set(whole, 10);
    expect(parseServerInfo(padded.subarray(10)).passwordRequired).toBe(true);
  });
});

describe('normalizeServerUrl', () => {
  const asPage = (protocol: string) => vi.stubGlobal('location', { protocol });

  it('adds the trailing slash that Wisp requires', () => {
    asPage('https:');
    expect(normalizeServerUrl('wss://example.com/wisp')).toEqual({ url: 'wss://example.com/wisp/' });
  });

  it('assumes wss for a bare host', () => {
    asPage('https:');
    expect(normalizeServerUrl(' example.com ')).toEqual({ url: 'wss://example.com/' });
  });

  it('assumes ws for a bare address on this computer', () => {
    asPage('https:');
    expect(normalizeServerUrl('localhost:4173/wisp/')).toEqual({ url: 'ws://localhost:4173/wisp/' });
    expect(normalizeServerUrl('127.0.0.1:4173')).toEqual({ url: 'ws://127.0.0.1:4173/' });
    expect(normalizeServerUrl('localhost.example.com')).toEqual({ url: 'wss://localhost.example.com/' });
  });

  it('accepts the https address people copy from a browser', () => {
    asPage('https:');
    expect(normalizeServerUrl('https://example.com/wisp/')).toEqual({ url: 'wss://example.com/wisp/' });
  });

  it('refuses ws from a secure page, except to this computer', () => {
    asPage('https:');
    expect(normalizeServerUrl('ws://example.com/')).toHaveProperty('problem');
    expect(normalizeServerUrl('ws://localhost:1234/')).toEqual({ url: 'ws://localhost:1234/' });
  });

  it('allows ws from an insecure page', () => {
    asPage('http:');
    expect(normalizeServerUrl('ws://192.168.0.2:8080/')).toEqual({ url: 'ws://192.168.0.2:8080/' });
  });

  it('refuses what is not an address', () => {
    asPage('https:');
    expect(normalizeServerUrl('')).toHaveProperty('problem');
    expect(normalizeServerUrl('ftp://example.com/')).toHaveProperty('problem');
    expect(normalizeServerUrl('wss://')).toHaveProperty('problem');
  });
});
