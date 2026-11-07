package com.wonderhoylabs.shira.tunnel

/** A queue of bytes that can be read from anywhere in it, and starts small because most flows are. */
internal class RingBuffer {
    private var bytes = ByteArray(INITIAL_BYTES)
    private var head = 0

    var size = 0
        private set

    fun append(source: ByteArray, offset: Int, length: Int) {
        ensureCapacity(size + length)
        val tail = (head + size) % bytes.size
        val first = minOf(length, bytes.size - tail)
        System.arraycopy(source, offset, bytes, tail, first)
        System.arraycopy(source, offset + first, bytes, 0, length - first)
        size += length
    }

    /** Copies [length] bytes, starting [offset] bytes into the queue, without removing them. */
    fun peek(offset: Int, target: ByteArray, targetOffset: Int, length: Int) {
        val start = (head + offset) % bytes.size
        val first = minOf(length, bytes.size - start)
        System.arraycopy(bytes, start, target, targetOffset, first)
        System.arraycopy(bytes, 0, target, targetOffset + first, length - first)
    }

    fun drop(length: Int) {
        head = (head + length) % bytes.size
        size -= length
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= bytes.size) return
        var capacity = bytes.size
        while (capacity < needed) capacity *= 2
        val grown = ByteArray(capacity)
        peek(0, grown, 0, size)
        bytes = grown
        head = 0
    }

    private companion object {
        const val INITIAL_BYTES = 4 * 1024
    }
}
