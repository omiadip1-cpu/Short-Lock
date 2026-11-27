package com.shortslock.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

class KeystoreSigner(private val alias: String = "shortslock_pin_hmac") : Signer {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            .apply { init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build()) }
            .generateKey()
    }

    override fun sign(data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(key()) }.doFinal(data)
}

class PrefsStore(context: Context) : Store {
    private val prefs = context.getSharedPreferences("pin", Context.MODE_PRIVATE)
    override fun get(key: String) = prefs.getString(key, null)
    override fun put(vararg kv: Pair<String, String?>): Boolean =
        prefs.edit().apply { kv.forEach { (k, v) -> if (v == null) remove(k) else putString(k, v) } }.commit()
}
