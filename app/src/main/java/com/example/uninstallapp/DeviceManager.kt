package com.example.uninstallapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.security.SecureRandom
import java.util.UUID

class DeviceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "device_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PAIRING_CODE = "pairing_code"
        private const val KEY_PAIRED = "is_paired"
    }

    fun getDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getPairingCode(): String {
        var code = prefs.getString(KEY_PAIRING_CODE, null)
        if (code == null || !isPaired()) {
            code = String.format("%06d", SecureRandom().nextInt(1000000))
            prefs.edit().putString(KEY_PAIRING_CODE, code).apply()
        }
        return code
    }

    fun isPaired(): Boolean {
        return prefs.getBoolean(KEY_PAIRED, false)
    }

    fun savePairedStatus() {
        prefs.edit().putBoolean(KEY_PAIRED, true).apply()
    }

    fun clearPairedStatus() {
        prefs.edit()
            .putBoolean(KEY_PAIRED, false)
            .remove(KEY_PAIRING_CODE)
            .apply()
    }

    fun getDeviceName(): String {
        val brand = Build.BRAND.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(brand, ignoreCase = true)) {
            model
        } else {
            "$brand $model"
        }
    }
}
