import { describe, expect, it } from 'vitest';
import { describeApp, describeClose, formatBytes, formatDuration } from './format';
import { LogStore } from './store';

describe('LogStore', () => {
  it('follows a stream from start to finish', () => {
    const log = new LogStore();
    log.open(1, 'tcp', '1.2.3.4', 443, 1000);
    log.describe(1, null, 10123, 'example.com', 0);
    log.addBytes(1, 100, 2000);
    log.close(1, 0x02, 1500);

    const [row] = log.rows;
    expect(row).toMatchObject({ host: '1.2.3.4', name: 'example.com', uid: 10123, up: 100, down: 2000, state: 'done', closedAt: 1500 });
  });

  it('marks DNS lookups as what they are', () => {
    const log = new LogStore();
    log.open(1, 'tcp', '1.1.1.1', 53, 1000);
    log.describe(1, 'dns', -1, 'example.com', 28);
    expect(log.rows[0]).toMatchObject({ kind: 'dns', queryType: 28 });
  });

  it('tells failures from normal closes', () => {
    const log = new LogStore();
    log.open(1, 'tcp', 'a', 1, 0);
    log.open(2, 'tcp', 'b', 1, 0);
    log.close(1, 0x42, 1);
    log.close(2, 0x02, 1);
    expect(log.rows.map((row) => row.state)).toEqual(['failed', 'done']);
    expect(log.totals(1).failed).toBe(1);
  });

  it('counts bytes for a stream it has already forgotten', () => {
    const log = new LogStore();
    log.open(1, 'tcp', 'a', 1, 0);
    log.close(1, 0x02, 1);
    log.addBytes(1, 5, 5);
    expect(log.totals(1)).toMatchObject({ up: 5, down: 5 });
    expect(log.rows[0]?.up).toBe(0);
  });

  it('works out rates over time', () => {
    const log = new LogStore();
    log.totals(0);
    log.addBytes(9, 1000, 2000);
    expect(log.totals(1000)).toMatchObject({ upRate: 1000, downRate: 2000 });
  });

  it('keeps open rows when it clears', () => {
    const log = new LogStore();
    log.open(1, 'tcp', 'a', 1, 0);
    log.open(2, 'tcp', 'b', 1, 0);
    log.close(1, 0x02, 1);
    log.clear();
    expect(log.rows.map((row) => row.stream)).toEqual([2]);
    log.addBytes(2, 1, 1);
    expect(log.rows[0]?.up).toBe(1);
  });

  it('drops the oldest finished rows when it gets too long', () => {
    const log = new LogStore();
    log.open(0, 'tcp', 'still open', 1, 0);
    for (let stream = 1; stream <= 20_010; stream++) {
      log.open(stream, 'tcp', 'x', 1, 0);
      log.close(stream, 0x02, 1);
    }
    expect(log.rows.length).toBeLessThanOrEqual(20_000);
    expect(log.rows[0]?.host).toBe('still open');
    expect(log.totals(1).total).toBe(20_011);
  });

  it('closes whatever is open', () => {
    const log = new LogStore();
    log.open(1, 'tcp', 'a', 1, 0);
    log.closeAll(0x03, 5);
    expect(log.rows[0]).toMatchObject({ state: 'failed', closedAt: 5, closeReason: 0x03 });
    expect(log.totals(5).active).toBe(0);
  });

  it('names apps from what the phone said', () => {
    const log = new LogStore();
    log.packages.set(10123, 'com.android.chrome');
    log.open(1, 'tcp', 'a', 1, 0);
    log.describe(1, null, 10123, '', 0);
    expect(log.appName(log.rows[0]!)).toBe('com.android.chrome');
    expect(log.toJson()).toContain('com.android.chrome');
  });
});

describe('formatting', () => {
  it('writes bytes the way people read them', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(50 * 1024 * 1024)).toBe('50 MB');
  });

  it('writes durations the way people read them', () => {
    expect(formatDuration(12)).toBe('12 ms');
    expect(formatDuration(1500)).toBe('1.5 s');
    expect(formatDuration(125_000)).toBe('2m 5s');
    expect(formatDuration(3_700_000)).toBe('1h 1m');
  });

  it('puts close reasons in words', () => {
    expect(describeClose(0x42)).toBe('Host unreachable');
    expect(describeClose(0xfe)).toBe('Closed (reason 0xfe)');
    expect(describeClose(null)).toBe('Closed');
  });

  it('names apps without a package', () => {
    expect(describeApp(2000, undefined)).toBe('adb shell');
    expect(describeApp(-1, undefined)).toBe('Unknown');
    expect(describeApp(10999, undefined)).toBe('App 10999');
    expect(describeApp(10999, 'com.example')).toBe('com.example');
  });
});
