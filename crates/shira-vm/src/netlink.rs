//! Netlink messages for routing rules and routes, built and read as bytes.
//!
//! This part has nothing Linux-only in it, so its tests run anywhere. Sending them is in `linux::socket`. Numbers
//! are in the machine's own byte order, the way netlink wants them.

pub const RTM_NEWROUTE: u16 = 24;
pub const RTM_NEWRULE: u16 = 32;
pub const RTM_DELRULE: u16 = 33;
pub const RTM_GETRULE: u16 = 34;
pub const NLMSG_ERROR: u16 = 2;
pub const NLMSG_DONE: u16 = 3;

pub const NLM_F_REQUEST: u16 = 0x1;
#[cfg(test)]
const NLM_F_MULTI: u16 = 0x2;
pub const NLM_F_ACK: u16 = 0x4;
pub const NLM_F_REPLACE: u16 = 0x100;
pub const NLM_F_EXCL: u16 = 0x200;
pub const NLM_F_CREATE: u16 = 0x400;
pub const NLM_F_DUMP: u16 = 0x300;

pub const FRA_DST: u16 = 1;
pub const FRA_PRIORITY: u16 = 6;
pub const FRA_SUPPRESS_PREFIXLEN: u16 = 14;
pub const FRA_TABLE: u16 = 15;
pub const FRA_IP_PROTO: u16 = 22;
pub const FRA_DPORT_RANGE: u16 = 24;
pub const FR_ACT_TO_TBL: u8 = 1;
pub const FR_ACT_UNREACHABLE: u8 = 7;

const RTA_OIF: u16 = 4;
const RTA_TABLE: u16 = 15;
const RTPROT_STATIC: u8 = 4;
const RT_SCOPE_LINK: u8 = 253;
const RTN_UNICAST: u8 = 1;

pub const HEADER_BYTES: usize = 16;
/// `struct fib_rule_hdr` and `struct rtmsg` are both this long.
const BODY_HEADER_BYTES: usize = 12;

/// IPv4 or IPv6, with Linux's numbers for them.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Family {
	V4,
	V6,
}

impl Family {
	pub fn number(self) -> u8 {
		match self {
			Family::V4 => 2,
			Family::V6 => 10,
		}
	}
}

/// A netlink message being put together: the header, then whatever's pushed, then attributes.
pub struct Message {
	bytes: Vec<u8>,
}

impl Message {
	pub fn new(kind: u16, flags: u16, sequence: u32) -> Message {
		let mut bytes = Vec::with_capacity(64);
		bytes.extend_from_slice(&0u32.to_ne_bytes());
		bytes.extend_from_slice(&kind.to_ne_bytes());
		bytes.extend_from_slice(&flags.to_ne_bytes());
		bytes.extend_from_slice(&sequence.to_ne_bytes());
		bytes.extend_from_slice(&0u32.to_ne_bytes());
		Message { bytes }
	}

	pub fn push(&mut self, bytes: &[u8]) {
		self.bytes.extend_from_slice(bytes);
	}

	/// An attribute: its length and type in 2 bytes each, then the value, padded to 4 bytes.
	pub fn attribute(&mut self, kind: u16, value: &[u8]) {
		let length = (4 + value.len()) as u16;
		self.bytes.extend_from_slice(&length.to_ne_bytes());
		self.bytes.extend_from_slice(&kind.to_ne_bytes());
		self.bytes.extend_from_slice(value);
		while !self.bytes.len().is_multiple_of(4) {
			self.bytes.push(0);
		}
	}

	pub fn finish(mut self) -> Vec<u8> {
		let length = self.bytes.len() as u32;
		self.bytes[..4].copy_from_slice(&length.to_ne_bytes());
		self.bytes
	}
}

/// Deletes one rule of `family` at `priority`, whatever it says. Send it until the kernel says there are none left.
pub fn delete_rule_at(family: Family, priority: u32, sequence: u32) -> Vec<u8> {
	let mut message = Message::new(RTM_DELRULE, NLM_F_REQUEST | NLM_F_ACK, sequence);
	message.push(&[family.number(), 0, 0, 0, 0, 0, 0, 0]);
	message.push(&0u32.to_ne_bytes());
	message.attribute(FRA_PRIORITY, &priority.to_ne_bytes());
	message.finish()
}

/// Asks for every rule of `family`.
pub fn dump_rules(family: Family, sequence: u32) -> Vec<u8> {
	let mut message = Message::new(RTM_GETRULE, NLM_F_REQUEST | NLM_F_DUMP, sequence);
	message.push(&[family.number(), 0, 0, 0, 0, 0, 0, 0]);
	message.push(&0u32.to_ne_bytes());
	message.finish()
}

/// An IPv4 default route through the device numbered `interface`, in `table`, replacing any that's there.
pub fn default_route(interface: u32, table: u32, sequence: u32) -> Vec<u8> {
	let mut message = Message::new(RTM_NEWROUTE, NLM_F_REQUEST | NLM_F_ACK | NLM_F_CREATE | NLM_F_REPLACE, sequence);
	// The table's too big for the header's byte, so it's 0 there and in RTA_TABLE instead.
	message.push(&[Family::V4.number(), 0, 0, 0, 0, RTPROT_STATIC, RT_SCOPE_LINK, RTN_UNICAST]);
	message.push(&0u32.to_ne_bytes());
	message.attribute(RTA_TABLE, &table.to_ne_bytes());
	message.attribute(RTA_OIF, &interface.to_ne_bytes());
	message.finish()
}

/// One message from the kernel.
#[derive(Debug, PartialEq, Eq)]
pub struct Reply<'a> {
	pub kind: u16,
	pub sequence: u32,
	pub body: &'a [u8],
}

impl Reply<'_> {
	/// For an NLMSG_ERROR: 0 for an acknowledgement, or a negative errno.
	pub fn error(&self) -> Option<i32> {
		if self.kind != NLMSG_ERROR || self.body.len() < 4 {
			return None;
		}
		Some(i32::from_ne_bytes([self.body[0], self.body[1], self.body[2], self.body[3]]))
	}

	/// For a rule from a dump: its family and priority. A rule with no priority attribute has priority 0.
	pub fn rule(&self) -> Option<(u8, u32)> {
		if self.kind != RTM_NEWRULE || self.body.len() < BODY_HEADER_BYTES {
			return None;
		}
		let family = self.body[0];
		let mut priority = 0;
		let mut at = BODY_HEADER_BYTES;
		while at + 4 <= self.body.len() {
			let length = u16::from_ne_bytes([self.body[at], self.body[at + 1]]) as usize;
			let kind = u16::from_ne_bytes([self.body[at + 2], self.body[at + 3]]);
			if length < 4 || at + length > self.body.len() {
				break;
			}
			if kind == FRA_PRIORITY && length >= 8 {
				let value = &self.body[at + 4..at + 8];
				priority = u32::from_ne_bytes([value[0], value[1], value[2], value[3]]);
			}
			at += length.div_ceil(4) * 4;
		}
		Some((family, priority))
	}
}

/// Splits what one read from the socket held into its messages.
pub fn replies(bytes: &[u8]) -> Vec<Reply<'_>> {
	let mut found = Vec::new();
	let mut at = 0;
	while at + HEADER_BYTES <= bytes.len() {
		let length = u32::from_ne_bytes([bytes[at], bytes[at + 1], bytes[at + 2], bytes[at + 3]]) as usize;
		if length < HEADER_BYTES || at + length > bytes.len() {
			break;
		}
		found.push(Reply {
			kind: u16::from_ne_bytes([bytes[at + 4], bytes[at + 5]]),
			sequence: u32::from_ne_bytes([bytes[at + 8], bytes[at + 9], bytes[at + 10], bytes[at + 11]]),
			body: &bytes[at + HEADER_BYTES..at + length],
		});
		at += length.div_ceil(4) * 4;
	}
	found
}

#[cfg(test)]
mod tests {
	use super::*;

	fn header(length: u32, kind: u16, flags: u16, sequence: u32) -> Vec<u8> {
		[&length.to_ne_bytes()[..], &kind.to_ne_bytes(), &flags.to_ne_bytes(), &sequence.to_ne_bytes(), &[0; 4]]
			.concat()
	}

	#[test]
	fn deleting_by_priority_is_laid_out_like_ip_rule_del() {
		let mut expected = header(36, RTM_DELRULE, NLM_F_REQUEST | NLM_F_ACK, 3);
		expected.extend_from_slice(&[10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
		expected.extend_from_slice(&[8, 0, 6, 0]);
		expected.extend_from_slice(&5303u32.to_ne_bytes());
		assert_eq!(delete_rule_at(Family::V6, 5303, 3), expected);
	}

	#[test]
	fn a_default_route_names_its_table_and_device() {
		let mut expected = header(44, RTM_NEWROUTE, 0x505, 9);
		expected.extend_from_slice(&[2, 0, 0, 0, 0, 4, 253, 1, 0, 0, 0, 0]);
		expected.extend_from_slice(&[8, 0, 15, 0]);
		expected.extend_from_slice(&5302u32.to_ne_bytes());
		expected.extend_from_slice(&[8, 0, 4, 0]);
		expected.extend_from_slice(&7u32.to_ne_bytes());
		assert_eq!(default_route(7, 5302, 9), expected);
	}

	#[test]
	fn attributes_are_padded() {
		let mut message = Message::new(RTM_NEWRULE, 0, 0);
		message.attribute(FRA_IP_PROTO, &[17]);
		let bytes = message.finish();
		assert_eq!(bytes.len(), HEADER_BYTES + 8);
		assert_eq!(bytes[HEADER_BYTES..], [5, 0, 22, 0, 17, 0, 0, 0]);
	}

	#[test]
	fn reads_acknowledgements_and_errors() {
		let mut bytes = header(36, NLMSG_ERROR, 0, 5);
		bytes.extend_from_slice(&0i32.to_ne_bytes());
		bytes.extend_from_slice(&header(16, RTM_NEWRULE, 0, 5));
		let mut failure = header(36, NLMSG_ERROR, 0, 6);
		failure.extend_from_slice(&(-17i32).to_ne_bytes());
		failure.extend_from_slice(&header(16, RTM_NEWRULE, 0, 6));
		bytes.extend_from_slice(&failure);

		let found = replies(&bytes);
		assert_eq!(found.len(), 2);
		assert_eq!((found[0].sequence, found[0].error()), (5, Some(0)));
		assert_eq!((found[1].sequence, found[1].error()), (6, Some(-17)));
	}

	#[test]
	fn reads_a_rules_priority_from_a_dump() {
		let mut body = vec![2, 0, 0, 0, 254, 0, 0, 1, 0, 0, 0, 0];
		body.extend_from_slice(&[8, 0, 15, 0]);
		body.extend_from_slice(&254u32.to_ne_bytes());
		body.extend_from_slice(&[8, 0, 6, 0]);
		body.extend_from_slice(&5302u32.to_ne_bytes());
		let mut bytes = header((HEADER_BYTES + body.len()) as u32, RTM_NEWRULE, NLM_F_MULTI, 1);
		bytes.extend_from_slice(&body);
		bytes.extend_from_slice(&header(20, NLMSG_DONE, NLM_F_MULTI, 1));
		bytes.extend_from_slice(&0u32.to_ne_bytes());

		let found = replies(&bytes);
		assert_eq!(found[0].rule(), Some((2, 5302)));
		assert_eq!(found[1].kind, NLMSG_DONE);
	}
}
