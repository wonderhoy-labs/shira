const UNITS = ["B", "KB", "MB", "GB", "TB"];
const STEP = 1024;

export function formatBytes(bytes: number): string {
	let value = bytes;
	let unit = 0;
	while (value >= STEP && unit < UNITS.length - 1) {
		value /= STEP;
		unit++;
	}
	return unit === 0 ? `${Math.round(bytes)} B` : `${value.toFixed(value < 10 ? 1 : 0)} ${UNITS[unit]}`;
}

export function formatRate(bytesPerSecond: number): string {
	return `${formatBytes(bytesPerSecond)}/s`;
}

export function formatDuration(millis: number): string {
	if (millis < 1000) return `${Math.round(millis)} ms`;
	const seconds = millis / 1000;
	if (seconds < 60) return `${seconds.toFixed(1)} s`;
	const minutes = Math.floor(seconds / 60);
	if (minutes < 60) return `${minutes}m ${Math.floor(seconds % 60)}s`;
	return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}

export function formatTime(timestamp: number): string {
	const date = new Date(timestamp);
	const pad = (value: number, width = 2) => String(value).padStart(width, "0");
	return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`;
}

/** Wisp's reasons for closing a stream, in words. */
const CLOSE_REASONS: Record<number, string> = {
	1: "Closed for an unknown reason",
	2: "Closed normally",
	3: "Network error",
	65: "Invalid destination",
	66: "Host unreachable",
	67: "Connection timed out",
	68: "Connection refused",
	71: "Transfer timed out",
	72: "Blocked by the server",
	73: "Throttled by the server",
	129: "Dropped by the phone",
};

export function describeClose(reason: number | null): string {
	if (reason === null) return "Closed";
	return CLOSE_REASONS[reason] ?? `Closed (reason 0x${reason.toString(16)})`;
}

/** The DNS record types people are likely to see. */
const QUERY_TYPES: Record<number, string> = {
	1: "A",
	2: "NS",
	5: "CNAME",
	6: "SOA",
	12: "PTR",
	15: "MX",
	16: "TXT",
	28: "AAAA",
	33: "SRV",
	64: "SVCB",
	65: "HTTPS",
};

export function describeQueryType(type: number): string {
	return QUERY_TYPES[type] ?? `TYPE${type}`;
}

/** Android's own users, which have no package to name them by. */
const SYSTEM_UIDS: Record<number, string> = {
	0: "root",
	1000: "Android system",
	1051: "Android DNS",
	1073: "Android network stack",
	2000: "adb shell",
};

export function describeApp(uid: number, packageName: string | undefined): string {
	if (packageName) return packageName;
	if (uid < 0) return "Unknown";
	return SYSTEM_UIDS[uid] ?? `App ${uid}`;
}
