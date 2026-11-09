//! `install`, `uninstall` and `status`: shira-vm as a systemd service that starts before the VM's network.

use super::firewall::Firewall;
use super::require_root;
use crate::rules::DEVICE;
use std::fs;
use std::io;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::process::Command;

const BINARY: &str = "/usr/local/bin/shira-vm";
const SERVICE: &str = "shira-vm.service";
const UNIT: &str = "/etc/systemd/system/shira-vm.service";
const NETWORKD_CONFIG: &str = "/etc/systemd/networkd.conf.d/shira-vm.conf";
const NETWORK_FILE: &str = "/etc/systemd/network/10-shira-vm.network";

/// Starts before the network does, so the VM's traffic is never let out, and again whenever it stops.
const UNIT_TEXT: &str = "\
# Installed by shira-vm. `sudo shira-vm uninstall` takes it away again.
[Unit]
Description=Shira for this VM: its traffic goes through Shira on the phone, or nowhere
Documentation=https://github.com/wonderhoy-labs/shira#androids-linux-terminal
DefaultDependencies=no
After=local-fs.target
Before=network-pre.target shutdown.target
Wants=network-pre.target
Conflicts=shutdown.target

[Service]
ExecStart=/usr/local/bin/shira-vm run
Restart=always
RestartSec=2

[Install]
WantedBy=multi-user.target
";

/// systemd-networkd takes away routing rules it didn't make whenever it starts, unless it's told not to.
const NETWORKD_TEXT: &str = "\
# Installed by shira-vm, whose routing rules keep this VM's traffic from leaving except through Shira.
[Network]
ManageForeignRoutingPolicyRules=no
ManageForeignRoutes=no
";

const NETWORK_TEXT: &str = "\
# Installed by shira-vm, which sets up its own device.
[Match]
Name=shira0

[Link]
Unmanaged=yes
";

pub fn install() -> io::Result<()> {
	require_root("install")?;
	let me = std::env::current_exe()?;
	if me != Path::new(BINARY) {
		// Copied to a new name first, so that a running service never sees half a file.
		let partial = format!("{BINARY}.new");
		fs::copy(&me, &partial)?;
		fs::set_permissions(&partial, fs::Permissions::from_mode(0o755))?;
		fs::rename(&partial, BINARY)?;
	}
	write(NETWORKD_CONFIG, NETWORKD_TEXT)?;
	write(NETWORK_FILE, NETWORK_TEXT)?;
	write(UNIT, UNIT_TEXT)?;
	systemctl(&["daemon-reload"])?;
	// So that it reads the setting above before shira-vm's rules are there.
	systemctl(&["try-restart", "systemd-networkd.service"])?;
	systemctl(&["enable", SERVICE])?;
	systemctl(&["restart", SERVICE])?;
	println!(
		"shira-vm is installed and running. This VM's traffic now goes through Shira on the phone, and nowhere \
		 while Shira isn't connected.\nSee what it's doing with `shira-vm status` or `journalctl -u shira-vm`."
	);
	Ok(())
}

pub fn uninstall() -> io::Result<()> {
	require_root("uninstall")?;
	// Stopping it first, or it would put the rules straight back.
	let _ = systemctl(&["disable", "--now", SERVICE]);
	Firewall::open()?.remove_all()?;
	for path in [UNIT, NETWORK_FILE, NETWORKD_CONFIG, BINARY] {
		match fs::remove_file(path) {
			Err(error) if error.kind() != io::ErrorKind::NotFound => return Err(error),
			_ => {}
		}
	}
	systemctl(&["daemon-reload"])?;
	systemctl(&["try-restart", "systemd-networkd.service"])?;
	println!("shira-vm is gone, and this VM uses the phone's own network again.");
	Ok(())
}

pub fn status() -> io::Result<()> {
	let blocking = Firewall::open()?.blocking()?;
	let connected = Path::new("/sys/class/net").join(DEVICE).exists();
	let message = match (blocking, connected) {
		(true, true) => "Connected: this VM's traffic goes through Shira.",
		(true, false) => "Blocked: Shira isn't connected, so this VM's traffic can't go anywhere.",
		(false, true) => "Not blocked, though Shira's device is there. Restart shira-vm to put its rules back.",
		(false, false) => "Not set up: this VM uses the phone's own network. Run `sudo shira-vm install`.",
	};
	println!("{message}");
	Ok(())
}

fn write(path: &str, text: &str) -> io::Result<()> {
	if let Some(parent) = Path::new(path).parent() {
		fs::create_dir_all(parent)?;
	}
	fs::write(path, text)
}

fn systemctl(args: &[&str]) -> io::Result<()> {
	let status = Command::new("systemctl").args(args).status()?;
	if status.success() { Ok(()) } else { Err(io::Error::other(format!("`systemctl {}` failed", args.join(" ")))) }
}
