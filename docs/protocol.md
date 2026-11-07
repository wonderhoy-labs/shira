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
