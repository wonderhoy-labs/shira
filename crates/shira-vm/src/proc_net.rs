//! What the VM's own network looks like, from `/proc/net/route` and `/proc/net/if_inet6`.
//!
//! Both list the main routing table and addresses as text, which is simpler than asking netlink for them.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// The kernel's flag for a route that's up.
const RTF_UP: u16 = 0x1;
/// And for one through a gateway.
const RTF_GATEWAY: u16 = 0x2;

/// An address prefix, like 192.168.0.0/24.
pub type Prefix = (IpAddr, u8);

/// One IPv4 route from the main table.
#[derive(Debug, PartialEq, Eq)]
pub struct Route {
	pub interface: String,
	pub destination: Ipv4Addr,
	pub gateway: Ipv4Addr,
	pub flags: u16,
	pub metric: u32,
	pub prefix: u8,
}

/// One IPv6 address on an interface.
#[derive(Debug, PartialEq, Eq)]
pub struct Address {
	pub interface: String,
	pub address: Ipv6Addr,
	pub prefix: u8,
	pub scope: u8,
}

/// The networks the VM is directly on, and where its own default route goes.
#[derive(Debug, Default, PartialEq, Eq, Clone)]
pub struct Network {
	/// Through the phone: the Shira app listens here unless told otherwise.
	pub gateway: Option<Ipv4Addr>,
	/// Directly reachable, so these stay reachable: the phone, and the Terminal app on it, are there.
	pub local: Vec<Prefix>,
}

impl Network {
	/// Puts the two files together. `own` is the tunnel's device, which never counts.
	pub fn from(routes: &[Route], addresses: &[Address], own: &str) -> Network {
		let mut network = Network::default();
		let mut best_metric = u32::MAX;
		for route in routes {
			if route.flags & RTF_UP == 0 || route.interface == own || route.interface == "lo" {
				continue;
			}
			if route.prefix == 0 {
				if route.flags & RTF_GATEWAY != 0 && route.metric < best_metric {
					best_metric = route.metric;
					network.gateway = Some(route.gateway);
				}
			} else if route.flags & RTF_GATEWAY == 0 {
				push_unique(&mut network.local, (IpAddr::V4(route.destination), route.prefix));
			}
		}
		for address in addresses {
			// Only addresses of global scope. Link-local ones are always allowed, and host ones are loopback.
			if address.scope != 0 || address.interface == own || address.interface == "lo" || address.prefix == 0 {
				continue;
			}
			let network_part = mask6(address.address, address.prefix);
			push_unique(&mut network.local, (IpAddr::V6(network_part), address.prefix));
		}
		network
	}
}

fn push_unique(list: &mut Vec<Prefix>, prefix: Prefix) {
	if !list.contains(&prefix) {
		list.push(prefix);
	}
}

fn mask6(address: Ipv6Addr, prefix: u8) -> Ipv6Addr {
	let bits = u128::from(address);
	let mask = if prefix >= 128 { u128::MAX } else { !(u128::MAX >> prefix) };
	Ipv6Addr::from(bits & mask)
}

/// Reads `/proc/net/route`. Its addresses are hexadecimal numbers in the machine's own byte order.
pub fn parse_routes(text: &str) -> Vec<Route> {
	text.lines()
		.skip(1)
		.filter_map(|line| {
			let fields: Vec<&str> = line.split_whitespace().collect();
			if fields.len() < 8 {
				return None;
			}
			let hex = |field: &str| u32::from_str_radix(field, 16).ok();
			let address = |field: &str| hex(field).map(|value| Ipv4Addr::from(value.to_ne_bytes()));
			Some(Route {
				interface: fields[0].to_string(),
				destination: address(fields[1])?,
				gateway: address(fields[2])?,
				flags: u16::from_str_radix(fields[3], 16).ok()?,
				metric: fields[6].parse().ok()?,
				prefix: hex(fields[7])?.count_ones() as u8,
			})
		})
		.collect()
}

/// Reads `/proc/net/if_inet6`: the address in 32 hexadecimal digits, the interface's index, the prefix length, the
/// scope and flags, all in hexadecimal, then the interface's name.
pub fn parse_addresses(text: &str) -> Vec<Address> {
	text.lines()
		.filter_map(|line| {
			let fields: Vec<&str> = line.split_whitespace().collect();
			if fields.len() < 6 || fields[0].len() != 32 {
				return None;
			}
			Some(Address {
				address: Ipv6Addr::from(u128::from_str_radix(fields[0], 16).ok()?),
				prefix: u8::from_str_radix(fields[2], 16).ok()?,
				scope: u8::from_str_radix(fields[3], 16).ok()?,
				interface: fields[5].to_string(),
			})
		})
		.collect()
}

#[cfg(test)]
mod tests {
	use super::*;

	// What a Debian VM on the phone shows, give or take the numbers, written the way a little-endian machine does.
	const ROUTES: &str = "\
Iface	Destination	Gateway 	Flags	RefCnt	Use	Metric	Mask		MTU	Window	IRTT
enp0s1	00000000	0119A8C0	0003	0	0	1024	00000000	0	0	0
enp0s1	0019A8C0	00000000	0001	0	0	1024	00FFFFFF	0	0	0
enp0s1	0119A8C0	00000000	0005	0	0	1024	FFFFFFFF	0	0	0
shira0	00000000	00000000	0001	0	0	0	00000000	0	0	0
";

	const ADDRESSES: &str = "\
00000000000000000000000000000001 01 80 10 80       lo
fe800000000000005054fffe12345678 02 40 20 80   enp0s1
2a0012340056007800000000000000aa 02 40 00 00   enp0s1
";

	#[test]
	fn reads_routes() {
		let routes = parse_routes(ROUTES);
		assert_eq!(routes.len(), 4);
		assert_eq!(
			routes[0],
			Route {
				interface: "enp0s1".into(),
				destination: Ipv4Addr::UNSPECIFIED,
				gateway: Ipv4Addr::new(192, 168, 25, 1),
				flags: 3,
				metric: 1024,
				prefix: 0,
			}
		);
		assert_eq!(routes[1].destination, Ipv4Addr::new(192, 168, 25, 0));
		assert_eq!(routes[1].prefix, 24);
		assert_eq!(routes[2].prefix, 32);
	}

	#[test]
	fn reads_addresses() {
		let addresses = parse_addresses(ADDRESSES);
		assert_eq!(addresses.len(), 3);
		assert_eq!(addresses[2].address, "2a00:1234:56:78::aa".parse::<Ipv6Addr>().unwrap());
		assert_eq!(addresses[2].prefix, 64);
		assert_eq!(addresses[2].scope, 0);
		assert_eq!(addresses[1].scope, 0x20);
	}

	#[test]
	fn finds_the_gateway_and_the_local_networks() {
		let network = Network::from(&parse_routes(ROUTES), &parse_addresses(ADDRESSES), "shira0");
		assert_eq!(network.gateway, Some(Ipv4Addr::new(192, 168, 25, 1)));
		assert_eq!(
			network.local,
			[
				("192.168.25.0".parse().unwrap(), 24),
				("192.168.25.1".parse().unwrap(), 32),
				("2a00:1234:56:78::".parse().unwrap(), 64),
			]
		);
	}

	#[test]
	fn ignores_its_own_device_and_the_lowest_metric_wins() {
		let routes = "\
Iface	Destination	Gateway 	Flags	RefCnt	Use	Metric	Mask		MTU	Window	IRTT
shira0	00000000	0100DB0A	0003	0	0	0	00000000	0	0	0
eth1	00000000	0101A8C0	0003	0	0	200	00000000	0	0	0
eth0	00000000	0100A8C0	0003	0	0	100	00000000	0	0	0
eth0	0000A8C0	00000000	0000	0	0	100	00FFFFFF	0	0	0
";
		let network = Network::from(&parse_routes(routes), &[], "shira0");
		assert_eq!(network.gateway, Some(Ipv4Addr::new(192, 168, 0, 1)));
		// That last one isn't up.
		assert!(network.local.is_empty());
	}
}
