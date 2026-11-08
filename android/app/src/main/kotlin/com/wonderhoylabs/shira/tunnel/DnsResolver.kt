package com.wonderhoylabs.shira.tunnel

/**
 * The resolver the phone runs for itself.
 *
 * Android is told to use an address with nothing behind it for DNS. Queries to it are picked out of the
 * packet stream by [Tunnel], answered from a small cache here if possible, and otherwise sent to 1.1.1.1
 * through Wisp. This only looks after the messages. It also remembers which name each address came from,
 * so that the log can show names.
 */
internal class DnsResolver {
	class Question(val name: String, val type: Int)

	private class CacheEntry(val response: ByteArray, val expiresAt: Long)

	private val cache = HashMap<String, CacheEntry>()
	private val names = object : LinkedHashMap<Int, String>(MAX_NAMES, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>) = size > MAX_NAMES
	}

	/** The name that [address] was last an answer for, if any. */
	fun nameOf(address: Int): String? = names[address]

	/** A reply from the cache, using this query's ID and spelling of the name. */
	fun cached(query: ByteArray, now: Long): ByteArray? {
		val key = cacheKey(query) ?: return null
		val entry = cache[key] ?: return null
		if (entry.expiresAt <= now) {
			cache.remove(key)
			return null
		}
		val reply = entry.response.copyOf()
		System.arraycopy(query, 0, reply, 0, ID_BYTES)
		val end = questionEnd(query) ?: return null
		System.arraycopy(query, HEADER_BYTES, reply, HEADER_BYTES, end - HEADER_BYTES)
		return reply
	}

	fun remember(query: ByteArray, response: ByteArray, now: Long) {
		learnNames(query, response)
		val key = cacheKey(query) ?: return
		val ttl = cacheableTtlSeconds(response) ?: return
		if (cache.size >= MAX_CACHE_ENTRIES) {
			cache.values.removeAll { it.expiresAt <= now }
			if (cache.size >= MAX_CACHE_ENTRIES) cache.clear()
		}
		cache[key] = CacheEntry(response, now + ttl * MILLIS_PER_SECOND)
	}

	private fun learnNames(query: ByteArray, response: ByteArray) {
		val name = question(query)?.name ?: return
		if (response.size < HEADER_BYTES || u8(response, 3) and RCODE_MASK != 0) return
		var at = questionEnd(response) ?: return
		repeat(u16(response, ANSWER_COUNT_AT)) {
			at = skipName(response, at) ?: return
			if (at + RECORD_FIXED_BYTES > response.size) return
			val type = u16(response, at)
			val length = u16(response, at + RECORD_LENGTH_AT)
			if (type == TYPE_A && length == IPV4_BYTES && at + RECORD_FIXED_BYTES + IPV4_BYTES <= response.size) {
				names[i32(response, at + RECORD_FIXED_BYTES)] = name
			}
			at += RECORD_FIXED_BYTES + length
		}
	}

	companion object {
		private const val ID_BYTES = 2
		private const val HEADER_BYTES = 12
		private const val QUESTION_COUNT_AT = 4
		private const val ANSWER_COUNT_AT = 6
		private const val AUTHORITY_COUNT_AT = 8
		private const val ADDITIONAL_COUNT_AT = 10
		private const val QUESTION_FIXED_BYTES = 4
		private const val RECORD_FIXED_BYTES = 10
		private const val RECORD_TTL_AT = 4
		private const val RECORD_LENGTH_AT = 8
		private const val IPV4_BYTES = 4
		private const val TYPE_A = 1
		private const val TYPE_OPT = 41
		private const val FLAG_RESPONSE = 0x80
		private const val FLAG_TRUNCATED = 0x02
		private const val FLAGS_RESPONSE = 0x8180
		private const val RCODE_MASK = 0x0f
		private const val RCODE_SERVFAIL = 2
		private const val MAX_CACHE_ENTRIES = 512
		private const val MAX_CACHE_TTL_SECONDS = 300L
		private const val MAX_NAMES = 4096
		private const val MILLIS_PER_SECOND = 1_000L
		private const val MAX_NAME_LENGTH = 255
		private const val COMPRESSION_MARK = 0xc0
		private const val LONGEST_LABEL = 63
		const val PLAIN_UDP_LIMIT = 512

		/** Where the single question in a message ends, or null if it doesn't have exactly one. */
		private fun questionEnd(message: ByteArray): Int? {
			if (message.size < HEADER_BYTES || u16(message, QUESTION_COUNT_AT) != 1) return null
			val nameEnd = skipName(message, HEADER_BYTES) ?: return null
			val end = nameEnd + QUESTION_FIXED_BYTES
			return if (end <= message.size) end else null
		}

		/** The question in a query, if it has exactly one and its name is written out in full. */
		fun question(query: ByteArray): Question? {
			val end = questionEnd(query) ?: return null
			val name = StringBuilder()
			var at = HEADER_BYTES
			while (true) {
				val length = u8(query, at)
				if (length == 0) break
				if (length > LONGEST_LABEL) return null
				if (name.isNotEmpty()) name.append('.')
				for (index in at + 1..at + length) name.append((query[index].toInt() and 0xff).toChar())
				if (name.length > MAX_NAME_LENGTH) return null
				at += 1 + length
			}
			return Question(name.toString().lowercase(), u16(query, end - QUESTION_FIXED_BYTES))
		}

		/** Tells a client that asked over UDP to ask again over TCP, if the answer's too big for it. */
		fun fitForUdp(query: ByteArray, response: ByteArray): ByteArray {
			if (response.size <= udpLimit(query)) return response
			val end = questionEnd(response) ?: return servfail(query)
			val cut = response.copyOf(end)
			cut[2] = (u8(cut, 2) or FLAG_TRUNCATED).toByte()
			putU16(cut, ANSWER_COUNT_AT, 0)
			putU16(cut, AUTHORITY_COUNT_AT, 0)
			putU16(cut, ADDITIONAL_COUNT_AT, 0)
			return cut
		}

		/** The answer for when 1.1.1.1 can't be reached. */
		fun servfail(query: ByteArray): ByteArray {
			val end = questionEnd(query) ?: HEADER_BYTES
			val reply = query.copyOf(minOf(end, query.size))
			putU16(reply, 2, FLAGS_RESPONSE or RCODE_SERVFAIL)
			putU16(reply, ANSWER_COUNT_AT, 0)
			putU16(reply, AUTHORITY_COUNT_AT, 0)
			putU16(reply, ADDITIONAL_COUNT_AT, 0)
			return reply
		}

		/** How big a reply the client said it can take, in an OPT record, or the standard's 512 bytes. */
		private fun udpLimit(query: ByteArray): Int {
			var at = questionEnd(query) ?: return PLAIN_UDP_LIMIT
			val records = u16(query, ANSWER_COUNT_AT) + u16(query, AUTHORITY_COUNT_AT) + u16(query, ADDITIONAL_COUNT_AT)
			repeat(records) {
				at = skipName(query, at) ?: return PLAIN_UDP_LIMIT
				if (at + RECORD_FIXED_BYTES > query.size) return PLAIN_UDP_LIMIT
				// An OPT record keeps the client's limit where other records keep their class.
				if (u16(query, at) == TYPE_OPT) return maxOf(u16(query, at + 2), PLAIN_UDP_LIMIT)
				at += RECORD_FIXED_BYTES + u16(query, at + RECORD_LENGTH_AT)
			}
			return PLAIN_UDP_LIMIT
		}

		/**
		 * The cache key for a query: its question in lowercase plus everything after it, so that
		 * queries asking for different EDNS options don't share an answer.
		 */
		private fun cacheKey(query: ByteArray): String? {
			val end = questionEnd(query) ?: return null
			val key = StringBuilder(query.size)
			for (index in HEADER_BYTES until query.size) {
				var byte = u8(query, index)
				if (index < end && byte in 'A'.code..'Z'.code) byte += 'a'.code - 'A'.code
				key.append(byte.toChar())
			}
			return key.toString()
		}

		/**
		 * How long a response may be cached, in seconds, or null if it shouldn't be. Only successful
		 * answers are kept. TTLs aren't rewritten when serving from the cache, so the cap also limits
		 * how stale a record can get in the system's own cache.
		 */
		private fun cacheableTtlSeconds(response: ByteArray): Long? {
			if (response.size < HEADER_BYTES) return null
			val flags = u8(response, 2)
			val answers = u16(response, ANSWER_COUNT_AT)
			val authorities = u16(response, AUTHORITY_COUNT_AT)
			val failed = u8(response, 3) and RCODE_MASK != 0
			if (flags and FLAG_RESPONSE == 0 || flags and FLAG_TRUNCATED != 0 || failed || answers == 0) return null

			var at = questionEnd(response) ?: return null
			var ttl = MAX_CACHE_TTL_SECONDS
			repeat(answers + authorities) {
				at = skipName(response, at) ?: return null
				if (at + RECORD_FIXED_BYTES > response.size) return null
				ttl = minOf(ttl, i32(response, at + RECORD_TTL_AT).toLong() and 0xffffffffL)
				at += RECORD_FIXED_BYTES + u16(response, at + RECORD_LENGTH_AT)
				if (at > response.size) return null
			}
			return if (ttl > 0) ttl else null
		}

		private fun skipName(message: ByteArray, start: Int): Int? {
			var at = start
			while (true) {
				if (at >= message.size) return null
				val length = u8(message, at)
				when {
					length == 0 -> return at + 1

					length <= LONGEST_LABEL -> at += 1 + length

					// A compression pointer ends the name.
					length >= COMPRESSION_MARK -> return if (at + 1 < message.size) at + 2 else null

					else -> return null
				}
			}
		}
	}
}
