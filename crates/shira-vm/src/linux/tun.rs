//! The TUN device the VM's packets go into. It disappears, with its routes, when it's dropped.

use std::ffi::CString;
use std::fs::{File, OpenOptions};
use std::io;
use std::net::Ipv4Addr;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd, RawFd};
use std::os::unix::fs::OpenOptionsExt;

// From <linux/if_tun.h> and <linux/sockios.h>, the same on x86_64 and arm64.
const TUNSETIFF: u64 = 0x4004_54ca;
const IFF_TUN: i16 = 0x0001;
const IFF_NO_PI: i16 = 0x1000;
const SIOCGIFFLAGS: u64 = 0x8913;
const SIOCSIFFLAGS: u64 = 0x8914;
const SIOCSIFADDR: u64 = 0x8916;
const SIOCSIFNETMASK: u64 = 0x891c;
const SIOCSIFMTU: u64 = 0x8922;
const IFF_UP: i16 = 0x1;
const IFF_RUNNING: i16 = 0x40;

/// `struct ifreq`: a name, then a union whose biggest member is 24 bytes on 64-bit machines.
#[repr(C)]
struct InterfaceRequest {
	name: [u8; 16],
	data: [u8; 24],
}

impl InterfaceRequest {
	fn new(name: &str) -> InterfaceRequest {
		let mut request = InterfaceRequest { name: [0; 16], data: [0; 24] };
		request.name[..name.len()].copy_from_slice(name.as_bytes());
		request
	}

	fn set_flags(&mut self, flags: i16) {
		self.data[..2].copy_from_slice(&flags.to_ne_bytes());
	}

	fn flags(&self) -> i16 {
		i16::from_ne_bytes([self.data[0], self.data[1]])
	}

	fn set_address(&mut self, address: Ipv4Addr) {
		self.data = [0; 24];
		self.data[..2].copy_from_slice(&(libc::AF_INET as u16).to_ne_bytes());
		self.data[4..8].copy_from_slice(&address.octets());
	}

	fn set_int(&mut self, value: i32) {
		self.data[..4].copy_from_slice(&value.to_ne_bytes());
	}
}

pub struct Tun {
	file: File,
	pub index: u32,
}

impl Tun {
	/// Makes the device `name`, with `address` as its only address, and brings it up.
	pub fn create(name: &str, address: Ipv4Addr, mtu: u16) -> io::Result<Tun> {
		if name.len() >= 16 {
			return Err(io::Error::new(io::ErrorKind::InvalidInput, "that device name is too long"));
		}
		let file = OpenOptions::new()
			.read(true)
			.write(true)
			.custom_flags(libc::O_NONBLOCK | libc::O_CLOEXEC)
			.open("/dev/net/tun")?;
		let mut request = InterfaceRequest::new(name);
		request.set_flags(IFF_TUN | IFF_NO_PI);
		ioctl(file.as_raw_fd(), TUNSETIFF, &mut request)?;

		// SAFETY: socket has no memory arguments, and its result is checked before use.
		let control = unsafe { libc::socket(libc::AF_INET, libc::SOCK_DGRAM | libc::SOCK_CLOEXEC, 0) };
		if control < 0 {
			return Err(io::Error::last_os_error());
		}
		// SAFETY: control is a new descriptor that nothing else owns.
		let control = unsafe { OwnedFd::from_raw_fd(control) };
		let control = control.as_raw_fd();

		let mut request = InterfaceRequest::new(name);
		request.set_address(address);
		ioctl(control, SIOCSIFADDR, &mut request)?;
		request.set_address(Ipv4Addr::BROADCAST);
		ioctl(control, SIOCSIFNETMASK, &mut request)?;
		let mut request = InterfaceRequest::new(name);
		request.set_int(i32::from(mtu));
		ioctl(control, SIOCSIFMTU, &mut request)?;
		let mut request = InterfaceRequest::new(name);
		ioctl(control, SIOCGIFFLAGS, &mut request)?;
		let flags = request.flags() | IFF_UP | IFF_RUNNING;
		request.set_flags(flags);
		ioctl(control, SIOCSIFFLAGS, &mut request)?;

		let c_name = CString::new(name).map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))?;
		// SAFETY: c_name is a valid C string.
		let index = unsafe { libc::if_nametoindex(c_name.as_ptr()) };
		if index == 0 {
			return Err(io::Error::last_os_error());
		}
		Ok(Tun { file, index })
	}

	pub fn fd(&self) -> RawFd {
		self.file.as_raw_fd()
	}
}

fn ioctl(fd: RawFd, request: u64, argument: &mut InterfaceRequest) -> io::Result<()> {
	// SAFETY: every request here reads or writes one struct ifreq, which argument is.
	let result = unsafe { libc::ioctl(fd, request as _, argument as *mut InterfaceRequest) };
	if result < 0 { Err(io::Error::last_os_error()) } else { Ok(()) }
}
