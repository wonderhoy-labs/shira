//! The parts that need Linux: netlink, the TUN device, the service, and installing it.

pub mod daemon;
pub mod firewall;
pub mod install;
mod socket;
mod tun;

use std::io;

/// Most of this changes the VM's network, which only root may do.
fn require_root(what: &str) -> io::Result<()> {
	// SAFETY: geteuid has no preconditions and can't fail.
	if unsafe { libc::geteuid() } == 0 {
		Ok(())
	} else {
		Err(io::Error::new(io::ErrorKind::PermissionDenied, format!("{what} needs root. Try: sudo shira-vm {what}")))
	}
}
