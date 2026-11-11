package dev.hermes.wearos.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * KEYSTORE-BACKED WATCH SECRETS (D15): the gateway token, the tailnet authkey, the watchkit
 * bearer, the device token and the live pairing secret never sit in DataStore as plaintext.
 * Each value is sealed with a device-bound AES-256-GCM key that lives in the Android
 * Keystore — exported=false, so a `adb pull` of the datastore (or a stolen .ab, though the
 * manifest already says allowBackup=false) yields ciphertext, not credentials.
 *
 * Wire format in the store: `ks1:` + base64(IV[12] || ciphertext||tag). Anything WITHOUT
 * that prefix is a legacy plaintext value: readers return it verbatim AND the caller
 * re-seals it on the spot (lazy migration — an upgraded watch encrypts its old secrets on
 * first touch, and a downgrade still reads them, since the raw value was never removed…
 * it was replaced; a downgrade therefore just re-prompts, never bricks).
 *
 * A keystore failure (corrupt key, rare on factory reset) DEGRADES TO PLAINTEXT rather than
 * losing the secret: the value still round-trips, just unsealed — loud in logcat, never a
 * silent auth break.
 */
object KeystoreSecrets {
    private const val ENTRY = "hermes-wearos-secrets"
    private const val PREFIX = "ks1:"

    fun seal(context: Context, plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray())
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        }.getOrElse {
            android.util.Log.w("KeystoreSecrets", "seal failed — storing plaintext this time: ${it.message}")
            plain
        }
    }

    fun unseal(context: Context, stored: String): String {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return stored
        return runCatching {
            val blob = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(cipher.doFinal(blob, 12, blob.size - 12))
        }.getOrElse {
            android.util.Log.w("KeystoreSecrets", "unseal failed — secret may need re-pairing: ${it.message}")
            ""
        }
    }

    /** True when [stored] still needs re-sealing (legacy plaintext or a degraded seal). */
    fun needsSeal(stored: String): Boolean = stored.isNotEmpty() && !stored.startsWith(PREFIX)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ENTRY, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ENTRY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Suppress("UNUSED_PARAMETER") private fun ctx(c: Context) = c // seam kept for future per-user keys
}
