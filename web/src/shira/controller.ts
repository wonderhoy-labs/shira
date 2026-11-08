import { type AppInfo, InstallError, type Phone } from "../adb/phone";
import { knownPhone, openUsbPhone, pickPhone } from "../adb/usb";
import { CLOSE_NETWORK_ERROR, LinkStatus, MetaKind, StreamType } from "../link/frames";
import { ProtocolMismatchError, Relay, type RelayEvents } from "../link/relay";
import { LogStore } from "../log/store";
import {
	connectWisp,
	normalizeServerUrl,
	type WispConnection,
	type WispCredentials,
	WispError,
} from "../wisp/connection";

export type Step = "phone" | "app" | "server" | "activate" | "live";
export type LinkState = "idle" | "waiting-for-app" | "connecting" | "live" | "server-lost" | "phone-lost";
export type ActivateMode = "auto" | "manual";

export interface ShiraState {
	step: Step;
	/** The phone's name once it's attached. */
	phone: string | null;
	app: AppInfo | null;
	/** True if the app's version is what this site carries, or newer. */
	appCurrent: boolean;
	installing: boolean;
	installProgress: number;
	installManually: boolean;
	/** The install failed in a way that only removing the old copy can fix. */
	offerUninstall: boolean;
	/** What to tell the person to do while something's happening. */
	busy: string | null;
	error: string | null;

	serverInput: string;
	username: string;
	/** Show the login fields, because the server asked or someone asked for them. */
	loginNeeded: boolean;
	serverLabel: string | null;
	serverMessage: string | null;
	checkingServer: boolean;

	mode: ActivateMode | null;
	link: LinkState;
	/** The app's version, once it's said hello. */
	appVersion: string | null;
	udp: boolean;
	wispVersion: number | null;
}

const STORED_SERVER = "shira.server";
const STORED_USERNAME = "shira.username";
const APK_PATH = "shira.apk";
const POLL_MILLIS = 1_000;
const MAX_RETRY_MILLIS = 15_000;
const FIRST_RETRY_MILLIS = 1_000;
const SLOW_ACTIVATE_MILLIS = 10_000;
const PACKAGE_REFRESH_MILLIS = 10_000;

function stored(key: string): string {
	try {
		return localStorage.getItem(key) ?? "";
	} catch {
		return "";
	}
}

function store(key: string, value: string) {
	try {
		localStorage.setItem(key, value);
	} catch {
		// Without storage it's just asked for again next time.
	}
}

function defaultServer(): string {
	if (import.meta.env.DEV) {
		const scheme = location.protocol === "https:" ? "wss:" : "ws:";
		return `${scheme}//${location.host}/wisp/`;
	}
	return stored(STORED_SERVER);
}

const sleep = (millis: number) => new Promise<void>((resolve) => setTimeout(resolve, millis));

/** Everything the page does, step by step. The screens only show this and call its methods. */
export class Controller {
	readonly log = new LogStore();

	private state: ShiraState = {
		step: "phone",
		phone: null,
		app: null,
		appCurrent: false,
		installing: false,
		installProgress: 0,
		installManually: false,
		offerUninstall: false,
		busy: null,
		error: null,
		serverInput: defaultServer(),
		username: stored(STORED_USERNAME),
		loginNeeded: false,
		serverLabel: null,
		serverMessage: null,
		checkingServer: false,
		mode: null,
		link: "idle",
		appVersion: null,
		udp: false,
		wispVersion: null,
	};

	private readonly listeners = new Set<() => void>();
	private phone: Phone | null = null;
	private relay: Relay | null = null;
	private wisp: WispConnection | null = null;
	private serverUrl: string | null = null;
	private credentials: WispCredentials | null = null;
	/** Bumped to cancel whatever long-running job is going on. Each one checks it's still current. */
	private job = 0;
	private usingUsb = false;
	private lastPackageRefresh = 0;
	private statusWaiter: ((up: { ok: true } | { ok: false; message: string }) => void) | null = null;
	private deactivating = false;
	/** Why the app said it was closing the socket, as opposed to the socket just going away. */
	private goodbye: "off" | "replaced" | null = null;

	constructor() {
		navigator.usb?.addEventListener("connect", () => void this.onUsbPlugged());
		// The phone's told by its socket closing, if this gets to run. If it doesn't, the pings stop.
		window.addEventListener("pagehide", () => void this.phone?.close().catch(() => undefined));
	}

	readonly subscribe = (listener: () => void) => {
		this.listeners.add(listener);
		return () => this.listeners.delete(listener);
	};

	readonly getState = () => this.state;

	private update(changes: Partial<ShiraState>) {
		this.state = { ...this.state, ...changes };
		for (const listener of this.listeners) listener();
	}

	dismissError() {
		this.update({ error: null });
	}

	// Step 1: the phone

	/** Has to be called straight from a click, because of the browser's list of devices. */
	async connectUsb() {
		this.update({ error: null });
		try {
			const device = await pickPhone();
			if (!device) return;
			this.usingUsb = true;
			await this.attach(() => openUsbPhone(device));
		} catch (error) {
			this.update({ busy: null, error: `Couldn't reach your phone: ${message(error)}` });
		}
	}

	/** Development only: uses a phone or emulator through the adb server. */
	async connectThroughAdbServer(device: import("../adb/dev-server").DevDevice) {
		if (!import.meta.env.DEV) return;
		this.update({ error: null });
		try {
			const { openDevPhone } = await import("../adb/dev-server");
			this.usingUsb = false;
			await this.attach(() => openDevPhone(device));
		} catch (error) {
			this.update({ busy: null, error: `Couldn't reach that device: ${message(error)}` });
		}
	}

	private async onUsbPlugged() {
		// Only to carry on after a replug. The first connection is always someone's click.
		if (!this.usingUsb || this.phone) return;
		try {
			const device = await knownPhone();
			if (device && !this.phone) await this.attach(() => openUsbPhone(device));
		} catch (error) {
			this.update({ busy: null, error: `Couldn't reach your phone: ${message(error)}` });
		}
	}

	private async attach(open: () => Promise<Phone>) {
		this.update({ busy: "Unlock your phone. If it asks whether to allow USB debugging, tap Allow." });
		const phone = await open();
		this.phone = phone;
		void phone.disconnected.then(
			() => this.onPhoneLost(phone),
			() => this.onPhoneLost(phone),
		);
		this.update({ phone: phone.name, busy: null, error: null });
		void this.refreshPackages(true);
		await this.afterPhone();
	}

	private onPhoneLost(phone: Phone) {
		if (this.phone !== phone) return;
		this.phone = null;
		this.job++;
		this.statusWaiter?.({ ok: false, message: "The phone was disconnected." });
		this.statusWaiter = null;
		this.stopLink();
		this.update({
			phone: null,
			installing: false,
			busy: null,
			link: this.state.mode ? "phone-lost" : "idle",
			step: this.state.mode ? this.state.step : "phone",
		});
	}

	/** Goes wherever the phone's state calls for, and picks a running session back up after a replug. */
	private async afterPhone() {
		const app = await this.refreshApp();
		if (this.state.mode && app.installed && this.appIsCurrent(app)) {
			void this.runLink();
			return;
		}
		this.update({ step: this.stepAfterPhone(app) });
	}

	private appIsCurrent(app: AppInfo) {
		return app.installed && (app.versionCode ?? 0) >= __SHIRA_VERSION_CODE__;
	}

	private stepAfterPhone(app: AppInfo): Step {
		if (!this.appIsCurrent(app)) return "app";
		return this.serverUrl ? "activate" : "server";
	}

	private async refreshApp(): Promise<AppInfo> {
		const app = await this.requirePhone().appInfo();
		this.update({ app, appCurrent: this.appIsCurrent(app) });
		return app;
	}

	private requirePhone(): Phone {
		if (!this.phone) throw new Error("The phone was disconnected.");
		return this.phone;
	}

	// Step 2: the app

	async installApp() {
		this.update({
			installing: true,
			installProgress: 0,
			installManually: false,
			offerUninstall: false,
			error: null,
		});
		try {
			const response = await fetch(APK_PATH);
			if (!response.ok) throw new Error(`This site is missing ${APK_PATH}.`);
			const apk = new Uint8Array(await response.arrayBuffer());
			await this.requirePhone().install(apk, (installProgress) => this.update({ installProgress }));
			const app = await this.refreshApp();
			this.update({ installing: false, step: this.stepAfterPhone(app) });
		} catch (error) {
			const uninstall = error instanceof InstallError && error.needsUninstall;
			this.update({
				installing: false,
				offerUninstall: uninstall,
				error: uninstall
					? "The copy of Shira on your phone was signed with a different key, so Android won't update it. Remove it to carry on."
					: `Couldn't install the app: ${message(error)}`,
			});
		}
	}

	async uninstallAndInstall() {
		try {
			await this.requirePhone().uninstall();
			await this.installApp();
		} catch (error) {
			this.update({ error: `Couldn't remove the old app: ${message(error)}` });
		}
	}

	/** Waits for someone to install it by hand, and carries on when it appears. */
	async installManually() {
		const job = ++this.job;
		this.update({ installManually: true, error: null });
		while (this.job === job && this.phone) {
			const app = await this.refreshApp();
			if (this.appIsCurrent(app)) {
				this.update({ installManually: false, step: this.stepAfterPhone(app) });
				return;
			}
			await sleep(POLL_MILLIS);
		}
	}

	cancelManualInstall() {
		this.job++;
		this.update({ installManually: false });
	}

	// Step 3: the Wisp server

	setServerInput(serverInput: string) {
		this.update({ serverInput, error: null });
	}

	setUsername(username: string) {
		this.update({ username });
	}

	showLogin() {
		this.update({ loginNeeded: true });
	}

	/** Checks the address really is a Wisp server that'll have us, by connecting to it. */
	async chooseServer(password: string) {
		const normalized = normalizeServerUrl(this.state.serverInput);
		if ("problem" in normalized) {
			this.update({ error: normalized.problem });
			return;
		}
		const username = this.state.username.trim();
		const credentials = this.state.loginNeeded && username ? { username, password } : null;
		this.update({ checkingServer: true, error: null, serverInput: normalized.url });
		try {
			const connection = await connectWisp(normalized.url, credentials);
			const motd = connection.server_motd ?? null;
			this.update({
				udp: connection.udp_enabled,
				wispVersion: connection.wisp_version,
				serverMessage: motd,
			});
			connection.onclose = () => undefined;
			connection.close();

			this.serverUrl = normalized.url;
			this.credentials = credentials;
			store(STORED_SERVER, normalized.url);
			if (credentials) store(STORED_USERNAME, credentials.username);
			this.update({ checkingServer: false, serverLabel: new URL(normalized.url).host, step: "activate" });
		} catch (error) {
			const needsLogin =
				error instanceof WispError &&
				(error.failure === "credentials-needed" || error.failure === "credentials-wrong");
			this.update({
				checkingServer: false,
				loginNeeded: this.state.loginNeeded || needsLogin,
				error: message(error),
			});
		}
	}

	changeServer() {
		this.update({ step: "server", error: null });
	}

	// Step 4: going live

	activate(mode: ActivateMode) {
		this.deactivating = false;
		this.update({ mode, error: null });
		void this.runLink();
	}

	cancelActivate() {
		this.job++;
		this.stopLink();
		this.update({ mode: null, link: "idle", busy: null });
	}

	/** Turns Shira off on the phone, and stays on this page ready to turn it back on. */
	deactivate() {
		this.job++;
		this.deactivating = true;
		this.update({ mode: null });
		const relay = this.relay;
		if (relay) relay.deactivate();
		this.closeWisp();
		this.update({ link: "idle", step: "activate", busy: null });
	}

	/** For a phone that went away while using the adb server, which can't announce its return. */
	reconnect() {
		this.update({ step: "phone", error: null });
	}

	/**
	 * Gets from here to connected. [startApp] is false when going back to an app that's already running,
	 * because anything else would turn Shira back on after someone turned it off.
	 */
	private async runLink(startApp = true) {
		const job = ++this.job;
		const isCurrent = () => this.job === job;
		const mode = this.state.mode;
		let phone: Phone;
		try {
			phone = this.requirePhone();
			this.update({ link: "waiting-for-app", busy: null });
			this.goodbye = null;
			if (mode === "auto" && startApp) await phone.activate();
			const relay = await this.waitForApp(phone, mode, isCurrent);
			if (!relay || !isCurrent()) return;
			await this.keepConnected(relay, isCurrent);
		} catch (error) {
			if (!isCurrent()) return;
			this.stopLink();
			if (error instanceof ProtocolMismatchError) {
				this.update({
					mode: null,
					link: "idle",
					step: "app",
					appCurrent: false,
					error: `${error.message} Install the version from this site.`,
				});
				return;
			}
			this.update({ mode: null, link: "idle", step: "activate", busy: null, error: message(error) });
		}
	}

	/**
	 * Waits for the app's service, and connects to its socket.
	 *
	 * Any app on the phone could listen on the socket's name, so this waits until Shira's own service is
	 * running first. The service stops if it can't get the name, so once it's running the name is its own.
	 */
	private async waitForApp(phone: Phone, mode: ActivateMode | null, isCurrent: () => boolean): Promise<Relay | null> {
		const started = Date.now();
		while (isCurrent()) {
			if (mode === "auto" && Date.now() - started > SLOW_ACTIVATE_MILLIS) {
				this.update({ busy: "Unlock your phone. If it's asking about a VPN connection, tap OK." });
			}
			if (await phone.isServiceRunning()) {
				try {
					const socket = await phone.openSocket();
					const relay = new Relay(socket, this.relayEvents());
					this.relay = relay;
					const hello = await relay.greet();
					this.update({ appVersion: hello.appVersion, busy: null });
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

	/** Stays connected to the Wisp server until the phone goes or the page says stop. */
	private async keepConnected(relay: Relay, isCurrent: () => boolean) {
		const url = this.serverUrl;
		if (!url) throw new Error("Choose a Wisp server first.");
		let delay = FIRST_RETRY_MILLIS;
		let everUp = false;

		while (isCurrent() && this.relay === relay) {
			this.update({ link: everUp ? "server-lost" : "connecting" });
			let wisp: WispConnection;
			try {
				wisp = await connectWisp(url, this.credentials, relay.downloadGate);
			} catch (error) {
				const refused = error instanceof WispError && error.failure !== "unreachable";
				// A login problem won't fix itself, and a server that never worked probably has the wrong address.
				if (refused || !everUp) throw error;
				await sleep(delay);
				delay = Math.min(delay * 2, MAX_RETRY_MILLIS);
				continue;
			}
			if (!isCurrent() || this.relay !== relay) {
				wisp.close();
				return;
			}
			this.wisp = wisp;
			const lost = new Promise<void>((resolve) => {
				wisp.onclose = () => resolve();
				wisp.onerror = () => resolve();
			});
			this.update({
				udp: wisp.udp_enabled,
				wispVersion: wisp.wisp_version,
				serverMessage: wisp.server_motd ?? null,
			});

			const status = new Promise<{ ok: true } | { ok: false; message: string }>((resolve) => {
				this.statusWaiter = resolve;
			});
			relay.attach(wisp, new URL(url).host);
			const up = await status;
			if (!up.ok) {
				wisp.onclose = () => undefined;
				wisp.close();
				throw new Error(up.message);
			}
			everUp = true;
			delay = FIRST_RETRY_MILLIS;
			this.update({ link: "live", step: "live", busy: null });

			await lost;
			if (!isCurrent() || this.relay !== relay) return;
			this.wisp = null;
			this.log.closeAll(CLOSE_NETWORK_ERROR, Date.now());
			relay.detach();
			this.update({ link: "server-lost" });
			await sleep(delay);
		}
	}

	private relayEvents(): RelayEvents {
		return {
			onStreamOpen: (stream, request) => {
				const kind = request.streamType === StreamType.UDP ? "udp" : "tcp";
				this.log.open(stream, kind, request.host, request.port, Date.now());
			},
			onStreamMeta: (stream, meta) => {
				this.log.describe(
					stream,
					meta.kind === MetaKind.DNS ? "dns" : null,
					meta.uid,
					meta.name,
					meta.queryType,
				);
				if (meta.uid >= 0 && !this.log.packages.has(meta.uid)) void this.refreshPackages(false);
			},
			onStreamData: (stream, up, down) => this.log.addBytes(stream, up, down),
			onStreamClose: (stream, reason) => this.log.close(stream, reason, Date.now()),
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
		this.closeWisp();
		this.log.closeAll(CLOSE_NETWORK_ERROR, Date.now());
		// Unplugging ends this too, and that's handled when adb notices, a moment later.
		if (!this.phone || wasDeactivating || !this.state.mode) return;
		if (this.state.link === "waiting-for-app") return;
		const standDown = (reason: string) => {
			this.job++;
			this.statusWaiter?.({ ok: false, message: reason });
			this.statusWaiter = null;
			this.update({ mode: null, link: "idle", step: "activate", error: reason });
		};
		const offMessage = "Shira was turned off on the phone.";
		if (this.goodbye === "off") {
			standDown(offMessage);
			return;
		}
		if (this.goodbye === "replaced") {
			standDown("Another page took over this phone. Activate here to take it back.");
			return;
		}
		void this.phone.isServiceRunning().then(
			(running) => {
				if (!this.phone || !this.state.mode) return;
				// It dropped this connection for being silent, say, so connect again.
				if (running) void this.runLink(false);
				else standDown(offMessage);
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
		this.closeWisp();
		this.log.closeAll(CLOSE_NETWORK_ERROR, Date.now());
	}

	private closeWisp() {
		const wisp = this.wisp;
		this.wisp = null;
		if (!wisp) return;
		wisp.onclose = () => undefined;
		wisp.onerror = () => undefined;
		wisp.close();
	}

	private async refreshPackages(force: boolean) {
		const now = Date.now();
		if (!force && now - this.lastPackageRefresh < PACKAGE_REFRESH_MILLIS) return;
		this.lastPackageRefresh = now;
		try {
			const packages = await this.phone?.packagesByUid();
			if (packages) this.log.packages = packages;
		} catch (error) {
			console.warn("couldn't list the phone's packages", error);
		}
	}
}

function message(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}
