package dev.tgscope.domain.export

import java.security.MessageDigest

/**
 * Replaces user identity with stable aliases. The same user always gets the same alias for a
 * given salt, so threads and per-user statistics stay meaningful in anonymized exports.
 */
class Anonymizer(private val salt: String) {
    private val cache = HashMap<Long, Pair<Long, String>>()

    fun userId(userId: Long): Long = alias(userId).first

    fun username(userId: Long): String = alias(userId).second

    private fun alias(userId: Long): Pair<Long, String> = cache.getOrPut(userId) {
        val digest = MessageDigest.getInstance("SHA-256").digest("$salt:$userId".toByteArray())
        var id = 0L
        for (i in 0 until 8) id = (id shl 8) or (digest[i].toLong() and 0xFF)
        val positive = id and Long.MAX_VALUE
        val hex = digest.take(5).joinToString("") { "%02x".format(it) }
        positive to "user_$hex"
    }
}
