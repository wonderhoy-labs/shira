import { afterEach, describe, expect, it, vi } from "vitest";
import { FlowControlledSocket } from "./socket";

type Server = "takes writes" | "takes no writes" | "never answers";

/**
 * Stands in for the browser's WebSocketStream and writes down what reaches the server. Like the real one, nothing
 * gets out once it's closed.
 */
class FakeWebSocketStream {
	static server: Server = "takes writes";
	static last: FakeWebSocketStream;

	/** What the server got, in order. */
	readonly received: string[] = [];
	closeCalls = 0;
	readonly opened: Promise<{ readable: ReadableStream; writable: WritableStream<Uint8Array>; protocol: string }>;
	readonly closed: Promise<unknown>;
	private markClosed: () => void = () => undefined;
	private isClosed = false;

	constructor() {
		FakeWebSocketStream.last = this;
		const server = FakeWebSocketStream.server;
		this.closed = new Promise((resolve) => {
			this.markClosed = () => resolve(undefined);
		});
		const writable = new WritableStream<Uint8Array>({
			write: (chunk) => {
				if (server === "takes no writes") return new Promise(() => undefined);
				if (this.isClosed) throw new Error("The WebSocket is closed.");
				this.received.push(`frame ${chunk[0]}`);
			},
		});
		this.opened =
			server === "never answers"
				? new Promise(() => undefined)
				: Promise.resolve({ readable: new ReadableStream(), writable, protocol: "" });
	}

	close() {
		this.closeCalls++;
		if (this.isClosed) return;
		this.isClosed = true;
		this.received.push("closed");
		this.markClosed();
	}
}

async function openSocket(server: Server) {
	FakeWebSocketStream.server = server;
	const socket = new FlowControlledSocket("ws://server/", undefined, () => undefined, FakeWebSocketStream);
	const stream = FakeWebSocketStream.last;
	if (server !== "never answers") await stream.opened;
	return { socket, stream };
}

/** Lets every pending promise callback run, without letting a timer fire. */
async function settleMicrotasks() {
	for (let i = 0; i < 50; i++) await Promise.resolve();
}

describe("FlowControlledSocket.close", () => {
	afterEach(() => {
		vi.useRealTimers();
	});

	it("sends everything it was given before closing", async () => {
		const { socket, stream } = await openSocket("takes writes");
		for (const frame of [1, 2, 3]) socket.send(new Uint8Array([frame]));
		socket.close();
		await stream.closed;
		expect(stream.received).toEqual(["frame 1", "frame 2", "frame 3", "closed"]);
	});

	it("never closes the WebSocketStream in the task that wrote to it", async () => {
		// Chrome's tab crashes when it does: Deactivate closed every Wisp stream and then the connection.
		const { socket, stream } = await openSocket("takes writes");
		socket.send(new Uint8Array([1]));
		socket.close();
		await settleMicrotasks();
		expect(stream.closeCalls).toBe(0);
		await stream.closed;
		expect(stream.closeCalls).toBe(1);
	});

	it("sends nothing more once it's closing", async () => {
		const { socket, stream } = await openSocket("takes writes");
		socket.send(new Uint8Array([1]));
		socket.close();
		socket.send(new Uint8Array([2]));
		await stream.closed;
		expect(stream.received).toEqual(["frame 1", "closed"]);
	});

	it("closes anyway when the server stops taking what's sent", async () => {
		vi.useFakeTimers();
		const { socket, stream } = await openSocket("takes no writes");
		socket.send(new Uint8Array([1]));
		socket.close();
		await vi.advanceTimersByTimeAsync(5_000);
		expect(stream.closeCalls).toBe(1);
	});

	it("closes a connection that never opened", async () => {
		const { socket, stream } = await openSocket("never answers");
		socket.close();
		await stream.closed;
		expect(stream.closeCalls).toBe(1);
	});

	it("closes once, however often it's asked", async () => {
		const { socket, stream } = await openSocket("takes writes");
		socket.close();
		socket.close();
		await stream.closed;
		await new Promise((resolve) => setTimeout(resolve, 10));
		expect(stream.closeCalls).toBe(1);
	});
});
