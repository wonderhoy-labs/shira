//! `shira-vm run`: blocks everything, then keeps connecting to Shira, and moves packets while it's connected.

use super::firewall::Firewall;
use super::require_root;
use super::tun::Tun;
use crate::link::{self, FrameBuffer, Hello};
use crate::proc_net::{self, Network};
use crate::rules::{self, DEVICE};
use std::io::{self, Read, Write};
use std::net::{Ipv4Addr, Shutdown, SocketAddrV4, TcpStream};
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

const RETRY: Duration = Duration::from_secs(2);
const CONNECT_TIMEOUT: Duration = Duration::from_secs(3);
const HELLO_TIMEOUT: Duration = Duration::from_secs(5);
/// How long either side waits before checking whether it should stop.
const WAKE: Duration = Duration::from_millis(500);
/// How often the rules are checked while connected, in case the VM's network changed or someone took them away.
const RECHECK: Duration = Duration::from_secs(5);
const BATCH_PACKETS: usize = 64;
const BATCH_BYTES: usize = 256 * 1024;

pub fn run(server: Option<SocketAddrV4>) -> io::Result<()> {
	require_root("run")?;
	let mut firewall = Firewall::open()?;
	// First of all, before anything else can happen.
	firewall.ensure_fixed()?;
	let mut log = Log::default();
	log.say("everything but the local network is blocked until Shira connects".into());
	loop {
		let network = read_network();
		if let Err(error) = refresh(&mut firewall, &network, server) {
			log.say(format!("couldn't set up the rules for the local network: {error}"));
		}
		match server.or_else(|| network.gateway.map(|gateway| SocketAddrV4::new(gateway, link::PORT))) {
			None => log.say("waiting for the VM's network".into()),
			Some(address) => match TcpStream::connect_timeout(&address.into(), CONNECT_TIMEOUT) {
				Ok(stream) => {
					let result = session(stream, &mut firewall, &network, server, &mut log);
					let why = result.err().map_or("Shira closed the connection".into(), |error| error.to_string());
					log.say(format!("disconnected, so the VM's traffic is blocked: {why}"));
				}
				Err(error) => log.say(format!("Shira isn't there at {address}: {error}")),
			},
		}
		thread::sleep(RETRY);
	}
}

/// Only says something when it's different from last time, so that retries don't fill the journal.
#[derive(Default)]
struct Log {
	last: String,
}

impl Log {
	fn say(&mut self, message: String) {
		if message != self.last {
			eprintln!("shira-vm: {message}");
			self.last = message;
		}
	}
}

fn read_network() -> Network {
	let routes = std::fs::read_to_string("/proc/net/route").unwrap_or_default();
	let addresses = std::fs::read_to_string("/proc/net/if_inet6").unwrap_or_default();
	Network::from(&proc_net::parse_routes(&routes), &proc_net::parse_addresses(&addresses), DEVICE)
}

fn refresh(firewall: &mut Firewall, network: &Network, server: Option<SocketAddrV4>) -> io::Result<()> {
	firewall.ensure_fixed()?;
	firewall.set_local(rules::local_rules(network, server))
}

fn session(
	stream: TcpStream,
	firewall: &mut Firewall,
	network: &Network,
	server: Option<SocketAddrV4>,
	log: &mut Log,
) -> io::Result<()> {
	stream.set_nodelay(true)?;
	stream.set_read_timeout(Some(HELLO_TIMEOUT))?;
	let mut reader = stream.try_clone()?;
	let mut frames = FrameBuffer::default();
	let hello = read_hello(&mut reader, &mut frames)?;
	if hello.version != link::PROTOCOL_VERSION {
		return Err(io::Error::other(format!(
			"Shira speaks version {} of the link and this shira-vm speaks {}, so update whichever is older",
			hello.version,
			link::PROTOCOL_VERSION
		)));
	}

	let tun = Arc::new(Tun::create(DEVICE, hello.address, hello.mtu)?);
	firewall.route_into(tun.index)?;
	use_resolver(hello.resolver, log);
	log.say(format!("connected to Shira {}, and the VM's traffic goes through it", hello.app_version));

	let stop = Arc::new(AtomicBool::new(false));
	let uploads = {
		let (tun, stop, mut writer) = (tun.clone(), stop.clone(), stream.try_clone()?);
		thread::spawn(move || {
			let result = tun_to_shira(&tun, &mut writer, &stop);
			stop.store(true, Ordering::Relaxed);
			let _ = writer.shutdown(Shutdown::Both);
			result
		})
	};
	reader.set_read_timeout(Some(WAKE))?;
	let downloads = shira_to_tun(&mut reader, &mut frames, &tun, &stop, || {
		let current = read_network();
		if current.gateway != network.gateway && server.is_none() {
			return Err(io::Error::other("the VM's network changed"));
		}
		refresh(firewall, &current, server)
	});
	stop.store(true, Ordering::Relaxed);
	let _ = stream.shutdown(Shutdown::Both);
	let uploaded = uploads.join().unwrap_or_else(|_| Err(io::Error::other("the upload thread failed")));
	// Dropping the device takes its route, and systemd-resolved forgets its resolver.
	drop(tun);
	downloads.and(uploaded)
}

fn read_hello(reader: &mut TcpStream, frames: &mut FrameBuffer) -> io::Result<Hello> {
	loop {
		if let Some((kind, payload)) = frames.next_frame()? {
			if kind == link::HELLO {
				return Hello::parse(payload);
			}
			return Err(io::Error::new(io::ErrorKind::InvalidData, "Shira didn't say hello first"));
		}
		let count = reader.read(frames.space())?;
		if count == 0 {
			return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "Shira closed the connection straight away"));
		}
		frames.filled(count);
	}
}

/// Tells systemd-resolved to send every question to Shira's resolver, through the tunnel.
fn use_resolver(resolver: Ipv4Addr, log: &mut Log) {
	let address = resolver.to_string();
	for args in [["dns", DEVICE, address.as_str()], ["domain", DEVICE, "~."], ["default-route", DEVICE, "yes"]] {
		let done = Command::new("resolvectl")
			.args(args)
			.stdout(Stdio::null())
			.stderr(Stdio::null())
			.status()
			.is_ok_and(|status| status.success());
		if !done {
			log.say(format!(
				"couldn't tell systemd-resolved about Shira's resolver, so point /etc/resolv.conf at {address}"
			));
			return;
		}
	}
}

fn tun_to_shira(tun: &Tun, writer: &mut TcpStream, stop: &AtomicBool) -> io::Result<()> {
	let mut batch = Vec::with_capacity(BATCH_BYTES + link::MAX_FRAME_BYTES);
	let mut packet = vec![0u8; link::MAX_FRAME_BYTES];
	while !stop.load(Ordering::Relaxed) {
		if !wait_readable(tun.fd(), WAKE)? {
			continue;
		}
		batch.clear();
		for _ in 0..BATCH_PACKETS {
			// SAFETY: packet is valid for its length.
			let count = unsafe { libc::read(tun.fd(), packet.as_mut_ptr().cast(), packet.len()) };
			if count < 0 {
				let error = io::Error::last_os_error();
				if error.kind() == io::ErrorKind::WouldBlock {
					break;
				}
				return Err(error);
			}
			let count = count as usize;
			batch.extend_from_slice(&link::packet_header(count));
			batch.extend_from_slice(&packet[..count]);
			if batch.len() >= BATCH_BYTES {
				break;
			}
		}
		if !batch.is_empty() {
			writer.write_all(&batch)?;
		}
	}
	Ok(())
}

fn shira_to_tun(
	reader: &mut TcpStream,
	frames: &mut FrameBuffer,
	tun: &Tun,
	stop: &AtomicBool,
	mut recheck: impl FnMut() -> io::Result<()>,
) -> io::Result<()> {
	let mut checked = Instant::now();
	loop {
		// Whatever came in with the HELLO goes first.
		while let Some((kind, payload)) = frames.next_frame()? {
			if kind == link::PACKET {
				write_packet(tun, payload)?;
			}
		}
		if stop.load(Ordering::Relaxed) {
			return Ok(());
		}
		if checked.elapsed() >= RECHECK {
			checked = Instant::now();
			recheck()?;
		}
		match reader.read(frames.space()) {
			Ok(0) => return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "Shira closed the connection")),
			Ok(count) => frames.filled(count),
			Err(error) if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
			Err(error) if error.kind() == io::ErrorKind::Interrupted => {}
			Err(error) => return Err(error),
		}
	}
}

fn write_packet(tun: &Tun, packet: &[u8]) -> io::Result<()> {
	// SAFETY: packet is valid for its length.
	let written = unsafe { libc::write(tun.fd(), packet.as_ptr().cast(), packet.len()) };
	if written >= 0 {
		return Ok(());
	}
	let error = io::Error::last_os_error();
	match error.raw_os_error() {
		// Like a full network card, or a packet the kernel won't have: TCP sends it again, and UDP may lose it.
		Some(libc::EAGAIN | libc::ENOBUFS | libc::EINVAL) => Ok(()),
		_ => Err(error),
	}
}

fn wait_readable(fd: i32, timeout: Duration) -> io::Result<bool> {
	let mut poll = libc::pollfd { fd, events: libc::POLLIN, revents: 0 };
	// SAFETY: poll gets one valid pollfd.
	let ready = unsafe { libc::poll(&mut poll, 1, timeout.as_millis() as i32) };
	match ready {
		0 => Ok(false),
		1.. => Ok(true),
		_ => {
			let error = io::Error::last_os_error();
			if error.kind() == io::ErrorKind::Interrupted { Ok(false) } else { Err(error) }
		}
	}
}
