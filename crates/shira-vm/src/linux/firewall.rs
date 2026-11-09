//! Puts the rules from `rules` into the kernel, and takes them away again.

use super::socket::Netlink;
use crate::netlink::{self, Family};
use crate::rules::{self, Rule};
use std::io;

const EEXIST: i32 = 17;

pub struct Firewall {
	netlink: Netlink,
	local: Vec<Rule>,
}

impl Firewall {
	pub fn open() -> io::Result<Firewall> {
		Ok(Firewall { netlink: Netlink::open()?, local: Vec::new() })
	}

	/// Adds the rules that block everything but the tunnel, unless they're there already.
	pub fn ensure_fixed(&mut self) -> io::Result<()> {
		for rule in rules::fixed_rules() {
			self.add(&rule)?;
		}
		Ok(())
	}

	/// Replaces the rules for the local networks, if they've changed. The fixed ones keep everything else blocked
	/// while this happens.
	pub fn set_local(&mut self, wanted: Vec<Rule>) -> io::Result<()> {
		if wanted == self.local {
			return Ok(());
		}
		self.local.clear();
		// The way to the local networks goes before the DNS blocks in front of it, and comes back after them, so that
		// DNS to the phone is never let through.
		for family in [Family::V4, Family::V6] {
			self.netlink.delete_rules_at(family, rules::LOCAL_PRIORITY)?;
		}
		for family in [Family::V4, Family::V6] {
			self.netlink.delete_rules_at(family, rules::DNS_PRIORITY)?;
		}
		let (dns, rest): (Vec<&Rule>, Vec<&Rule>) =
			wanted.iter().partition(|rule| rule.priority == rules::DNS_PRIORITY);
		for rule in dns.into_iter().chain(rest) {
			self.add(rule)?;
		}
		self.local = wanted;
		Ok(())
	}

	/// Points the tunnel's table at the device numbered `interface`.
	pub fn route_into(&mut self, interface: u32) -> io::Result<()> {
		self.netlink.call(|sequence| netlink::default_route(interface, rules::TABLE, sequence))
	}

	/// Takes every rule away, so that the VM uses its own network again.
	pub fn remove_all(&mut self) -> io::Result<()> {
		for family in [Family::V4, Family::V6] {
			for priority in rules::PRIORITIES {
				self.netlink.delete_rules_at(family, priority)?;
			}
		}
		self.local.clear();
		Ok(())
	}

	/// Whether everything but the tunnel is blocked, for both IPv4 and IPv6.
	pub fn blocking(&mut self) -> io::Result<bool> {
		for family in [Family::V4, Family::V6] {
			if !self.netlink.rule_priorities(family)?.contains(&rules::BLOCK_PRIORITY) {
				return Ok(false);
			}
		}
		Ok(true)
	}

	fn add(&mut self, rule: &Rule) -> io::Result<()> {
		match self.netlink.call(|sequence| rule.add_message(sequence)) {
			Err(error) if error.raw_os_error() == Some(EEXIST) => Ok(()),
			other => other,
		}
	}
}
