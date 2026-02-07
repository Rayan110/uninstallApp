package com.example.uninstallapp

import android.graphics.drawable.Drawable

data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val size: String,
    val sizeBytes: Long = 0L,
    var isWhitelisted: Boolean = true,  // 是否在白名单中（保留）
    val isSystemApp: Boolean = false    // 是否是系统应用
)
