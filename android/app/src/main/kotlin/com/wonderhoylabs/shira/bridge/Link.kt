package com.wonderhoylabs.shira.bridge

import java.io.IOException

/**
 * The messages between the app and the web page, over one adb socket.
 *
 * Each message is a Wisp packet with its length in front, because a socket has no message boundaries
 * of its own the way a WebSocket does. CONNECT, DATA, CONTINUE and CLOSE mean what they mean in Wisp,
 * and all numbers are little-endian as they are there. The other types stay on the cable.
 * See docs/protocol.md.
 */
object Link {
    const val SOCKET_NAME = "shira"

    /** Has to match PROTOCOL_VERSION in the web app. They refuse each other if it doesn't. */
    const val PROTOCOL_VERSION = 1

    const val CONNECT = 0x01
    const val DATA = 0x02
    const val CONTINUE = 0x03
    const val CLOSE = 0x04

    /** App to page, first thing on a new socket: protocol version, then the app's version. */
    const val HELLO = 0x80
    /** Page to app, once it's connected to the Wisp server: bring the VPN up. */
    const val START = 0x81
    /** Page to app: bring the VPN down, and stop the service too if the flag is set. */
    const val STOP = 0x82
    /** App to page: what happened to the VPN. */
    const val STATUS = 0x83
    /** App to page: what's known about a stream, for the log. */
    const val META = 0x84
    /** Page to app every couple of seconds. A closed tab doesn't always close the socket. */
    const val PING = 0x85

    const val STREAM_TCP = 0x01
    const val STREAM_UDP = 0x02

    const val CLOSE_UNKNOWN = 0x01
    const val CLOSE_VOLUNTARY = 0x02
    const val CLOSE_CLIENT_ERROR = 0x81

    const val STATUS_DOWN = 0
    const val STATUS_UP = 1
    const val STATUS_FAILED = 2

    /** Sent last when someone turns Shira off on the phone, so the page knows not to turn it back on. */
    const val STATUS_OFF = 3

    /** Sent last when another page takes over, so that this one stands down instead of taking it back. */
    const val STATUS_REPLACED = 4

    const val META_CONNECTION = 0
    const val META_DNS = 1

    const val LENGTH_BYTES = 4
    const val HEADER_BYTES = 5
    const val FRAME_OVERHEAD = LENGTH_BYTES + HEADER_BYTES
    const val MAX_PACKET_BYTES = 1 shl 20

    private const val UID_BYTES = 4
    private const val KIND_BYTES = 1
    private const val QUERY_TYPE_BYTES = 2

    /** A frame with room for [payload] bytes, with its length, type and stream already filled in. */
    fun frame(type: Int, stream: Int, payload: Int): ByteArray {
        val frame = ByteArray(FRAME_OVERHEAD + payload)
        putInt(frame, 0, HEADER_BYTES + payload)
        frame[LENGTH_BYTES] = type.toByte()
        putInt(frame, LENGTH_BYTES + 1, stream)
        return frame
    }

    fun connect(stream: Int, streamType: Int, port: Int, host: String): ByteArray {
        val name = host.toByteArray()
        val frame = frame(CONNECT, stream, 3 + name.size)
        frame[FRAME_OVERHEAD] = streamType.toByte()
        putShort(frame, FRAME_OVERHEAD + 1, port)
        name.copyInto(frame, FRAME_OVERHEAD + 3)
        return frame
    }

    fun data(stream: Int, source: ByteArray, offset: Int, length: Int): ByteArray {
        val frame = frame(DATA, stream, length)
        System.arraycopy(source, offset, frame, FRAME_OVERHEAD, length)
        return frame
    }

    fun close(stream: Int, reason: Int): ByteArray {
        val frame = frame(CLOSE, stream, 1)
        frame[FRAME_OVERHEAD] = reason.toByte()
        return frame
    }

    fun hello(appVersion: String): ByteArray {
        val version = appVersion.toByteArray()
        val frame = frame(HELLO, 0, 1 + version.size)
        frame[FRAME_OVERHEAD] = PROTOCOL_VERSION.toByte()
        version.copyInto(frame, FRAME_OVERHEAD + 1)
        return frame
    }

    fun status(code: Int, message: String = ""): ByteArray {
        val text = message.toByteArray()
        val frame = frame(STATUS, 0, 1 + text.size)
        frame[FRAME_OVERHEAD] = code.toByte()
        text.copyInto(frame, FRAME_OVERHEAD + 1)
        return frame
    }

    /** [uid] is -1 if Android can't say. [name] is the hostname if the resolver has seen one. */
    fun meta(stream: Int, uid: Int, kind: Int, queryType: Int, name: String): ByteArray {
        val text = name.toByteArray()
        val frame = frame(META, stream, UID_BYTES + KIND_BYTES + QUERY_TYPE_BYTES + text.size)
        putInt(frame, FRAME_OVERHEAD, uid)
        frame[FRAME_OVERHEAD + UID_BYTES] = kind.toByte()
        putShort(frame, FRAME_OVERHEAD + UID_BYTES + KIND_BYTES, queryType)
        text.copyInto(frame, FRAME_OVERHEAD + UID_BYTES + KIND_BYTES + QUERY_TYPE_BYTES)
        return frame
    }

    fun putInt(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value shr 8).toByte()
        bytes[at + 2] = (value shr 16).toByte()
        bytes[at + 3] = (value shr 24).toByte()
    }

    fun putShort(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = value.toByte()
        bytes[at + 1] = (value shr 8).toByte()
    }

    fun getInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xff) or
            ((bytes[at + 1].toInt() and 0xff) shl 8) or
            ((bytes[at + 2].toInt() and 0xff) shl 16) or
            ((bytes[at + 3].toInt() and 0xff) shl 24)
}

/** What a [FrameReader] finds. The payload is only valid during the call. */
fun interface PacketHandler {
    fun onPacket(type: Int, stream: Int, buffer: ByteArray, offset: Int, length: Int)
}

/** Finds whole packets in what comes off the socket, which can be split up in any way. */
class FrameReader(private val handler: PacketHandler) {
    private val buffer = ByteArray(BUFFER_BYTES)
    private var filled = 0

    /** Lets [read] fill the free space, and returns what it returned. Calls the handler for each packet. */
    inline fun fill(read: (ByteArray, Int, Int) -> Int): Int {
        val count = read(storage(), position(), space())
        if (count > 0) received(count)
        return count
    }

    @PublishedApi internal fun storage() = buffer

    @PublishedApi internal fun position() = filled

    @PublishedApi internal fun space() = buffer.size - filled

    @PublishedApi
    internal fun received(count: Int) {
        filled += count
        var offset = 0
        while (filled - offset >= Link.LENGTH_BYTES) {
            val length = Link.getInt(buffer, offset)
            if (length < Link.HEADER_BYTES || length > Link.MAX_PACKET_BYTES) {
                throw IOException("The web page sent a packet of $length bytes.")
            }
            if (filled - offset - Link.LENGTH_BYTES < length) break
            val start = offset + Link.LENGTH_BYTES
            handler.onPacket(
                buffer[start].toInt() and 0xff,
                Link.getInt(buffer, start + 1),
                buffer,
                start + Link.HEADER_BYTES,
                length - Link.HEADER_BYTES,
            )
            offset = start + length
        }
        if (offset > 0) {
            System.arraycopy(buffer, offset, buffer, 0, filled - offset)
            filled -= offset
        }
    }

    private companion object {
        const val BUFFER_BYTES = Link.MAX_PACKET_BYTES + 256 * 1024
    }
}
