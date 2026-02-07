package com.example.uninstallapp

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku

class ShizukuUninstaller(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuUninstaller"
        const val REQUEST_CODE = 1001
        private val VALID_PACKAGE = Regex("""^[a-zA-Z][a-zA-Z0-9._]*$""")
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

    /**
     * Execute a shell command via Shizuku.newProcess() using reflection.
     * This avoids bindUserService which fails on Android 16 due to SELinux
     * blocking shell context from loading app APKs.
     * newProcess is @RestrictTo(LIBRARY_GROUP_PREFIX) so we use reflection.
     */
    private fun execShellCommand(command: String): Pair<Int, String> {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Pair(exitCode, "$output$error".trim())
        } catch (e: Exception) {
            Log.e(TAG, "execShellCommand failed: $command", e)
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    fun uninstallPackages(packages: List<String>, callback: UninstallCallback?) {
        val mainHandler = Handler(Looper.getMainLooper())

        Thread {
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

                if (!VALID_PACKAGE.matches(pkg)) {
                    Log.e(TAG, "Invalid package name: $pkg")
                    failCount++
                    mainHandler.post { callback?.onResult(pkg, false, "$appName 包名无效") }
                    continue
                }

                val (exitCode, output) = execShellCommand("pm uninstall --user 0 $pkg")
                val result = exitCode == 0 && output.contains("Success")
                Log.d(TAG, "Uninstall $pkg: exitCode=$exitCode, output=$output")

                if (result) {
                    successCount++
                    mainHandler.post { callback?.onResult(pkg, true, "$appName 卸载成功") }
                } else {
                    failCount++
                    mainHandler.post { callback?.onResult(pkg, false, "$appName 卸载失败") }
                }

                Thread.sleep(200)
            }

            val s = successCount
            val f = failCount
            mainHandler.post { callback?.onComplete(s, f) }
        }.start()
    }
}
