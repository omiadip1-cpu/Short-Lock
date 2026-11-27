package com.shortslock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PinManagerTest {
    private class MapStore : Store {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(vararg kv: Pair<String, String?>) {
            kv.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
        }
    }

    private val signer = object : Signer {
        override fun sign(data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")) }.doFinal(data)
    }
    private var t = 1_000_000L
    private val store = MapStore()
    private val pm = PinManager(store, signer, { t }, iterations = 1000)

    @Test fun correctPinWorksAndNothingStoredInPlain() {
        pm.setPin("1234")
        assertTrue(pm.isSet())
        assertEquals(PinManager.Result.Ok, pm.verify("1234"))
        assertFalse(store.m.values.any { it.contains("1234") })
    }

    @Test fun rejectsBadPinFormat() {
        for (p in listOf("123", "12345", "12a4", "")) {
            assertThrows(IllegalArgumentException::class.java) { pm.setPin(p) }
        }
    }

    @Test fun recoveryCodeFormat() {
        assertTrue(Regex("[A-Z2-9]{4}(-[A-Z2-9]{4}){3}").matches(pm.setPin("1234")))
    }

    @Test fun wrongPinCountsDownThenLocksAndRefusesEvenCorrectPin() {
        pm.setPin("1234")
        for (left in 4 downTo 1) assertEquals(PinManager.Result.Wrong(left), pm.verify("0000"))
        assertEquals(PinManager.Result.LockedOut(60_000), pm.verify("0000"))
        assertTrue(pm.verify("1234") is PinManager.Result.LockedOut)
    }

    @Test fun lockoutExpiresThenDoublesAndCaps() {
        pm.setPin("1234")
        repeat(5) { pm.verify("0000") }
        t += 60_000
        assertEquals(PinManager.Result.LockedOut(120_000), pm.verify("0000"))
        t += 120_000
        assertEquals(PinManager.Result.LockedOut(240_000), pm.verify("0000"))
        repeat(10) { t += 900_000; pm.verify("0000") }
        t += 900_000
        assertEquals(PinManager.Result.LockedOut(900_000), pm.verify("0000"))
    }

    @Test fun correctPinResetsFailureCount() {
        pm.setPin("1234")
        repeat(3) { pm.verify("0000") }
        assertEquals(PinManager.Result.Ok, pm.verify("1234"))
        assertEquals(PinManager.Result.Wrong(4), pm.verify("0000"))
    }

    @Test fun recoveryCodeResetsPinAndLockout() {
        val code = pm.setPin("1234")
        repeat(5) { pm.verify("0000") }
        assertFalse(pm.resetWithRecovery("AAAA-AAAA-AAAA-AAAA", "9999"))
        assertTrue(pm.resetWithRecovery(code.lowercase().replace("-", " "), "9999"))
        assertEquals(PinManager.Result.Ok, pm.verify("9999"))
        assertTrue(pm.verify("1234") is PinManager.Result.Wrong)
    }

    @Test fun differentKeyCannotVerify() {
        pm.setPin("1234")
        val other = PinManager(store, object : Signer { override fun sign(data: ByteArray) = data }, { t }, 1000)
        assertTrue(other.verify("1234") is PinManager.Result.Wrong)
    }
}
