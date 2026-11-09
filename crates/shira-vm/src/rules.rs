//! The routing policy that sends the VM's traffic into the tunnel, and refuses it when there's no tunnel.
//!
//! Linux looks at policy rules in order of priority before any routing table. These sit in front of the main table:
//!
//! | Priority | Rule |
//! | --- | --- |
//! | 5300 | DNS to the local networks is unreachable, because the phone would answer it from its own network |
//! | 5301 | The local networks, link-local and multicast use the main table, but never its default route |
//! | 5302 | Everything else looks in table 5302, which has a default route into the tunnel while it's up |
//! | 5303 | Whatever's left is unreachable |
//!
//! So the main table's default route, which Android forwards straight out of the phone, is never used. The rules at
//! 5302 and 5303 never change, and they stay when `shira-vm` stops, so the VM can't reach anything until it's back.

use crate::netlink::{self, Family};
use crate::proc_net::{Network, Prefix};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddrV4};

/// The tunnel's device.
pub const DEVICE: &str = "shira0";

/// The routing table with the tunnel's default route, while there is one.
pub const TABLE: u32 = 5302;

const MAIN_TABLE: u32 = 254;

pub const DNS_PRIORITY: u32 = 5300;
pub const LOCAL_PRIORITY: u32 = 5301;
pub const TUNNEL_PRIORITY: u32 = 5302;
pub const BLOCK_PRIORITY: u32 = 5303;

/// Every priority these rules use, for taking them all away again.
pub const PRIORITIES: [u32; 4] = [DNS_PRIORITY, LOCAL_PRIORITY, TUNNEL_PRIORITY, BLOCK_PRIORITY];

const TCP: u8 = 6;
const UDP: u8 = 17;

/// DNS, and DNS over TLS. The phone answers DNS on its address on the VM's network, through its own network.
const DNS_PORTS: [(u8, u16); 3] = [(UDP, 53), (TCP, 53), (TCP, 853)];

/// What a rule does with a packet it matches.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action {
	Lookup(u32),
	Unreachable,
}

/// One policy rule.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Rule {
	pub family: Family,
	pub priority: u32,
	pub to: Option<Prefix>,
	pub protocol: Option<u8>,
	pub port: Option<u16>,
	pub action: Action,
	/// Ignores default routes in the table it looks in, so that only the routes for local networks count.
	pub suppress_default: bool,
}

impl Rule {
	fn new(family: Family, priority: u32, action: Action) -> Rule {
		Rule { family, priority, to: None, protocol: None, port: None, action, suppress_default: false }
	}

	/// The netlink message that adds it. The kernel says it exists already if an identical one does.
	pub fn add_message(&self, sequence: u32) -> Vec<u8> {
		let (table, action) = match self.action {
			Action::Lookup(table) => (table, netlink::FR_ACT_TO_TBL),
			Action::Unreachable => (0, netlink::FR_ACT_UNREACHABLE),
		};
		let mut message = netlink::Message::new(
			netlink::RTM_NEWRULE,
			netlink::NLM_F_REQUEST | netlink::NLM_F_ACK | netlink::NLM_F_CREATE | netlink::NLM_F_EXCL,
			sequence,
		);
		let destination_bits = self.to.map_or(0, |(_, bits)| bits);
		// The table goes in the header too when it fits, the way `ip rule` does it.
		let short_table = if table <= 255 { table as u8 } else { 0 };
		message.push(&[self.family.number(), destination_bits, 0, 0, short_table, 0, 0, action]);
		message.push(&0u32.to_ne_bytes());
		message.attribute(netlink::FRA_PRIORITY, &self.priority.to_ne_bytes());
		if table != 0 {
			message.attribute(netlink::FRA_TABLE, &table.to_ne_bytes());
		}
		if let Some((address, _)) = self.to {
			match address {
				IpAddr::V4(address) => message.attribute(netlink::FRA_DST, &address.octets()),
				IpAddr::V6(address) => message.attribute(netlink::FRA_DST, &address.octets()),
			}
		}
		if self.suppress_default {
			message.attribute(netlink::FRA_SUPPRESS_PREFIXLEN, &0u32.to_ne_bytes());
		}
		if let Some(protocol) = self.protocol {
			message.attribute(netlink::FRA_IP_PROTO, &[protocol]);
		}
		if let Some(port) = self.port {
			let mut range = port.to_ne_bytes().to_vec();
			range.extend_from_slice(&port.to_ne_bytes());
			message.attribute(netlink::FRA_DPORT_RANGE, &range);
		}
		message.finish()
	}
}

/// The rules that never change: into the tunnel when it's there, and nowhere otherwise. The tunnel is IPv4 only.
pub fn fixed_rules() -> Vec<Rule> {
	vec![
		Rule::new(Family::V4, TUNNEL_PRIORITY, Action::Lookup(TABLE)),
		Rule::new(Family::V4, BLOCK_PRIORITY, Action::Unreachable),
		Rule::new(Family::V6, BLOCK_PRIORITY, Action::Unreachable),
	]
}

/// The rules for what stays reachable directly: the networks the VM is on, except their DNS, link-local and multicast
/// addresses, and `server` when it's been set somewhere else. The phone, and the Terminal app that shows this VM,
/// are on the VM's network.
pub fn local_rules(network: &Network, server: Option<SocketAddrV4>) -> Vec<Rule> {
	let link_local = (IpAddr::V6(Ipv6Addr::new(0xfe80, 0, 0, 0, 0, 0, 0, 0)), 10);
	let mut unicast = network.local.clone();
	unicast.push(link_local);
	let multicast =
		[(IpAddr::V4(Ipv4Addr::new(224, 0, 0, 0)), 4), (IpAddr::V6(Ipv6Addr::new(0xff00, 0, 0, 0, 0, 0, 0, 0)), 8)];

	let mut rules = Vec::new();
	for prefix in &unicast {
		for (protocol, port) in DNS_PORTS {
			rules.push(Rule {
				to: Some(*prefix),
				protocol: Some(protocol),
				port: Some(port),
				..Rule::new(family_of(prefix), DNS_PRIORITY, Action::Unreachable)
			});
		}
	}
	for prefix in unicast.iter().chain(&multicast) {
		rules.push(Rule {
			to: Some(*prefix),
			suppress_default: true,
			..Rule::new(family_of(prefix), LOCAL_PRIORITY, Action::Lookup(MAIN_TABLE))
		});
	}
	if let Some(server) = server {
		// Somewhere else, so through the main table's default route, but only to Shira's port.
		rules.push(Rule {
			to: Some((IpAddr::V4(*server.ip()), 32)),
			protocol: Some(TCP),
			port: Some(server.port()),
			..Rule::new(Family::V4, LOCAL_PRIORITY, Action::Lookup(MAIN_TABLE))
		});
	}
	rules
}

fn family_of(prefix: &Prefix) -> Family {
	match prefix.0 {
		IpAddr::V4(_) => Family::V4,
		IpAddr::V6(_) => Family::V6,
	}
}

#[cfg(test)]
mod tests {
	use super::*;

	fn network() -> Network {
		Network {
			gateway: Some(Ipv4Addr::new(192, 168, 25, 1)),
			local: vec![(IpAddr::V4(Ipv4Addr::new(192, 168, 25, 0)), 24)],
		}
	}

	#[test]
	fn only_the_tunnel_and_nothing_else_are_fixed() {
		let rules = fixed_rules();
		assert_eq!(rules.len(), 3);
		assert!(rules.iter().all(|rule| rule.to.is_none() && rule.protocol.is_none()));
		assert_eq!(rules[0].action, Action::Lookup(TABLE));
		assert!(rules[1..].iter().all(|rule| rule.action == Action::Unreachable && rule.priority == BLOCK_PRIORITY));
	}

	#[test]
	fn the_local_network_is_reachable_but_not_its_dns() {
		let rules = local_rules(&network(), None);
		let lan = Some((IpAddr::V4(Ipv4Addr::new(192, 168, 25, 0)), 24));
		let dns: Vec<_> = rules.iter().filter(|rule| rule.to == lan && rule.priority == DNS_PRIORITY).collect();
		assert_eq!(dns.len(), 3);
		assert!(dns.iter().all(|rule| rule.action == Action::Unreachable));
		assert!(dns.iter().any(|rule| rule.protocol == Some(UDP) && rule.port == Some(53)));
		let direct: Vec<_> = rules.iter().filter(|rule| rule.to == lan && rule.priority == LOCAL_PRIORITY).collect();
		assert_eq!(direct.len(), 1);
		assert_eq!(direct[0].action, Action::Lookup(MAIN_TABLE));
		assert!(direct[0].suppress_default);
	}

	#[test]
	fn every_local_rule_comes_before_the_tunnel() {
		let rules = local_rules(&network(), Some("192.168.65.254:47830".parse().unwrap()));
		assert!(rules.iter().all(|rule| rule.priority < TUNNEL_PRIORITY));
		// The default route is only ever allowed for the server, and only to its port.
		let open: Vec<_> =
			rules.iter().filter(|rule| !rule.suppress_default && rule.action != Action::Unreachable).collect();
		assert_eq!(open.len(), 1);
		assert_eq!(open[0].to, Some((IpAddr::V4(Ipv4Addr::new(192, 168, 65, 254)), 32)));
		assert_eq!((open[0].protocol, open[0].port), (Some(TCP), Some(47830)));
	}

	#[test]
	fn a_lookup_rule_is_laid_out_like_ip_rule() {
		let rule = local_rules(&network(), None).into_iter().find(|rule| rule.priority == LOCAL_PRIORITY).unwrap();
		let mut expected = Vec::new();
		expected.extend_from_slice(&60u32.to_ne_bytes()); // length
		expected.extend_from_slice(&netlink::RTM_NEWRULE.to_ne_bytes());
		expected.extend_from_slice(&0x605u16.to_ne_bytes()); // request, ack, create, exclusive
		expected.extend_from_slice(&7u32.to_ne_bytes()); // sequence
		expected.extend_from_slice(&0u32.to_ne_bytes()); // port id
		expected.extend_from_slice(&[2, 24, 0, 0, 254, 0, 0, 1, 0, 0, 0, 0]); // AF_INET, /24, main, to table
		expected.extend_from_slice(&[8, 0, 6, 0]); // FRA_PRIORITY
		expected.extend_from_slice(&5301u32.to_ne_bytes());
		expected.extend_from_slice(&[8, 0, 15, 0]); // FRA_TABLE
		expected.extend_from_slice(&254u32.to_ne_bytes());
		expected.extend_from_slice(&[8, 0, 1, 0, 192, 168, 25, 0]); // FRA_DST
		expected.extend_from_slice(&[8, 0, 14, 0, 0, 0, 0, 0]); // FRA_SUPPRESS_PREFIXLEN 0
		assert_eq!(rule.add_message(7), expected);
	}

	#[test]
	fn a_port_rule_carries_its_protocol_and_port() {
		let rule = Rule {
			to: Some((IpAddr::V4(Ipv4Addr::new(192, 168, 25, 0)), 24)),
			protocol: Some(UDP),
			port: Some(53),
			..Rule::new(Family::V4, DNS_PRIORITY, Action::Unreachable)
		};
		let bytes = rule.add_message(1);
		// The header: AF_INET, /24, no table, unreachable.
		assert_eq!(bytes[16..24], [2, 24, 0, 0, 0, 0, 0, 7]);
		let tail = &bytes[bytes.len() - 16..];
		assert_eq!(tail[..5], [5, 0, 22, 0, 17]); // FRA_IP_PROTO, padded to 8
		assert_eq!(tail[8..12], [8, 0, 24, 0]); // FRA_DPORT_RANGE
		assert_eq!(tail[12..], [53u16.to_ne_bytes(), 53u16.to_ne_bytes()].concat()[..]);
	}
}
