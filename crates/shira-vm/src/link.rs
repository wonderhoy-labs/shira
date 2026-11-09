//! The link to the Shira app, over TCP to the phone's address on the VM's network.
//!
//! Each frame is a length in 4 little-endian bytes, counting what follows, then a type in 1 byte, then the payload.
//! The app sends [`HELLO`] first, and after that both sides send [`PACKET`]s, each holding one IPv4 packet. It's the
//! same as `VmLink.kt` in `android/tunnel`, and the tests here check the same bytes as its tests. See
//! docs/protocol.md.

use std::io;
use std::net::Ipv4Addr;

/// The TCP port the app listens on.
pub const PORT: u16 = 47830;

/// Has to match the app's. They refuse each other if it doesn't.
pub const PROTOCOL_VERSION: u8 = 1;

/// App to VM, first thing on a new connection.
pub const HELLO: u8 = 0x01;

/// Both ways: one IPv4 packet.
pub const PACKET: u8 = 0x02;

pub const LENGTH_BYTES: usize = 4;
pub const TYPE_BYTES: usize = 1;
pub const FRAME_OVERHEAD: usize = LENGTH_BYTES + TYPE_BYTES;

/// The most a frame can count after its length: a type and the biggest IPv4 packet.
pub const MAX_FRAME_BYTES: usize = TYPE_BYTES + 65_535;

/// How the app wants the VM's device set up.
#[derive(Debug, PartialEq, Eq)]
pub struct Hello {
	pub version: u8,
	/// The VM's address inside the tunnel.
	pub address: Ipv4Addr,
	/// Where to send DNS questions. The app answers them itself.
	pub resolver: Ipv4Addr,
	pub mtu: u16,
	pub app_version: String,
}

impl Hello {
	/// Reads a HELLO's payload: the version in 1 byte, two addresses in 4 bytes each, the MTU in 2 little-endian
	/// bytes, then the app's version as text.
	pub fn parse(payload: &[u8]) -> io::Result<Hello> {
		let (&version, rest) = payload.split_first().ok_or_else(|| invalid("an empty HELLO"))?;
		if version != PROTOCOL_VERSION {
			// Nothing after the version can be trusted to mean the same thing.
			return Ok(Hello {
				version,
				address: Ipv4Addr::UNSPECIFIED,
				resolver: Ipv4Addr::UNSPECIFIED,
				mtu: 0,
				app_version: String::new(),
			});
		}
		if rest.len() < 10 {
			return Err(invalid("a HELLO that's too short"));
		}
		Ok(Hello {
			version,
			address: Ipv4Addr::new(rest[0], rest[1], rest[2], rest[3]),
			resolver: Ipv4Addr::new(rest[4], rest[5], rest[6], rest[7]),
			mtu: u16::from_le_bytes([rest[8], rest[9]]),
			app_version: String::from_utf8_lossy(&rest[10..]).into_owned(),
		})
	}
}

/// The length and type in front of a PACKET of `length` bytes.
pub fn packet_header(length: usize) -> [u8; FRAME_OVERHEAD] {
	let counted = (TYPE_BYTES + length) as u32;
	let [a, b, c, d] = counted.to_le_bytes();
	[a, b, c, d, PACKET]
}

/// Collects what comes off the socket, which can be split up in any way, and finds whole frames in it.
pub struct FrameBuffer {
	buffer: Vec<u8>,
	start: usize,
	end: usize,
}

impl Default for FrameBuffer {
	fn default() -> Self {
		FrameBuffer { buffer: vec![0; 4 * (LENGTH_BYTES + MAX_FRAME_BYTES)], start: 0, end: 0 }
	}
}

impl FrameBuffer {
	/// Room to read into. Pass what was read to [`FrameBuffer::filled`]. There's always room for a whole frame.
	pub fn space(&mut self) -> &mut [u8] {
		if self.start > 0 && self.buffer.len() - self.end < LENGTH_BYTES + MAX_FRAME_BYTES {
			self.buffer.copy_within(self.start..self.end, 0);
			self.end -= self.start;
			self.start = 0;
		}
		&mut self.buffer[self.end..]
	}

	pub fn filled(&mut self, count: usize) {
		self.end += count;
	}

	/// The next whole frame's type and payload, if there is one yet.
	pub fn next_frame(&mut self) -> io::Result<Option<(u8, &[u8])>> {
		let waiting = &self.buffer[self.start..self.end];
		if waiting.len() < LENGTH_BYTES {
			return Ok(None);
		}
		let length = u32::from_le_bytes([waiting[0], waiting[1], waiting[2], waiting[3]]) as usize;
		if !(TYPE_BYTES..=MAX_FRAME_BYTES).contains(&length) {
			return Err(invalid(&format!("a frame of {length} bytes")));
		}
		if waiting.len() < LENGTH_BYTES + length {
			return Ok(None);
		}
		let frame = self.start + LENGTH_BYTES;
		self.start = frame + length;
		if self.start == self.end {
			// Start again at the front. The bytes stay where they are until the next read.
			self.start = 0;
			self.end = 0;
		}
		Ok(Some((self.buffer[frame], &self.buffer[frame + TYPE_BYTES..frame + length])))
	}
}

fn invalid(what: &str) -> io::Error {
	io::Error::new(io::ErrorKind::InvalidData, format!("Shira sent {what}"))
}

#[cfg(test)]
mod tests {
	use super::*;

	/// The bytes `VmLinkTest.helloIsLaidOutAsDocumented` checks in Kotlin.
	const HELLO_FRAME: [u8; 21] = [
		17, 0, 0, 0,    // length of what follows
		0x01, // HELLO
		1,    // protocol version
		10, 219, 0, 3, // the VM's address
		10, 219, 0, 2, // its resolver
		0x28, 0x23, // MTU 9000
		b'0', b'.', b'3', b'.', b'0', // the app's version
	];

	fn feed(frames: &mut FrameBuffer, bytes: &[u8]) {
		frames.space()[..bytes.len()].copy_from_slice(bytes);
		frames.filled(bytes.len());
	}

	#[test]
	fn reads_the_apps_hello() {
		let mut frames = FrameBuffer::default();
		feed(&mut frames, &HELLO_FRAME);
		let (kind, payload) = frames.next_frame().unwrap().unwrap();
		assert_eq!(kind, HELLO);
		assert_eq!(
			Hello::parse(payload).unwrap(),
			Hello {
				version: 1,
				address: Ipv4Addr::new(10, 219, 0, 3),
				resolver: Ipv4Addr::new(10, 219, 0, 2),
				mtu: 9000,
				app_version: "0.3.0".into(),
			}
		);
	}

	#[test]
	fn a_newer_app_only_has_to_get_the_version_across() {
		assert_eq!(Hello::parse(&[2, 9, 9]).unwrap().version, 2);
		assert!(Hello::parse(&[1, 9, 9]).is_err());
		assert!(Hello::parse(&[]).is_err());
	}

	#[test]
	fn packet_headers_match_the_app() {
		// VmLinkTest.packetCarriesItsBytes
		assert_eq!(packet_header(5), [6, 0, 0, 0, 0x02]);
	}

	#[test]
	fn finds_frames_however_they_are_split() {
		let mut all = Vec::new();
		all.extend_from_slice(&packet_header(3));
		all.extend_from_slice(&[1, 2, 3]);
		all.extend_from_slice(&packet_header(65_535));
		all.extend(std::iter::repeat_n(7, 65_535));
		for cut in [1, 4, 5, 7, 8, 9, 100, all.len() - 1] {
			let mut frames = FrameBuffer::default();
			let mut seen = Vec::new();
			for part in [&all[..cut], &all[cut..]] {
				feed(&mut frames, part);
				while let Some((kind, payload)) = frames.next_frame().unwrap() {
					seen.push((kind, payload.len(), payload.first().copied()));
				}
			}
			assert_eq!(seen, [(PACKET, 3, Some(1)), (PACKET, 65_535, Some(7))], "cut at {cut}");
		}
	}

	#[test]
	fn keeps_finding_frames_after_many_reads() {
		let mut stream = Vec::new();
		for _ in 0..2000 {
			stream.extend_from_slice(&packet_header(1000));
			stream.extend(std::iter::repeat_n(1, 1000));
		}
		// Each read stops part way into a frame, so the buffer never empties by itself and has to move along.
		let mut frames = FrameBuffer::default();
		let mut count = 0;
		for chunk in stream.chunks(7001) {
			feed(&mut frames, chunk);
			while let Some((kind, payload)) = frames.next_frame().unwrap() {
				assert_eq!((kind, payload.len()), (PACKET, 1000));
				count += 1;
			}
		}
		assert_eq!(count, 2000);
	}

	#[test]
	fn refuses_frames_that_cant_be_right() {
		let mut frames = FrameBuffer::default();
		feed(&mut frames, &[0, 0, 0, 0]);
		assert!(frames.next_frame().is_err());
		let mut frames = FrameBuffer::default();
		feed(&mut frames, &((MAX_FRAME_BYTES + 1) as u32).to_le_bytes());
		assert!(frames.next_frame().is_err());
	}
}
