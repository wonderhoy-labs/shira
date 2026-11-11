import type { Adb } from "@yume-chan/adb";
import { PackageManager } from "@yume-chan/android-bin";
import { type LinkApp, SHIRA_APP } from "./constants";

export type PhoneKind = "usb" | "adb-server";

export interface AppInfo {
	installed: boolean;
	versionName: string | null;
	versionCode: number | null;
}

/** Android's own explanation, or the command's output if it doesn't have one. */
function failureReason(output: string): string {
	return /\[?(INSTALL_[A-Z_]+)[\]:]?/.exec(output)?.[1] ?? (output.trim() || "Android didn't say why.");
}

/** A phone reached through adb, and the things Shira needs from it. */
export class Phone {
	constructor(
		readonly adb: Adb,
		readonly name: string,
		readonly kind: PhoneKind,
		/** The app on the phone that carries its network. Shira's own, unless another app built on its library. */
		readonly app: LinkApp = SHIRA_APP,
	) {}

	get disconnected(): Promise<unknown> {
		return this.adb.disconnected;
	}

	shell(command: string): Promise<string> {
		return this.adb.subprocess.noneProtocol.spawnWaitText(command);
	}

	async appInfo(): Promise<AppInfo> {
		const output = await this.shell(
			`dumpsys package ${this.app.package} | grep -E 'versionCode=|versionName=' | head -2`,
		);
		const code = /versionCode=(\d+)/.exec(output)?.[1];
		const name = /versionName=(\S+)/.exec(output)?.[1];
		return {
			installed: code !== undefined,
			versionName: name ?? null,
			versionCode: code === undefined ? null : Number(code),
		};
	}

	/**
	 * Hands an APK to Android's installer. Throws with Android's reason if it refuses.
	 *
	 * This pushes a temporary file instead of streaming straight into the installer. With streaming, closing
	 * the input also closes the socket that the installer's answer comes back on, so success looks like failure.
	 */
	async install(apk: Uint8Array, onProgress: (fraction: number) => void): Promise<void> {
		const chunkBytes = 256 * 1024;
		let sent = 0;
		const stream = new ReadableStream<Uint8Array>({
			pull(controller) {
				if (sent >= apk.length) {
					controller.close();
					return;
				}
				const chunk = apk.subarray(sent, sent + chunkBytes);
				sent += chunk.length;
				onProgress(sent / apk.length);
				controller.enqueue(chunk);
			},
		});
		try {
			await new PackageManager(this.adb).pushAndInstallStream(stream as never);
		} catch (error) {
			throw new InstallError(failureReason(error instanceof Error ? error.message : String(error)));
		}
	}

	async uninstall(): Promise<void> {
		await this.shell(`pm uninstall ${this.app.package}`);
	}

	/**
	 * Starts Shira without asking anything on the phone.
	 *
	 * adb is allowed to give an app the same approval the phone's own VPN dialog would, and to give it
	 * the notification permission that shows Shira's running. The screen that opens then starts the service.
	 */
	async activate(): Promise<void> {
		await this.shell(`pm grant ${this.app.package} android.permission.POST_NOTIFICATIONS 2>/dev/null`);
		await this.shell(`appops set ${this.app.package} ACTIVATE_VPN allow`);
		const output = await this.shell(`am start -n ${this.app.activateScreen}`);
		if (/^Error/m.test(output)) throw new Error(output.trim());
	}

	/** True once the app's own service is running, which is what makes the socket's name safely its own. */
	async isServiceRunning(): Promise<boolean> {
		const output = await this.shell(`dumpsys activity services ${this.app.service}`);
		return output.includes("ServiceRecord");
	}

	openSocket() {
		return this.adb.createSocket(this.app.socket);
	}

	/** Which package each app ID belongs to, for the log. */
	async packagesByUid(): Promise<Map<number, string>> {
		const output = await this.shell("pm list packages -U");
		const packages = new Map<number, string>();
		for (const match of output.matchAll(/^package:(\S+) uid:(\d+)/gm)) {
			const [, name, uid] = match;
			if (name && uid) packages.set(Number(uid), name);
		}
		return packages;
	}

	async close(): Promise<void> {
		await this.adb.close();
	}
}

export class InstallError extends Error {
	constructor(readonly reason: string) {
		super(reason);
		this.name = "InstallError";
	}

	/** A copy that's signed with another key has to be removed first. */
	get needsUninstall(): boolean {
		return this.reason.includes("UPDATE_INCOMPATIBLE") || this.reason.includes("VERSION_DOWNGRADE");
	}
}
