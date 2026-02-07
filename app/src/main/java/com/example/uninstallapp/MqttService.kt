package com.example.uninstallapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class MqttService : Service() {

    companion object {
        private const val TAG = "MqttService"
        private const val CHANNEL_ID = "mqtt_service_channel"
        private const val NOTIFICATION_ID = 1
        private const val STATUS_INTERVAL = 60_000L // 1 minute
        const val ACTION_UNINSTALL = "com.example.uninstallapp.ACTION_UNINSTALL"
        const val ACTION_REFRESH = "com.example.uninstallapp.ACTION_REFRESH"
    }

    private lateinit var mqttManager: MqttManager
    private lateinit var deviceManager: DeviceManager
    private val handler = Handler(Looper.getMainLooper())
    private val binder = LocalBinder()
    private var statusRunnable: Runnable? = null

    inner class LocalBinder : Binder() {
        fun getService(): MqttService = this@MqttService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        deviceManager = DeviceManager(this)
        mqttManager = MqttManager(this, deviceManager)

        mqttManager.setCallback(object : MqttManager.MqttCallback {
            override fun onConnected() {
                Log.d(TAG, "MQTT connected")
                updateNotification("远程管理已连接")
                // Publish initial status and app list
                handler.post {
                    mqttManager.publishStatus()
                    if (deviceManager.isPaired()) {
                        mqttManager.publishAppList()
                    }
                }
                startStatusReporting()
            }

            override fun onDisconnected() {
                Log.d(TAG, "MQTT disconnected")
                updateNotification("远程管理已断开，重连中...")
                stopStatusReporting()
            }

            override fun onPaired() {
                Log.d(TAG, "Device paired")
                updateNotification("远程管理已连接")
                sendBroadcast(Intent("com.example.uninstallapp.PAIRED").setPackage(packageName))
                // Immediately publish status and app list after pairing
                handler.postDelayed({
                    mqttManager.publishStatus()
                    mqttManager.publishAppList()
                    mqttManager.publishWhitelist()
                }, 500)
                startStatusReporting()
            }

            override fun onWhitelistReceived(packages: List<String>) {
                Log.d(TAG, "Whitelist received: ${packages.size} packages")
                sendBroadcast(Intent("com.example.uninstallapp.WHITELIST_UPDATED").setPackage(packageName))
                // Notify user that whitelist was remotely updated
                updateNotification("白名单已被远程更新 (${packages.size} 个)")
                handler.postDelayed({ updateNotification("远程管理已连接") }, 5000)
            }

            override fun onCommandReceived(command: String) {
                Log.d(TAG, "Command received: $command")
                // Send ACK immediately
                mqttManager.publishCommandAck(command, "received")

                when (command) {
                    "uninstall" -> {
                        val shizuku = ShizukuUninstaller(this@MqttService)
                        if (shizuku.isAvailable() && shizuku.isPermissionGranted()) {
                            val packages = shizuku.getPackagesToUninstall()
                            if (packages.isNotEmpty()) {
                                Log.d(TAG, "Shizuku silent uninstall: ${packages.size} packages")
                                mqttManager.publishCommandAck(command, "started", "${packages.size} packages")
                                shizuku.uninstallPackages(packages, object : ShizukuUninstaller.UninstallCallback {
                                    override fun onProgress(packageName: String, appName: String, current: Int, total: Int) {
                                        Log.d(TAG, "Uninstalling $current/$total: $appName")
                                        mqttManager.publishUninstallProgress(current, total, appName, true)
                                    }
                                    override fun onResult(packageName: String, success: Boolean, message: String) {
                                        Log.d(TAG, message)
                                    }
                                    override fun onComplete(successCount: Int, failCount: Int) {
                                        Log.d(TAG, "Uninstall done: $successCount ok, $failCount failed")
                                        mqttManager.publishCommandAck(command, "completed",
                                            "success=$successCount, failed=$failCount")
                                        mqttManager.publishStatus()
                                        mqttManager.publishAppList()
                                    }
                                })
                            } else {
                                Log.d(TAG, "No packages to uninstall")
                                mqttManager.publishCommandAck(command, "completed", "No packages to uninstall")
                            }
                        } else {
                            // Fallback: launch activity for accessibility-based uninstall
                            Log.d(TAG, "Shizuku not available, falling back to accessibility")
                            mqttManager.publishCommandAck(command, "fallback", "Shizuku not available, using accessibility")
                            val intent = Intent(this@MqttService, MainActivity::class.java).apply {
                                action = ACTION_UNINSTALL
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            startActivity(intent)
                        }
                    }
                    "refresh" -> {
                        mqttManager.publishAppList()
                        mqttManager.publishWhitelist()
                        mqttManager.publishStatus()
                        mqttManager.publishCommandAck(command, "completed")
                    }
                    "unpair" -> {
                        deviceManager.clearPairedStatus()
                        sendBroadcast(Intent("com.example.uninstallapp.UNPAIRED").setPackage(packageName))
                        mqttManager.publishCommandAck(command, "completed")
                    }
                }
            }

            override fun onConnectionError(error: String) {
                Log.e(TAG, "Connection error: $error")
                updateNotification("连接失败: $error")
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification("远程管理服务运行中")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: use ServiceCompat with explicit foreground service type
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            // Fallback: run as regular service
        }

        try {
            mqttManager.connect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect MQTT", e)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stopStatusReporting()
        try {
            mqttManager.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting MQTT", e)
        }
        super.onDestroy()
    }

    fun getMqttManager(): MqttManager = mqttManager
    fun getDeviceManager(): DeviceManager = deviceManager

    private fun startStatusReporting() {
        stopStatusReporting()
        statusRunnable = object : Runnable {
            override fun run() {
                if (mqttManager.isConnected() && deviceManager.isPaired()) {
                    mqttManager.publishStatus()
                }
                handler.postDelayed(this, STATUS_INTERVAL)
            }
        }
        handler.postDelayed(statusRunnable!!, STATUS_INTERVAL)
    }

    private fun stopStatusReporting() {
        statusRunnable?.let { handler.removeCallbacks(it) }
        statusRunnable = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "远程管理服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持远程管理连接"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("应用卸载器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val notification = buildNotification(text)
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }
}
