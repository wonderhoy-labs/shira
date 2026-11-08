import { AdbServerClient } from "@yume-chan/adb";
import { MaybeConsumable, ReadableStream } from "@yume-chan/stream-extra";
import { Phone } from "./phone";

/**
 * Reaches the adb server on this computer through the development server. Emulators don't appear over
 * USB, so this is how the real page gets to one. It doesn't exist in production builds.
 */
class WebSocketConnector implements AdbServerClient.ServerConnector {
	async connect(): Promise<AdbServerClient.ServerConnection> {
		const scheme = location.protocol === "https:" ? "wss:" : "ws:";
		const socket = new WebSocket(`${scheme}//${location.host}/__shira/adb`);
		socket.binaryType = "arraybuffer";
		await new Promise<void>((resolve, reject) => {
			socket.onopen = () => resolve();
			socket.onerror = () => reject(new Error("Couldn't reach the adb server. Is adb running?"));
		});

		let markClosed: () => void = () => undefined;
		const closed = new Promise<undefined>((resolve) => {
			markClosed = () => resolve(undefined);
		});
		const readable = new ReadableStream<Uint8Array>({
			start(controller) {
				socket.onmessage = (event) => controller.enqueue(new Uint8Array(event.data as ArrayBuffer));
				socket.onclose = () => {
					try {
						controller.close();
					} catch {
						// It was already cancelled.
					}
					markClosed();
				};
			},
			cancel() {
				socket.close();
			},
		});
		const writable = new MaybeConsumable.WritableStream<Uint8Array>({
			write(chunk) {
				if (socket.readyState === WebSocket.OPEN) socket.send(chunk as Uint8Array<ArrayBuffer>);
			},
			close() {
				// Only the sending side. An empty message tells the development server to end its input to adb.
				if (socket.readyState === WebSocket.OPEN) socket.send(new Uint8Array(0));
			},
		});
		return { readable, writable, closed, close: () => socket.close() };
	}

	addReverseTunnel(): never {
		throw new Error("Reverse tunnels are not supported here.");
	}

	removeReverseTunnel(): void {
		// There are never any, because adding one throws.
	}

	clearReverseTunnels(): void {
		// There are never any, because adding one throws.
	}
}

const client = new AdbServerClient(new WebSocketConnector());

export interface DevDevice {
	serial: string;
	label: string;
}

export async function listDevDevices(): Promise<DevDevice[]> {
	const devices = await client.getDevices(["device"]);
	return devices.map((device) => ({
		serial: device.serial,
		label: device.model ? `${device.model.replaceAll("_", " ")} (${device.serial})` : device.serial,
	}));
}

export async function openDevPhone(device: DevDevice): Promise<Phone> {
	const transport = await client.createTransport({ serial: device.serial });
	const { Adb } = await import("@yume-chan/adb");
	return new Phone(new Adb(transport), device.label, "adb-server");
}
