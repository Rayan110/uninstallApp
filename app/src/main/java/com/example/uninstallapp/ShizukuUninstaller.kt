package com.example.uninstallapp

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ShizukuUninstaller(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuUninstaller"
        const val REQUEST_CODE = 1001
    }

    interface UninstallCallback {
        fun onProgress(packageName: String, appName: String, current: Int, total: Int)
        fun onResult(packageName: String, success: Boolean, message: String)
        fun onComplete(successCount: Int, failCount: Int)
    }

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        return try {
            if (!isAvailable()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    fun getPackagesToUninstall(): List<String> {
        val whitelist = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
            .getStringSet("whitelist", emptySet()) ?: emptySet()
        val pm = context.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                it.packageName != context.packageName &&
                !whitelist.contains(it.packageName)
            }
            .map { it.packageName }
    }

    fun uninstallPackages(packages: List<String>, callback: UninstallCallback?) {
        val mainHandler = Handler(Looper.getMainLooper())

        Thread {
            // Bind user service
            val latch = CountDownLatch(1)
            var service: IUserService? = null

            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = IUserService.Stub.asInterface(binder)
                    latch.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                }
            }

            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, UserService::class.java.name)
            ).daemon(false).processNameSuffix("uninstall").version(1)

            try {
                mainHandler.post { Shizuku.bindUserService(args, conn) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind Shizuku service", e)
                mainHandler.post { callback?.onComplete(0, packages.size) }
                return@Thread
            }

            if (!latch.await(10, TimeUnit.SECONDS)) {
                Log.e(TAG, "Timeout waiting for Shizuku service")
                mainHandler.post { callback?.onComplete(0, packages.size) }
                return@Thread
            }

            // Execute uninstalls
            var successCount = 0
            var failCount = 0
            val pm = context.packageManager

            for ((index, pkg) in packages.withIndex()) {
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }

                mainHandler.post {
                    callback?.onProgress(pkg, appName, index + 1, packages.size)
                }

                val result = try {
                    val output = service?.execCommand("pm uninstall --user 0 $pkg") ?: "-1\nService null"
                    Log.d(TAG, "Uninstall $pkg: $output")
                    output.startsWith("0") && output.contains("Success")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to uninstall $pkg", e)
                    false
                }

                if (result) {
                    successCount++
                    mainHandler.post { callback?.onResult(pkg, true, "$appName 卸载成功") }
                } else {
                    failCount++
                    mainHandler.post { callback?.onResult(pkg, false, "$appName 卸载失败") }
                }

                Thread.sleep(200)
            }

            // Unbind service
            try {
                mainHandler.post { Shizuku.unbindUserService(args, conn, true) }
            } catch (_: Exception) {}

            val s = successCount
            val f = failCount
            mainHandler.post { callback?.onComplete(s, f) }
        }.start()
    }
}
