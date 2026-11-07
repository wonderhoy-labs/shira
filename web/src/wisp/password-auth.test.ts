import { describe, expect, it } from 'vitest';
import { decodeCredentials, encodeCredentials } from './password-auth';

describe('password messages', () => {
  it('follows the final protocol: username length, username, then the password fills the rest', () => {
    expect([...encodeCredentials('ab', 'xyz')]).toEqual([2, 97, 98, 120, 121, 122]);
  });

  it('reads back what it wrote', () => {
    expect(decodeCredentials(encodeCredentials('hazel', 'pä55:word'))).toEqual({ username: 'hazel', password: 'pä55:word' });
  });

  it('allows an empty password', () => {
    expect(decodeCredentials(encodeCredentials('hazel', ''))).toEqual({ username: 'hazel', password: '' });
  });

  it('refuses a username that does not fit the length field', () => {
    expect(() => encodeCredentials('x'.repeat(256), '')).toThrow(RangeError);
  });
});
