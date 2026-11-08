import { Phone } from "@wonderhoy-labs/shira";
import { Adb, AdbDaemonTransport } from "@yume-chan/adb";
import AdbWebCredentialStore from "@yume-chan/adb-credential-web";
import { AdbDaemonWebUsbDevice, AdbDaemonWebUsbDeviceManager } from "@yume-chan/adb-daemon-webusb";

/** How much can be on its way before adb waits for the other side. Bigger is faster, and adds some delay. */
const DELAYED_ACK_BYTES = 1024 * 1024;

const manager = AdbDaemonWebUsbDeviceManager.BROWSER;

export const isUsbAvailable = manager !== undefined;

/** Shows the browser's list of phones. Only works straight after a click. Null if nothing was picked. */
export async function pickPhone(): Promise<AdbDaemonWebUsbDevice | null> {
	return (await manager?.requestDevice()) ?? null;
}

/** A phone this site's already been allowed to use, if there's exactly one plugged in. */
export async function knownPhone(): Promise<AdbDaemonWebUsbDevice | null> {
	const devices = (await manager?.getDevices()) ?? [];
	return devices.length === 1 ? (devices[0] ?? null) : null;
}

export class UsbBusyError extends Error {
	constructor() {
		super(
			'Something else on this computer is using USB debugging. Close any other WebADB tabs, run "adb kill-server", and quit Android Studio if it\'s open.',
		);
		this.name = "UsbBusyError";
	}
}

/** Connects, which is when the phone asks whether to allow this computer if it doesn't know it yet. */
export async function openUsbPhone(device: AdbDaemonWebUsbDevice): Promise<Phone> {
	let connection: Awaited<ReturnType<AdbDaemonWebUsbDevice["connect"]>>;
	try {
		connection = await device.connect();
	} catch (error) {
		if (error instanceof AdbDaemonWebUsbDevice.DeviceBusyError) throw new UsbBusyError();
		throw error;
	}
	const transport = await AdbDaemonTransport.authenticate({
		serial: device.serial,
		connection,
		credentialStore: new AdbWebCredentialStore("Shira"),
		initialDelayedAckBytes: DELAYED_ACK_BYTES,
	});
	return new Phone(new Adb(transport), device.name || "Your phone", "usb");
}
