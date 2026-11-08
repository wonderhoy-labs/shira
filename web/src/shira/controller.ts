import {
	type ActivateMode,
	type AppInfo,
	connectWisp,
	InstallError,
	LogStore,
	normalizeServerUrl,
	type Phone,
	ShiraLink,
	type LinkState as ShiraLinkState,
	type StopReason,
	type WispCredentials,
	WispError,
} from "@wonderhoy-labs/shira";
import { knownPhone, openUsbPhone, pickPhone } from "../adb/usb";

export type { ActivateMode };
export type Step = "phone" | "app" | "server" | "activate" | "live";
export type LinkState = ShiraLinkState | "phone-lost";

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
	private link: ShiraLink | null = null;
	private serverUrl: string | null = null;
	private credentials: WispCredentials | null = null;
	/** Bumped to cancel whatever long-running job is going on. Each one checks it's still current. */
	private job = 0;
	private usingUsb = false;

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
		void phone.packagesByUid().then(
			(packages) => {
				this.log.packages = packages;
			},
			(error: unknown) => console.warn("couldn't list the phone's packages", error),
		);
		await this.afterPhone();
	}

	private onPhoneLost(phone: Phone) {
		if (this.phone !== phone) return;
		this.phone = null;
		this.job++;
		this.link?.stop();
		this.link = null;
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
			this.startLink(this.state.mode);
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
		this.update({ mode, error: null });
		this.startLink(mode);
	}

	cancelActivate() {
		this.link?.stop();
		this.update({ mode: null, link: "idle", busy: null });
	}

	/** Turns Shira off on the phone, and stays on this page ready to turn it back on. */
	deactivate() {
		this.link?.deactivate();
		this.update({ mode: null, link: "idle", step: "activate", busy: null });
	}

	/** For a phone that went away while using the adb server, which can't announce its return. */
	reconnect() {
		this.update({ step: "phone", error: null });
	}

	private startLink(mode: ActivateMode) {
		this.link?.stop();
		const phone = this.phone;
		if (!phone || !this.serverUrl) {
			this.update({
				mode: null,
				link: "idle",
				error: phone ? "Choose a Wisp server first." : "The phone was disconnected.",
			});
			return;
		}
		const link = new ShiraLink({
			phone,
			serverUrl: this.serverUrl,
			credentials: this.credentials,
			log: this.log,
			events: {
				onState: (state) => {
					if (this.link === link)
						this.update(state === "live" ? { link: state, step: "live" } : { link: state });
				},
				onBusy: (busy) => {
					if (this.link === link) this.update({ busy });
				},
				onHello: (appVersion) => this.update({ appVersion }),
				onServer: ({ udp, wispVersion, message: serverMessage }) =>
					this.update({ udp, wispVersion, serverMessage }),
				onStopped: (reason) => {
					if (this.link === link) this.onLinkStopped(reason);
				},
			},
		});
		this.link = link;
		link.start(mode);
	}

	private onLinkStopped(reason: StopReason) {
		this.link = null;
		const idle = { mode: null, link: "idle", busy: null } as const;
		switch (reason.kind) {
			case "app-mismatch":
				this.update({
					...idle,
					step: "app",
					appCurrent: false,
					error: `${reason.message} Install the version from this site.`,
				});
				break;
			case "off":
				this.update({ ...idle, step: "activate", error: "Shira was turned off on the phone." });
				break;
			case "replaced":
				this.update({
					...idle,
					step: "activate",
					error: "Another page took over this phone. Activate here to take it back.",
				});
				break;
			case "error":
				this.update({ ...idle, step: "activate", error: reason.message });
				break;
		}
	}
}

function message(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}
