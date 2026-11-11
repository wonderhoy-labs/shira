import type { client } from "@mercuryworkshop/wisp-js/client";
import type { Upstream, UpstreamConnector, UpstreamDetails, UpstreamStream, UpstreamStreamEvents } from "../upstream";
import { connectWisp, type WispConnection, type WispCredentials } from "./connection";
import type { ReadGate } from "./socket";

/** A Wisp connection as an upstream. */
class WispUpstream implements Upstream {
	readonly lost: Promise<void>;
	readonly details: UpstreamDetails;

	constructor(
		private readonly wisp: WispConnection,
		readonly label: string,
	) {
		this.lost = new Promise<void>((resolve) => {
			wisp.onclose = () => resolve();
			wisp.onerror = () => resolve();
		});
		this.details = { wispVersion: wisp.wisp_version, message: wisp.server_motd ?? null };
	}

	get credits(): number {
		return this.wisp.max_buffer_size ?? 0;
	}

	get udp(): boolean {
		return this.wisp.udp_enabled;
	}

	get unsentBytes(): number {
		return this.wisp.unsentBytes;
	}

	openStream(host: string, port: number, streamType: number, events: UpstreamStreamEvents): UpstreamStream {
		const stream: client.ClientStream = this.wisp.create_stream(host, port, streamType);
		let open = true;
		stream.onmessage = (data) => events.onData(data);
		stream.onclose = (reason) => {
			if (!open) return;
			open = false;
			events.onClose(reason ?? null);
		};
		// wisp-js has no event for this, and the phone needs to know how much it can send. The wisp-js stream
		// keeps its own count too, so if the two drift apart it holds packets back rather than overrunning.
		const received = stream.continue_received.bind(stream);
		stream.continue_received = (credits) => {
			received(credits);
			events.onContinue(credits);
		};
		return {
			send: (data) => stream.send(data),
			close: (reason) => {
				open = false;
				stream.close(reason);
			},
		};
	}

	close() {
		this.wisp.onclose = () => undefined;
		this.wisp.onerror = () => undefined;
		this.wisp.close();
	}
}

/**
 * Sends the phone's connections through a Wisp server at [url], which normalizeServerUrl accepted. Login problems
 * fail with a WispError, which ShiraLink treats as permanent apart from "unreachable".
 */
export function wispUpstream(url: string, credentials: WispCredentials | null): UpstreamConnector {
	return {
		connect: async (gate: ReadGate) =>
			new WispUpstream(await connectWisp(url, credentials, gate), new URL(url).host),
	};
}
