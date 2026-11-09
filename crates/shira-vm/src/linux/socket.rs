//! A netlink socket to the kernel's routing, which sends one request at a time and waits for its answer.

use crate::netlink::{self, Family};
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};

const NETLINK_ROUTE: i32 = 0;
const ENOENT: i32 = 2;

pub struct Netlink {
	fd: OwnedFd,
	sequence: u32,
	buffer: Vec<u8>,
}

impl Netlink {
	pub fn open() -> io::Result<Netlink> {
		// SAFETY: socket has no memory arguments, and its result is checked before use.
		let fd = unsafe { libc::socket(libc::AF_NETLINK, libc::SOCK_RAW | libc::SOCK_CLOEXEC, NETLINK_ROUTE) };
		if fd < 0 {
			return Err(io::Error::last_os_error());
		}
		// SAFETY: fd is a new descriptor that nothing else owns.
		let fd = unsafe { OwnedFd::from_raw_fd(fd) };
		// SAFETY: all zeros is a valid sockaddr_nl, which then asks for a port ID chosen by the kernel.
		let mut address: libc::sockaddr_nl = unsafe { std::mem::zeroed() };
		address.nl_family = libc::AF_NETLINK as u16;
		// SAFETY: address is a sockaddr_nl of the size given.
		let bound = unsafe {
			libc::bind(
				fd.as_raw_fd(),
				(&address as *const libc::sockaddr_nl).cast(),
				size_of::<libc::sockaddr_nl>() as u32,
			)
		};
		if bound < 0 {
			return Err(io::Error::last_os_error());
		}
		Ok(Netlink { fd, sequence: 1, buffer: vec![0; 64 * 1024] })
	}

	/// Sends what `build` makes with the next sequence number, and waits for the kernel's acknowledgement.
	pub fn call(&mut self, build: impl FnOnce(u32) -> Vec<u8>) -> io::Result<()> {
		let sequence = self.next();
		self.send(&build(sequence))?;
		loop {
			let count = self.receive()?;
			for reply in netlink::replies(&self.buffer[..count]) {
				if reply.sequence != sequence {
					continue;
				}
				match reply.error() {
					Some(0) => return Ok(()),
					Some(error) => return Err(io::Error::from_raw_os_error(-error)),
					None => {}
				}
			}
		}
	}

	/// Takes away every rule of `family` at `priority`.
	pub fn delete_rules_at(&mut self, family: Family, priority: u32) -> io::Result<()> {
		// A thousand is far more than shira-vm ever adds. It only stops a loop if something's badly wrong.
		for _ in 0..1000 {
			match self.call(|sequence| netlink::delete_rule_at(family, priority, sequence)) {
				Ok(()) => {}
				Err(error) if error.raw_os_error() == Some(ENOENT) => return Ok(()),
				Err(error) => return Err(error),
			}
		}
		Ok(())
	}

	/// The priority of every rule of `family`.
	pub fn rule_priorities(&mut self, family: Family) -> io::Result<Vec<u32>> {
		let sequence = self.next();
		self.send(&netlink::dump_rules(family, sequence))?;
		let mut priorities = Vec::new();
		loop {
			let count = self.receive()?;
			for reply in netlink::replies(&self.buffer[..count]) {
				if reply.sequence != sequence {
					continue;
				}
				if reply.kind == netlink::NLMSG_DONE {
					return Ok(priorities);
				}
				if let Some(error) = reply.error().filter(|&error| error != 0) {
					return Err(io::Error::from_raw_os_error(-error));
				}
				if let Some((_, priority)) = reply.rule() {
					priorities.push(priority);
				}
			}
		}
	}

	fn next(&mut self) -> u32 {
		self.sequence = self.sequence.wrapping_add(1);
		self.sequence
	}

	fn send(&self, message: &[u8]) -> io::Result<()> {
		// SAFETY: message is valid for its length.
		let sent = unsafe { libc::send(self.fd.as_raw_fd(), message.as_ptr().cast(), message.len(), 0) };
		if sent < 0 { Err(io::Error::last_os_error()) } else { Ok(()) }
	}

	fn receive(&mut self) -> io::Result<usize> {
		// SAFETY: buffer is valid for its length.
		let count = unsafe { libc::recv(self.fd.as_raw_fd(), self.buffer.as_mut_ptr().cast(), self.buffer.len(), 0) };
		if count < 0 { Err(io::Error::last_os_error()) } else { Ok(count as usize) }
	}
}
