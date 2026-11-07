package app.dkord.data.room

import java.security.SecureRandom
import java.util.Locale
import java.util.Random

object RoomCode {
    const val LENGTH = 10
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(random: Random = SecureRandom()): String =
        buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun normalize(input: String): String? = input.trim().uppercase(Locale.ROOT)
        .takeIf { it.length == LENGTH && it.all { char -> char in ALPHABET } }

    fun topic(code: String): String = "dkord-" + requireNotNull(normalize(code)) { "Invalid room code" }
}
