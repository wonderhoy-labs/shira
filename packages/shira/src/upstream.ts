/**
 * Where the phone's connections go once they reach the page. Shira's own is a Wisp server (wispUpstream), and another
 * library can bring its own, such as one that opens the connections itself from the computer.
 */

import type { ReadGate } from "./wisp/socket";

export type { ReadGate } from "./wisp/socket";

/** What a stream hears from the upstream. */
export interface UpstreamStreamEvents {
	/** Bytes that came from the destination, to go to the phone. */
	onData(data: Uint8Array): void;
	/** The upstream can take this many more DATA packets on the stream now, as in Wisp's CONTINUE. */
	onContinue(credits: number): void;
	/** The destination or the upstream closed the stream, with a Wisp close reason if there is one. */
	onClose(reason: number | null): void;
}

/** One of the phone's connections, out in the world. */
export interface UpstreamStream {
	/** Bytes from the phone. A TCP stream sends them on in order, and a UDP stream sends each one as a datagram. */
	send(data: Uint8Array): void;
	/** The phone closed it, with a Wisp close reason. No more events come after this. */
	close(reason: number): void;
}

/** A live upstream, ready for streams. */
export interface Upstream {
	/** How many DATA packets each new stream can take before its first onContinue, as in Wisp's CONTINUE. */
	readonly credits: number;
	/** Whether the phone's UDP can go through. Without it, apps fall back to TCP. */
	readonly udp: boolean;
	/** A short name for where the phone's traffic goes, which the app shows on the phone. */
	readonly label: string;
	/** Bytes the page has accepted from the phone that haven't gone out yet. The page stops reading above a limit. */
	readonly unsentBytes: number;
	/** What to tell the page's owner about it. */
	readonly details: UpstreamDetails;
	/**
	 * Opens a TCP (StreamType.TCP, 1) or UDP (StreamType.UDP, 2) stream to [host] and [port]. It can throw, and then
	 * the phone hears that the stream closed.
	 */
	openStream(host: string, port: number, streamType: number, events: UpstreamStreamEvents): UpstreamStream;
	/** Settles once the upstream has gone away by itself. */
	readonly lost: Promise<void>;
	/** Lets go of it. Its streams are already closed by then. */
	close(): void;
}

/** What an upstream says about itself, for the page. */
export interface UpstreamDetails {
	/** The Wisp protocol version, or 0 if it isn't a Wisp server. */
	wispVersion: number;
	/** Something the upstream wants the person to read, such as a Wisp server's MOTD. */
	message: string | null;
}

/** Makes upstreams. ShiraLink asks for a new one each time the last one is lost. */
export interface UpstreamConnector {
	/**
	 * Connects. [gate] is to be awaited before reading more from the network, so the phone isn't sent more than it can
	 * take. Throw an UpstreamError with `permanent` set for a problem that trying again won't fix.
	 */
	connect(gate: ReadGate): Promise<Upstream>;
}

/** An upstream couldn't connect. */
export class UpstreamError extends Error {
	/**
	 * @param permanent true for a problem that won't fix itself, such as a wrong password. ShiraLink stops then,
	 * rather than trying again.
	 */
	constructor(
		message: string,
		readonly permanent: boolean,
	) {
		super(message);
		this.name = "UpstreamError";
	}
}
