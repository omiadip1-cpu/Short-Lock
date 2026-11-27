package com.shortslock.app

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Keyed hash over the PBKDF2 output; on a phone the key lives in the Android Keystore and cannot be read out. */
interface Signer { fun sign(data: ByteArray): ByteArray }

/** Tiny string store; a null value removes the key. */
interface Store {
    fun get(key: String): String?
    /** Returns false if the write did not reach storage. */
    fun put(vararg kv: Pair<String, String?>): Boolean
}

class PinManager(
    private val store: Store,
    private val signer: Signer,
    private val now: () -> Long = System::currentTimeMillis,
    private val iterations: Int = 100_000,
) {
    sealed class Result {
        object Ok : Result()
        data class Wrong(val attemptsLeft: Int) : Result()
        data class LockedOut(val millis: Long) : Result()
    }

    fun isSet() = store.get(PIN_HASH) != null

    /** Stores a new PIN and returns a fresh one-time recovery code (shown to the parent once). */
    fun setPin(pin: String): String {
        require(PIN_RE.matches(pin)) { "PIN must be exactly 4 digits" }
        val code = newRecoveryCode()
        val pinSalt = salt()
        val recSalt = salt()
        val saved = store.put(
            PIN_SALT to pinSalt, PIN_HASH to hash(pin, pinSalt),
            REC_SALT to recSalt, REC_HASH to hash(normalize(code), recSalt),
            FAILS to "0", UNTIL to "0",
        )
        check(saved) { "Could not save the PIN" }
        return code
    }

    fun verify(pin: String): Result {
        check(isSet()) { "PIN not set" }
        val wait = (store.get(UNTIL)?.toLong() ?: 0L) - now()
        if (wait > 0) return Result.LockedOut(wait)
        if (same(hash(pin, store.get(PIN_SALT)!!), store.get(PIN_HASH)!!)) {
            store.put(FAILS to "0", UNTIL to "0")
            return Result.Ok
        }
        val fails = (store.get(FAILS)?.toInt() ?: 0) + 1
        if (fails < MAX_FREE_TRIES) {
            store.put(FAILS to fails.toString())
            return Result.Wrong(MAX_FREE_TRIES - fails)
        }
        val delay = minOf(BASE_LOCKOUT_MS shl minOf(fails - MAX_FREE_TRIES, 20), MAX_LOCKOUT_MS)
        store.put(FAILS to fails.toString(), UNTIL to (now() + delay).toString())
        return Result.LockedOut(delay)
    }

    /** Sets a new PIN if the recovery code is right. Also clears any lockout. The code itself stays valid. */
    fun resetWithRecovery(code: String, newPin: String): Boolean {
        require(PIN_RE.matches(newPin)) { "PIN must be exactly 4 digits" }
        val recSalt = store.get(REC_SALT) ?: return false
        if (!same(hash(normalize(code), recSalt), store.get(REC_HASH)!!)) return false
        val pinSalt = salt()
        return store.put(PIN_SALT to pinSalt, PIN_HASH to hash(newPin, pinSalt), FAILS to "0", UNTIL to "0")
    }

    private fun hash(secret: String, saltB64: String): String {
        val salt = Base64.getDecoder().decode(saltB64)
        val pbkdf2 = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(secret.toCharArray(), salt, iterations, 256)).encoded
        return Base64.getEncoder().encodeToString(signer.sign(pbkdf2))
    }

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    private fun salt() = Base64.getEncoder().encodeToString(ByteArray(16).also { rng.nextBytes(it) })
    private fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }

    private fun newRecoveryCode() =
        (1..16).map { ALPHABET[rng.nextInt(ALPHABET.length)] }.chunked(4).joinToString("-") { it.joinToString("") }

    companion object {
        const val MAX_FREE_TRIES = 5
        // shortcut: wall-clock lockout, so changing the phone clock can shorten it; switch to elapsedRealtime if that is seen
        const val BASE_LOCKOUT_MS = 60_000L
        const val MAX_LOCKOUT_MS = 15 * 60_000L
        private val PIN_RE = Regex("\\d{4}")
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val rng = SecureRandom()
        private const val PIN_SALT = "pin_salt"
        private const val PIN_HASH = "pin_hash"
        private const val REC_SALT = "rec_salt"
        private const val REC_HASH = "rec_hash"
        private const val FAILS = "fails"
        private const val UNTIL = "until"
    }
}
