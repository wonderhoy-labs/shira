import { describe, expect, it } from "vitest";
import {
	encodeClose,
	encodeContinue,
	encodeData,
	encodeStart,
	encodeStop,
	FrameDecoder,
	PacketType,
	parseConnect,
	parseHello,
	parseMeta,
	parseStatus,
} from "./frames";

function frame(type: number, stream: number, payload: number[]): Uint8Array {
	const length = 5 + payload.length;
	return new Uint8Array([length, 0, 0, 0, type, stream, 0, 0, 0, ...payload]);
}

describe("FrameDecoder", () => {
	it("finds packets however they are split", () => {
		const decoder = new FrameDecoder();
		const bytes = new Uint8Array([...frame(2, 7, [1, 2, 3]), ...frame(4, 7, [2])]);
		const packets = [...bytes].flatMap((byte) => decoder.push(new Uint8Array([byte])));
		expect(packets.map((packet) => packet.type)).toEqual([2, 4]);
		expect(packets[0]?.stream).toBe(7);
		expect([...(packets[0]?.payload ?? [])]).toEqual([1, 2, 3]);
	});

	it("finds several packets in one chunk", () => {
		const decoder = new FrameDecoder();
		const packets = decoder.push(new Uint8Array([...frame(2, 1, [9]), ...frame(2, 2, [8]), ...frame(2, 3, [7])]));
		expect(packets.map((packet) => packet.stream)).toEqual([1, 2, 3]);
	});

	it("copes with packets bigger than its buffer", () => {
		const decoder = new FrameDecoder();
		const data = new Uint8Array(400 * 1024).fill(5);
		const packets = decoder.push(encodeData(3, data));
		expect(packets[0]?.payload.length).toBe(data.length);
	});

	it("gives payloads that stay valid after more arrives", () => {
		const decoder = new FrameDecoder();
		const [first] = decoder.push(frame(2, 1, [1, 1]));
		decoder.push(frame(2, 2, [9, 9]));
		expect([...(first?.payload ?? [])]).toEqual([1, 1]);
	});

	it("refuses an impossible length", () => {
		expect(() => new FrameDecoder().push(new Uint8Array([1, 0, 0, 0]))).toThrow();
		expect(() => new FrameDecoder().push(new Uint8Array([0, 0, 0, 0x40]))).toThrow();
	});
});

describe("messages", () => {
	it("lays out data like Wisp, with a length in front", () => {
		expect([...encodeData(7, new Uint8Array([1, 2]))]).toEqual([7, 0, 0, 0, 0x02, 7, 0, 0, 0, 1, 2]);
	});

	it("lays out continue and close like Wisp", () => {
		expect([...encodeContinue(1, 128)]).toEqual([9, 0, 0, 0, 0x03, 1, 0, 0, 0, 128, 0, 0, 0]);
		expect([...encodeClose(1, 2)]).toEqual([6, 0, 0, 0, 0x04, 1, 0, 0, 0, 2]);
	});

	it("puts credits, UDP and the name in START", () => {
		const [packet] = new FrameDecoder().push(encodeStart(128, true, "a.b"));
		expect(packet?.type).toBe(PacketType.START);
		expect([...(packet?.payload ?? [])]).toEqual([128, 0, 0, 0, 1, 97, 46, 98]);
	});

	it("says whether STOP also turns the service off", () => {
		expect(new FrameDecoder().push(encodeStop(true))[0]?.payload[0]).toBe(1);
		expect(new FrameDecoder().push(encodeStop(false))[0]?.payload[0]).toBe(0);
	});

	it("reads what the app sends", () => {
		expect(parseConnect(new Uint8Array([1, 0xbb, 0x01, 49, 46, 49]))).toEqual({
			streamType: 1,
			port: 443,
			host: "1.1",
		});
		expect(parseHello(new Uint8Array([1, 48, 46, 49]))).toEqual({ protocol: 1, appVersion: "0.1" });
		expect(parseStatus(new Uint8Array([2, 110, 111]))).toEqual({ code: 2, message: "no" });
		const meta = new Uint8Array([0x8b, 0x27, 0, 0, 1, 28, 0, 97]);
		expect(parseMeta(meta)).toEqual({ uid: 10123, kind: 1, queryType: 28, name: "a" });
		expect(parseMeta(new Uint8Array([0xff, 0xff, 0xff, 0xff, 0, 0, 0])).uid).toBe(-1);
	});
});
