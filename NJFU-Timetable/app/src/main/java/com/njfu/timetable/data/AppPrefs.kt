package com.njfu.timetable.data

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/**
 * 凭据加密：使用系统 Keystore 生成不可导出的 AES 密钥，密码不落明文。
 */
object Crypto {
    private const val ALIAS = "njfu_timetable_secret"
    private const val STORE = "AndroidKeyStore"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(1 + iv.size + body.size)
            out[0] = iv.size.toByte()
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(body, 0, out, 1 + iv.size, body.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    fun decrypt(data: String): String {
        if (data.isEmpty()) return ""
        return try {
            val all = Base64.decode(data, Base64.NO_WRAP)
            val ivLen = all[0].toInt()
            val iv = all.copyOfRange(1, 1 + ivLen)
            val body = all.copyOfRange(1 + ivLen, all.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}

class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // 老版本存的是已下线的 jwk 地址，这里静默迁移到新教务。
        val saved = sp.getString("baseUrl", "") ?: ""
        if (saved.isBlank() || saved.contains("jwk.") || saved.contains("10.0.2.2")) {
            sp.edit().putString("baseUrl", DEFAULT_BASE).apply()
        }
    }

    var baseUrl: String
        get() = sp.getString("baseUrl", DEFAULT_BASE) ?: DEFAULT_BASE
        set(value) = sp.edit().putString("baseUrl", value.trim().trimEnd('/')).apply()

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(value) = sp.edit().putString("username", value.trim()).apply()

    var password: String
        get() = Crypto.decrypt(sp.getString("password", "") ?: "")
        set(value) = sp.edit().putString("password", Crypto.encrypt(value)).apply()

    var cookies: String
        get() = Crypto.decrypt(sp.getString("cookies", "") ?: "")
        set(value) = sp.edit().putString("cookies", Crypto.encrypt(value)).apply()

    /** 统一身份认证的 CASTGC，用于教务会话过期后静默换票。 */
    var casCookies: String
        get() = Crypto.decrypt(sp.getString("casCookies", "") ?: "")
        set(value) = sp.edit().putString("casCookies", Crypto.encrypt(value)).apply()

    var termStart: String
        get() = sp.getString("termStart", "2026-08-31") ?: "2026-08-31"
        set(value) = sp.edit().putString("termStart", value.trim()).apply()

    /** 上课时间按哪个校区的作息表换算：白马 / 新庄 / 淮安。 */
    var campus: String
        get() = sp.getString("campus", "白马") ?: "白马"
        set(value) = sp.edit().putString("campus", value.trim()).apply()

    var termName: String
        get() = sp.getString("termName", "2026-2027 秋季学期") ?: "2026-2027 秋季学期"
        set(value) = sp.edit().putString("termName", value.trim()).apply()

    var dayCount: Int
        get() = sp.getInt("dayCount", 7)
        set(value) = sp.edit().putInt("dayCount", value).apply()

    var lastSync: Long
        get() = sp.getLong("lastSync", 0L)
        set(value) = sp.edit().putLong("lastSync", value).apply()

    var needsLogin: Boolean
        get() = sp.getBoolean("needsLogin", false)
        set(value) = sp.edit().putBoolean("needsLogin", value).apply()

    var studentName: String
        get() = sp.getString("studentName", "") ?: ""
        set(value) = sp.edit().putString("studentName", value).apply()

    /** 上课提醒：默认开启，提前 25 分钟，只震动。 */
    var remindEnabled: Boolean
        get() = sp.getBoolean("remindEnabled", true)
        set(value) = sp.edit().putBoolean("remindEnabled", value).apply()

    var remindMinutes: Int
        get() = sp.getInt("remindMinutes", 25)
        set(value) = sp.edit().putInt("remindMinutes", value.coerceIn(1, 180)).apply()

    /** 提醒方式：vibrate / notif / alarm / ringtone，默认只震动。 */
    var remindTone: String
        get() = sp.getString("remindTone", "")?.takeIf { it.isNotEmpty() }
            ?: if (sp.getBoolean("remindSound", false)) "notif" else "vibrate"
        set(value) = sp.edit().putString("remindTone", value).apply()

    companion object {
        const val DEFAULT_BASE = "https://jwxt.njfu.edu.cn"
    }
}
