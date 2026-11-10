# The Shira link

Version 1. This is what goes between the app and the web page, for anyone writing another client. The Kotlin version is in
[`Link.kt`](../android/app/src/main/kotlin/com/wonderhoylabs/shira/bridge/Link.kt) and the TypeScript one is in
[`frames.ts`](../web/src/link/frames.ts). Their tests check the same bytes.

## The socket

The app listens on the abstract Unix socket `shira`, and the page reaches it through `adb` by opening the service
`localabstract:shira`. The app takes connections only from the shell user or root, which is what `adbd` runs as, so no other
app can see or inject traffic.

Only one page can be attached. A new connection replaces the old one, and the app tells the old one so that it doesn't try to
take the socket back.

## Packets

Everything on the socket is a [Wisp](https://github.com/MercuryWorkshop/wisp-protocol/blob/main/protocol.md) packet with its
length in front, because a socket has no message boundaries of its own the way a WebSocket does. Numbers are little-endian.

| Bytes | Value |
| --- | --- |
| 4 | Length of everything after this field, at most 1 MiB |
| 1 | Type |
| 4 | Stream ID. 0 for anything that isn't about a stream |
| n | Payload |

Types below `0x80` mean exactly what they mean in Wisp, and the page carries them on to the Wisp server through
[wisp-js](https://github.com/MercuryWorkshop/wisp-js). The others stay on the cable.

| Type | Name | From | Payload |
| --- | --- | --- | --- |
| `0x01` | CONNECT | App | Stream type in 1 byte, port in 2, then the destination as text |
| `0x02` | DATA | Both | Bytes |
| `0x03` | CONTINUE | Page | How many more packets the server can take on this stream, in 4 bytes |
| `0x04` | CLOSE | Both | Reason in 1 byte |
| `0x80` | HELLO | App | This protocol's version in 1 byte, then the app's version as text |
| `0x81` | START | Page | Packets the server can take per stream in 4 bytes, 1 byte that's nonzero if it allows UDP, then its name as text |
| `0x82` | STOP | Page | 1 byte that's nonzero if the service should stop too |
| `0x83` | STATUS | App | Code in 1 byte, then a message as text |
| `0x84` | META | App | See below |
| `0x85` | PING | Page | Nothing |

Skip packets with a type you don't know.

## How a session goes

1. The page connects, and the app sends HELLO straight away. If the protocol versions differ, the page tells you to install
   the app from the same site.
2. The page connects to the Wisp server, and sends START when it's ready. Until then the phone has no VPN, so its own network
   keeps working.
3. The app brings the VPN up and sends STATUS.
4. CONNECT, DATA and CLOSE flow for as long as it lasts.
5. If the Wisp connection drops, the page sends STOP and starts again from step 2 when it has a new one.

| Status | Name | Meaning |
| --- | --- | --- |
| 0 | Down | The VPN is down, because the page asked |
| 1 | Up | The VPN is up |
| 2 | Failed | Android wouldn't start the VPN, and the message says why |
| 3 | Off | Someone's turning Shira off on the phone. The page must not turn it back on |
| 4 | Replaced | Another page took over. This one must not take it back |

The page sends PING every 2 seconds. A closed tab doesn't always close the socket, so if the app hears nothing for 10 seconds
it brings the VPN down and goes back to waiting.

## META

Tells the page about a stream for its log, right after the CONNECT.

| Bytes | Value |
| --- | --- |
| 4 | The Android user ID of the app that made the connection, or -1 if Android can't say |
| 1 | 0 for a connection, 1 for a DNS lookup |
| 2 | For a DNS lookup, the record type being asked for |
| n | The name being looked up, or the name that the destination's address came from, as text. It may be empty |

## Flow control

Going up, the app counts packets the way a Wisp client does. Each stream starts with the number in START, each DATA uses one,
and each CONTINUE sets a new number. wisp-js keeps its own count too and holds packets back if the two ever drift apart.

Coming down, Wisp has no way to slow one stream. The only brake is the whole connection, by not reading from it. When the app
is holding too much for apps that haven't read it yet, it stops reading the socket. `adb` then stops taking data from the page,
the page stops reading its WebSocket, and the server eventually stops reading from the internet. If an app has stopped reading
altogether while this is going on, like a paused video, it loses its connection so that it doesn't hold everything else up.

## DNS

Android is told to use a resolver at `10.219.0.2`, which nothing's behind. The app picks UDP packets to port 53 out of the
packet stream and answers from a small cache if it can. Otherwise, it opens one Wisp TCP stream per question to `1.1.1.1:53`,
using DNS's own TCP format, and closes it after the answer. A TCP stream works on every Wisp server, while UDP is optional, and
one per question keeps failures simple. A question gets a failure answer if the stream closes first, or after 5 seconds.

An answer that's too big for the client's UDP limit comes back truncated, and the client asks again over TCP. That connection
goes through to `1.1.1.1:53` like any other.

# The VM link

Version 1. This is what goes between the app and `shira-vm` in Android's Linux Terminal. The Kotlin version is in
[`VmLink.kt`](../android/tunnel/src/main/kotlin/com/wonderhoylabs/shira/bridge/VmLink.kt) and the Rust one in
[`link.rs`](../crates/shira-vm/src/link.rs). Their tests check the same bytes.

## Why it exists

Android gives the Terminal's VM its network through a tap interface, `avf_tap_fixed`, which it serves the way it serves a
hotspot (`TETHERING_VIRTUAL`). Tethering picks its upstream from the system's default network, which a VPN never is, so the
VM's traffic is forwarded straight out of the phone and Shira's VPN never sees it.

## The socket

While the VPN is up, the app listens on TCP port 47830 on its own address on `avf_tap_fixed`, and nowhere else. It takes a
connection only from the rest of that interface's subnet, so the phone's own apps, which would come from the app's address,
can't use it. A new connection replaces the old one. When the VPN goes down, the app closes both.

The app leaves itself out of its own VPN. Android only lets the apps inside a VPN receive packets on the VPN's interface,
and would route the app's answers into the VPN.

## Frames

| Bytes | Value |
| --- | --- |
| 4 | Length of everything after this field, little-endian, at most 65,536 |
| 1 | Type |
| n | Payload |

| Type | Name | From | Payload |
| --- | --- | --- | --- |
| `0x01` | HELLO | App, first | The protocol version in 1 byte, the VM's address and resolver in 4 bytes each, most significant first, the MTU in 2 little-endian bytes, then the app's version as text |
| `0x02` | PACKET | Both | One IPv4 packet |

The app ends the VM's packets in a second `Tunnel` that shares the page with the phone's own. Its streams take IDs from the
same counter, so the page can't tell them apart, and its DNS goes to the same resolver at `10.219.0.2`. The VM is `10.219.0.3`.

## Inside the VM

`shira-vm` adds policy routing rules before anything else, and leaves them there when it stops:

| Priority | Rule |
| --- | --- |
| 5300 | DNS (UDP and TCP 53, TCP 853) to the local networks is unreachable, because the phone answers it from its own network |
| 5301 | The local networks, link-local and multicast use the main table, but never its default route |
| 5302 | Everything else uses table 5302, which only has a default route into `shira0` while the link is up |
| 5303 | Whatever's left is unreachable, for IPv4 and IPv6 |

So the VM reaches the phone, the Terminal app that shows it, and Shira's port, and nothing else unless the link is up.
