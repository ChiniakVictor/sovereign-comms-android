package com.victor.traillink.core

import java.security.MessageDigest
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Turns a human passcode into the Meshtastic channel settings shared by every radio in the group.
 *
 *  channel name = "TL" + first 6 hex chars of SHA-256("TrailLink/name/v1/" + passcode)
 *  PSK          = PBKDF2-HMAC-SHA256(passcode, APP_SALT + channelName, 100 000 iterations, 32 bytes)  -> AES-256
 *
 * The channel name also picks the LoRa frequency slot (Meshtastic hashes the primary channel name),
 * so different passcodes land on different frequencies as well as different keys.
 */
object Psk {
    const val APP_SALT = "com.victor.traillink/psk/v1/"
    const val ITERATIONS = 100_000
    const val KEY_BYTES = 32
    const val MIN_LENGTH = 8

    fun normalize(passcode: String): String = Normalizer.normalize(passcode.trim(), Normalizer.Form.NFC)

    fun channelName(passcode: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(("TrailLink/name/v1/" + normalize(passcode)).toByteArray(Charsets.UTF_8))
        return "TL" + d.take(3).joinToString("") { "%02x".format(it) }
    }

    fun derive(passcode: String, channelName: String = channelName(passcode), iterations: Int = ITERATIONS): ByteArray =
        pbkdf2Sha256(normalize(passcode).toCharArray(), (APP_SALT + channelName).toByteArray(Charsets.UTF_8), iterations, KEY_BYTES)

    fun pbkdf2Sha256(password: CharArray, salt: ByteArray, iterations: Int, bytes: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, bytes * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Short fingerprint so two people can compare that they typed the same passcode, without revealing it. */
    fun fingerprint(psk: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(psk)
        return d.take(4).joinToString("-") { "%02X".format(it) }
    }
}
