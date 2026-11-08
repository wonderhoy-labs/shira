import { describeApp } from "./format";

export type RowKind = "tcp" | "udp" | "dns";
export type RowState = "open" | "done" | "failed";

export interface LogRow {
	id: number;
	stream: number;
	kind: RowKind;
	/** The address it went to. */
	host: string;
	port: number;
	/** The name the phone's resolver saw for that address, or the name being looked up. */
	name: string;
	queryType: number;
	uid: number;
	openedAt: number;
	closedAt: number | null;
	up: number;
	down: number;
	state: RowState;
	closeReason: number | null;
}

/** The most rows to keep. The oldest finished ones go first. */
const MAX_ROWS = 20_000;
const CLOSE_NORMAL = new Set([0x01, 0x02]);
const RATE_WINDOW_MILLIS = 1_000;

export interface LogTotals {
	active: number;
	total: number;
	failed: number;
	up: number;
	down: number;
	upRate: number;
	downRate: number;
}

/**
 * The log of everything that's gone through. Packets arrive far faster than anyone can read, so rows change
 * in place, and whoever's displaying them checks [version] a few times a second.
 */
export class LogStore {
	rows: LogRow[] = [];
	version = 0;
	packages = new Map<number, string>();

	private readonly byStream = new Map<number, LogRow>();
	private nextId = 1;
	private totalUp = 0;
	private totalDown = 0;
	private failed = 0;
	private dropped = 0;
	private sampleAt = 0;
	private sampleUp = 0;
	private sampleDown = 0;
	private upRate = 0;
	private downRate = 0;

	open(stream: number, kind: RowKind, host: string, port: number, now: number) {
		const row: LogRow = {
			id: this.nextId++,
			stream,
			kind,
			host,
			port,
			name: "",
			queryType: 0,
			uid: -1,
			openedAt: now,
			closedAt: null,
			up: 0,
			down: 0,
			state: "open",
			closeReason: null,
		};
		this.rows.push(row);
		this.byStream.set(stream, row);
		if (this.rows.length > MAX_ROWS) this.trim();
		this.version++;
	}

	describe(stream: number, kind: RowKind | null, uid: number, name: string, queryType: number) {
		const row = this.byStream.get(stream);
		if (!row) return;
		if (kind) row.kind = kind;
		row.uid = uid;
		row.name = name;
		row.queryType = queryType;
		this.version++;
	}

	addBytes(stream: number, up: number, down: number) {
		this.totalUp += up;
		this.totalDown += down;
		const row = this.byStream.get(stream);
		if (!row) return;
		row.up += up;
		row.down += down;
		this.version++;
	}

	close(stream: number, reason: number | null, now: number) {
		const row = this.byStream.get(stream);
		if (!row) return;
		this.byStream.delete(stream);
		row.closedAt = now;
		row.closeReason = reason;
		row.state = reason === null || CLOSE_NORMAL.has(reason) ? "done" : "failed";
		if (row.state === "failed") this.failed++;
		this.version++;
	}

	/** Marks whatever's still open as cut off, for when the connection under it goes. */
	closeAll(reason: number, now: number) {
		for (const stream of [...this.byStream.keys()]) this.close(stream, reason, now);
	}

	/** Forgets the rows, and leaves anything still open to carry on. */
	clear() {
		this.rows = [...this.byStream.values()];
		this.failed = 0;
		this.dropped = 0;
		this.version++;
	}

	totals(now: number): LogTotals {
		if (now - this.sampleAt >= RATE_WINDOW_MILLIS) {
			const seconds = (now - this.sampleAt) / RATE_WINDOW_MILLIS;
			this.upRate = (this.totalUp - this.sampleUp) / seconds;
			this.downRate = (this.totalDown - this.sampleDown) / seconds;
			this.sampleAt = now;
			this.sampleUp = this.totalUp;
			this.sampleDown = this.totalDown;
		}
		return {
			active: this.byStream.size,
			total: this.rows.length + this.dropped,
			failed: this.failed,
			up: this.totalUp,
			down: this.totalDown,
			upRate: this.upRate,
			downRate: this.downRate,
		};
	}

	appName(row: LogRow): string {
		return describeApp(row.uid, this.packages.get(row.uid));
	}

	toJson(): string {
		return JSON.stringify(
			this.rows.map((row) => ({ ...row, app: this.appName(row) })),
			null,
			2,
		);
	}

	private trim() {
		const excess = this.rows.length - MAX_ROWS;
		let removed = 0;
		this.rows = this.rows.filter((row) => {
			if (removed < excess && row.state !== "open") {
				removed++;
				return false;
			}
			return true;
		});
		this.dropped += removed;
	}
}
