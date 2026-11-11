import { CLOSE_NETWORK_ERROR, LinkStatus, MetaKind, StreamType } from "./link/frames";
import { ProtocolMismatchError, Relay, type RelayEvents } from "./link/relay";
import type { LogStore } from "./log/store";
import type { Phone } from "./phone";
import { type Upstream, type UpstreamConnector, UpstreamError } from "./upstream";
import { type WispCredentials, WispError } from "./wisp/connection";
import { wispUpstream } from "./wisp/upstream";

/** Where the link is. "server-lost" means the phone is still there and the upstream is being tried again. */
export type LinkState = "idle" | "waiting-for-app" | "connecting" | "live" | "server-lost";

/** "auto" opens the app's activation screen, which asks Android for the VPN. "manual" waits for someone to. */
export type ActivateMode = "auto" | "manual";

/** Why a link stopped by itself. */
export type StopReason =
	/** Someone turned Shira off on the phone. */
	| { kind: "off" }
	/** Another page took over the phone's link. It should stay away, or the two would keep taking it back. */
	| { kind: "replaced" }
	/** The app speaks another version of the link's protocol, so it needs the version this library carries. */
	| { kind: "app-mismatch"; message: string }
	/** Anything else, such as the Wisp server refusing a login, or Android refusing the VPN. */
	| { kind: "error"; message: string };

/** The upstream's details, once it has answered. */
export interface ServerDetails {
	udp: boolean;
	/** The Wisp protocol version, or 0 for an upstream that isn't a Wisp server. */
	wispVersion: number;
	message: string | null;
}

export interface LinkEvents {
	onState?(state: LinkState): void;
	/** Something to tell the person while waiting, such as to unlock the phone, or null once it's no longer true. */
	onBusy?(message: string | null): void;
	/** The app said hello, with its version. */
	onHello?(appVersion: string): void;
	onServer?(details: ServerDetails): void;
	/** The link gave up. It's idle again, and start() tries afresh. */
	onStopped?(reason: StopReason): void;
}

export interface LinkOptions {
	phone: Phone;
	/** A Wisp server address that normalizeServerUrl accepted. Ignored when there's an upstream. */
	serverUrl?: string;
	/** The Wisp server's login. Ignored when there's an upstream. */
	credentials?: WispCredentials | null;
	/** Where the phone's connections go, instead of the Wisp server at serverUrl. */
	upstream?: UpstreamConnector;
	/** Where every connection is written down. The same one can carry on across links. */
	log: LogStore;
	events: LinkEvents;
}

const POLL_MILLIS = 1_000;
const MAX_RETRY_MILLIS = 15_000;
const FIRST_RETRY_MILLIS = 1_000;
const SLOW_ACTIVATE_MILLIS = 10_000;
const PACKAGE_REFRESH_MILLIS = 10_000;

const sleep = (millis: number) => new Promise<void>((resolve) => setTimeout(resolve, millis));

/**
 * Gives a phone running Shira's app a network: waits for the app's VPN, greets it over adb, and relays its connections
 * through a Wisp server, or another upstream, until stopped. It reconnects by itself when the upstream drops, and when
 * the app drops the connection for being quiet.
 */
export class ShiraLink {
	private linkState: LinkState = "idle";
	private relay: Relay | null = null;
	private upstream: Upstream | null = null;
	private mode: ActivateMode | null = null;
	/** Bumped to cancel whatever long-running job is going on. Each one checks it's still current. */
	private job = 0;
	private lastPackageRefresh = 0;
	private statusWaiter: ((up: { ok: true } | { ok: false; message: string }) => void) | null = null;
	private deactivating = false;
	/** Why the app said it was closing the socket, as opposed to the socket just going away. */
	private goodbye: "off" | "replaced" | null = null;

	constructor(private readonly options: LinkOptions) {}

	get state(): LinkState {
		return this.linkState;
	}

	/** Whether start() was called and the link hasn't stopped since. */
	get isActive(): boolean {
		return this.mode !== null;
	}

	/** Goes from here to connected, and stays there. */
	start(mode: ActivateMode) {
		this.deactivating = false;
		this.mode = mode;
		void this.run();
	}

	/** Lets go of the phone without turning Shira off on it, as when the page closes or the phone goes. */
	stop() {
		this.job++;
		this.mode = null;
		this.stopLink();
		this.setState("idle");
		this.options.events.onBusy?.(null);
	}

	/** Turns Shira off on the phone, which then goes back to its own network. */
	deactivate() {
		this.job++;
		this.deactivating = true;
		this.mode = null;
		this.relay?.deactivate();
		this.closeUpstream();
		this.setState("idle");
		this.options.events.onBusy?.(null);
	}

	private setState(state: LinkState) {
		this.linkState = state;
		this.options.events.onState?.(state);
	}

	private fail(reason: StopReason) {
		this.mode = null;
		this.setState("idle");
		this.options.events.onBusy?.(null);
		this.options.events.onStopped?.(reason);
	}

	/**
	 * [startApp] is false when going back to an app that's already running, because anything else would turn Shira
	 * back on after someone turned it off.
	 */
	private async run(startApp = true) {
		const job = ++this.job;
		const isCurrent = () => this.job === job;
		const { phone, events } = this.options;
		try {
			this.setState("waiting-for-app");
			events.onBusy?.(null);
			this.goodbye = null;
			if (this.mode === "auto" && startApp) await phone.activate();
			const relay = await this.waitForApp(isCurrent);
			if (!relay || !isCurrent()) return;
			await this.keepConnected(relay, isCurrent);
		} catch (error) {
			if (!isCurrent()) return;
			this.stopLink();
			if (error instanceof ProtocolMismatchError) {
				this.fail({ kind: "app-mismatch", message: error.message });
				return;
			}
			this.fail({ kind: "error", message: error instanceof Error ? error.message : String(error) });
		}
	}

	/**
	 * Waits for the app's service, and connects to its socket.
	 *
	 * Any app on the phone could listen on the socket's name, so this waits until Shira's own service is running
	 * first. The service stops if it can't get the name, so once it's running the name is its own.
	 */
	private async waitForApp(isCurrent: () => boolean): Promise<Relay | null> {
		const { phone, events } = this.options;
		const started = Date.now();
		while (isCurrent()) {
			if (this.mode === "auto" && Date.now() - started > SLOW_ACTIVATE_MILLIS) {
				events.onBusy?.("Unlock your phone. If it's asking about a VPN connection, tap OK.");
			}
			if (await phone.isServiceRunning()) {
				try {
					const socket = await phone.openSocket();
					const relay = new Relay(socket, this.relayEvents());
					this.relay = relay;
					const hello = await relay.greet();
					events.onHello?.(hello.appVersion);
					events.onBusy?.(null);
					return relay;
				} catch (error) {
					this.stopLink();
					if (error instanceof ProtocolMismatchError) throw error;
					// The service is still setting up its socket.
				}
			}
			await sleep(POLL_MILLIS);
		}
		return null;
	}

	private connector(): UpstreamConnector {
		const { upstream, serverUrl, credentials } = this.options;
		if (upstream) return upstream;
		if (!serverUrl) throw new Error("There's no Wisp server or other upstream to connect to.");
		return wispUpstream(serverUrl, credentials ?? null);
	}

	/** Stays connected to the upstream until the phone goes or the page says stop. */
	private async keepConnected(relay: Relay, isCurrent: () => boolean) {
		const { log, events } = this.options;
		const connector = this.connector();
		let delay = FIRST_RETRY_MILLIS;
		let everUp = false;

		while (isCurrent() && this.relay === relay) {
			this.setState(everUp ? "server-lost" : "connecting");
			let upstream: Upstream;
			try {
				upstream = await connector.connect(relay.downloadGate);
			} catch (error) {
				const refused =
					(error instanceof WispError && error.failure !== "unreachable") ||
					(error instanceof UpstreamError && error.permanent);
				// A login problem won't fix itself, and a server that never worked probably has the wrong address.
				if (refused || !everUp) throw error;
				await sleep(delay);
				delay = Math.min(delay * 2, MAX_RETRY_MILLIS);
				continue;
			}
			if (!isCurrent() || this.relay !== relay) {
				upstream.close();
				return;
			}
			this.upstream = upstream;
			events.onServer?.({ udp: upstream.udp, ...upstream.details });

			const status = new Promise<{ ok: true } | { ok: false; message: string }>((resolve) => {
				this.statusWaiter = resolve;
			});
			relay.attach(upstream);
			const up = await status;
			if (!up.ok) {
				this.upstream = null;
				upstream.close();
				throw new Error(up.message);
			}
			everUp = true;
			delay = FIRST_RETRY_MILLIS;
			events.onBusy?.(null);
			this.setState("live");

			await upstream.lost;
			if (!isCurrent() || this.relay !== relay) return;
			this.upstream = null;
			log.closeAll(CLOSE_NETWORK_ERROR, Date.now());
			relay.detach();
			this.setState("server-lost");
			await sleep(delay);
		}
	}

	private relayEvents(): RelayEvents {
		const { log } = this.options;
		return {
			onStreamOpen: (stream, request) => {
				const kind = request.streamType === StreamType.UDP ? "udp" : "tcp";
				log.open(stream, kind, request.host, request.port, Date.now());
			},
			onStreamMeta: (stream, meta) => {
				log.describe(stream, meta.kind === MetaKind.DNS ? "dns" : null, meta.uid, meta.name, meta.queryType);
				if (meta.uid >= 0 && !log.packages.has(meta.uid)) void this.refreshPackages();
			},
			onStreamData: (stream, up, down) => log.addBytes(stream, up, down),
			onStreamClose: (stream, reason) => log.close(stream, reason, Date.now()),
			onStatus: (code, statusMessage) => {
				if (code === LinkStatus.UP) {
					this.statusWaiter?.({ ok: true });
					this.statusWaiter = null;
				} else if (code === LinkStatus.FAILED) {
					this.statusWaiter?.({
						ok: false,
						message: `Android wouldn't start the VPN. ${statusMessage}`.trim(),
					});
					this.statusWaiter = null;
				} else if (code === LinkStatus.OFF) {
					this.goodbye = "off";
				} else if (code === LinkStatus.REPLACED) {
					this.goodbye = "replaced";
				}
			},
			onEnded: () => this.onRelayEnded(),
		};
	}

	private onRelayEnded() {
		const wasDeactivating = this.deactivating;
		this.deactivating = false;
		this.relay = null;
		this.closeUpstream();
		this.options.log.closeAll(CLOSE_NETWORK_ERROR, Date.now());
		if (wasDeactivating || !this.mode) return;
		if (this.linkState === "waiting-for-app") return;
		const standDown = (reason: StopReason) => {
			this.job++;
			this.statusWaiter?.({ ok: false, message: reason.kind });
			this.statusWaiter = null;
			this.fail(reason);
		};
		if (this.goodbye === "off" || this.goodbye === "replaced") {
			standDown({ kind: this.goodbye });
			return;
		}
		void this.options.phone.isServiceRunning().then(
			(running) => {
				if (!this.mode) return;
				// It dropped this connection for being silent, say, so connect again.
				if (running) void this.run(false);
				else standDown({ kind: "off" });
			},
			() => undefined,
		);
	}

	private stopLink() {
		const relay = this.relay;
		this.relay = null;
		this.deactivating = true;
		relay?.close();
		this.deactivating = false;
		this.closeUpstream();
		this.options.log.closeAll(CLOSE_NETWORK_ERROR, Date.now());
	}

	private closeUpstream() {
		const upstream = this.upstream;
		this.upstream = null;
		upstream?.close();
	}

	private async refreshPackages() {
		const now = Date.now();
		if (now - this.lastPackageRefresh < PACKAGE_REFRESH_MILLIS) return;
		this.lastPackageRefresh = now;
		try {
			this.options.log.packages = await this.options.phone.packagesByUid();
		} catch (error) {
			console.warn("couldn't list the phone's packages", error);
		}
	}

	/** Lists the phone's apps for the log now, rather than waiting for a connection from one it doesn't know. */
	refreshPackagesNow() {
		this.lastPackageRefresh = 0;
		void this.refreshPackages();
	}
}
