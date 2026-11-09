//! `shira-vm` sends the traffic of Android's Linux VM through Shira on the phone, and blocks it whenever Shira isn't
//! there to take it.
//!
//! Android gives the VM its network the way it serves a hotspot, and forwards it straight out of the phone, where no
//! VPN app sees it. So this hands the VM's IP packets to the Shira app over TCP instead, through a TUN device, and
//! policy routing rules keep everything else from leaving. `rules` has the rules, `link` the link to the app, and
//! docs/protocol.md both.

// Only Linux runs it. Elsewhere the parts that don't need Linux still build and test.
#![cfg_attr(not(target_os = "linux"), allow(dead_code))]

mod link;
#[cfg(target_os = "linux")]
mod linux;
mod netlink;
mod proc_net;
mod rules;

use std::net::SocketAddrV4;
use std::process::ExitCode;

const USAGE: &str = "\
Usage: shira-vm <command>

Sends this Linux VM's traffic through Shira on the phone, and blocks it whenever Shira isn't connected.

Commands:
  install     Install shira-vm as a service that starts with the VM, and start it
  uninstall   Stop it, take its rules away and remove it, so the VM uses the phone's network directly again
  status      Say whether the VM's traffic is blocked, and whether Shira is connected
  run         Run in the foreground, the way the service does

Options for run:
  --server ADDRESS:PORT   Where the Shira app is, instead of the default gateway on port 47830

Everything but status needs root, so run it with sudo.";

#[derive(Debug, PartialEq, Eq)]
enum Command {
	Install,
	Uninstall,
	Status,
	Run { server: Option<SocketAddrV4> },
	Help,
	Version,
}

fn parse(args: &[String]) -> Result<Command, String> {
	let mut args = args.iter().map(String::as_str);
	let command = match args.next() {
		None | Some("help" | "--help" | "-h") => return Ok(Command::Help),
		Some("--version" | "-V") => return Ok(Command::Version),
		Some("install") => Command::Install,
		Some("uninstall") => Command::Uninstall,
		Some("status") => Command::Status,
		Some("run") => {
			let mut server = None;
			while let Some(arg) = args.next() {
				match arg {
					"--server" => {
						let value = args.next().ok_or("--server needs an address, like 192.168.0.1:47830")?;
						server = Some(value.parse().map_err(|_| format!("{value} isn't an IPv4 address and port"))?);
					}
					other => return Err(format!("run doesn't take {other}")),
				}
			}
			return Ok(Command::Run { server });
		}
		Some(other) => return Err(format!("there's no command called {other}")),
	};
	match args.next() {
		Some(extra) => Err(format!("that command doesn't take {extra}")),
		None => Ok(command),
	}
}

fn main() -> ExitCode {
	let args: Vec<String> = std::env::args().skip(1).collect();
	let command = match parse(&args) {
		Ok(command) => command,
		Err(problem) => {
			eprintln!("shira-vm: {problem}\n\n{USAGE}");
			return ExitCode::from(2);
		}
	};
	match command {
		Command::Help => {
			println!("{USAGE}");
			ExitCode::SUCCESS
		}
		Command::Version => {
			println!("shira-vm {}", env!("CARGO_PKG_VERSION"));
			ExitCode::SUCCESS
		}
		other => run(other),
	}
}

#[cfg(target_os = "linux")]
fn run(command: Command) -> ExitCode {
	let result = match command {
		Command::Install => linux::install::install(),
		Command::Uninstall => linux::install::uninstall(),
		Command::Status => linux::install::status(),
		Command::Run { server } => linux::daemon::run(server),
		Command::Help | Command::Version => Ok(()),
	};
	match result {
		Ok(()) => ExitCode::SUCCESS,
		Err(error) => {
			eprintln!("shira-vm: {error}");
			ExitCode::FAILURE
		}
	}
}

#[cfg(not(target_os = "linux"))]
fn run(_: Command) -> ExitCode {
	eprintln!("shira-vm only runs on Linux, in Android's Terminal.");
	ExitCode::FAILURE
}

#[cfg(test)]
mod tests {
	use super::*;

	fn args(text: &str) -> Vec<String> {
		text.split_whitespace().map(String::from).collect()
	}

	#[test]
	fn reads_commands() {
		assert_eq!(parse(&args("")), Ok(Command::Help));
		assert_eq!(parse(&args("install")), Ok(Command::Install));
		assert_eq!(parse(&args("run")), Ok(Command::Run { server: None }));
		assert_eq!(
			parse(&args("run --server 192.168.65.254:47830")),
			Ok(Command::Run { server: Some("192.168.65.254:47830".parse().unwrap()) })
		);
	}

	#[test]
	fn says_what_it_doesnt_understand() {
		assert!(parse(&args("start")).is_err());
		assert!(parse(&args("install now")).is_err());
		assert!(parse(&args("run --server")).is_err());
		assert!(parse(&args("run --server phone")).is_err());
	}
}
