package com.example.uninstallapp

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import info.mqtt.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*

class MqttManager(
    private val context: Context,
    private val deviceManager: DeviceManager
) {

    companion object {
        private const val TAG = "MqttManager"
        private const val BROKER_URL = "tcp://broker-cn.emqx.io:1883"
        private const val TOPIC_PREFIX = "uninstall"
    }

    private var mqttClient: MqttAndroidClient? = null
    private val gson = Gson()
    private var callback: MqttCallback? = null
    private var isPairingMode = false
    private var pendingPairingCode: String? = null

    interface MqttCallback {
        fun onConnected()
        fun onDisconnected()
        fun onPaired()
        fun onWhitelistReceived(packages: List<String>)
        fun onCommandReceived(command: String)
        fun onConnectionError(error: String)
    }

    fun setCallback(callback: MqttCallback) {
        this.callback = callback
    }

    fun connect() {
        // Avoid duplicate connections
        if (mqttClient?.isConnected == true) {
            Log.d(TAG, "MQTT already connected, skipping connect")
            callback?.onConnected()
            return
        }
        // Clean up old client if exists
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (_: Exception) {}
        mqttClient = null

        val deviceId = deviceManager.getDeviceId()
        val clientId = "android_$deviceId"

        mqttClient = MqttAndroidClient(context, BROKER_URL, clientId).apply {
            setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.d(TAG, "Connected to MQTT broker (reconnect=$reconnect)")
                    if (deviceManager.isPaired()) {
                        subscribeToCommands()
                    }
                    // Handle pending pairing subscription
                    pendingPairingCode?.let { code ->
                        pendingPairingCode = null
                        startPairing(code)
                    }
                    callback?.onConnected()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT connection lost", cause)
                    callback?.onDisconnected()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    handleMessage(topic, message)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
        }

        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            keepAliveInterval = 30
            connectionTimeout = 10
            maxInflight = 100
        }

        try {
            mqttClient?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "MQTT connect onSuccess")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "MQTT connect failed", exception)
                    callback?.onConnectionError(exception?.message ?: "Connection failed")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "MQTT connect exception", e)
            callback?.onConnectionError(e.message ?: "Connection exception")
        }
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            mqttClient = null
        } catch (e: Exception) {
            Log.e(TAG, "MQTT disconnect error", e)
        }
    }

    fun isConnected(): Boolean {
        return mqttClient?.isConnected == true
    }

    fun startPairing(code: String) {
        isPairingMode = true
        val deviceId = deviceManager.getDeviceId()
        val pairingTopic = "$TOPIC_PREFIX/pair/$code"

        // Subscribe to pairing topic to detect manager connection
        if (isConnected()) {
            try {
                mqttClient?.subscribe(pairingTopic, 1, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d(TAG, "Subscribed to pairing topic: $pairingTopic")
                    }
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.e(TAG, "Failed to subscribe pairing topic", exception)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error subscribing to pairing topic", e)
            }
        } else {
            Log.w(TAG, "MQTT not connected yet, will subscribe after connection")
            // Store pairing code so we can subscribe after connection
            pendingPairingCode = code
        }

        // Publish pairing response as retained message
        val responseTopic = "$TOPIC_PREFIX/pair/$code/response"
        val pairingData = mapOf(
            "deviceId" to deviceId,
            "deviceName" to deviceManager.getDeviceName()
        )
        val payload = gson.toJson(pairingData)
        val message = MqttMessage(payload.toByteArray()).apply {
            qos = 1
            isRetained = true
        }

        if (isConnected()) {
            try {
                mqttClient?.publish(responseTopic, message, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d(TAG, "Published pairing response")
                    }
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.e(TAG, "Failed to publish pairing response", exception)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Error publishing pairing response", e)
            }
        }
    }

    fun stopPairing(code: String) {
        isPairingMode = false
        val pairingTopic = "$TOPIC_PREFIX/pair/$code"
        try {
            mqttClient?.unsubscribe(pairingTopic)
            // Clear retained pairing response
            val responseTopic = "$TOPIC_PREFIX/pair/$code/response"
            val emptyMessage = MqttMessage(ByteArray(0)).apply {
                qos = 1
                isRetained = true
            }
            mqttClient?.publish(responseTopic, emptyMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping pairing", e)
        }
    }

    fun subscribeToCommands() {
        if (!isConnected()) {
            Log.w(TAG, "MQTT not connected, skipping command subscription")
            return
        }
        val deviceId = deviceManager.getDeviceId()
        val commandTopic = "$TOPIC_PREFIX/$deviceId/command"
        val whitelistTopic = "$TOPIC_PREFIX/$deviceId/whitelist/set"

        try {
            mqttClient?.subscribe(arrayOf(commandTopic, whitelistTopic), intArrayOf(1, 1), null,
                object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        Log.d(TAG, "Subscribed to command topics")
                    }
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.e(TAG, "Failed to subscribe to command topics", exception)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to command topics", e)
        }
    }

    fun publishAppList() {
        val deviceId = deviceManager.getDeviceId()
        val topic = "$TOPIC_PREFIX/$deviceId/apps"

        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val whitelist = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
            .getStringSet("whitelist", emptySet()) ?: emptySet()

        val apps = packages
            .filter { it.packageName != context.packageName }
            .map { appInfo ->
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val size = try {
                    val file = java.io.File(appInfo.sourceDir)
                    formatFileSize(file.length())
                } catch (e: Exception) {
                    "Unknown"
                }
                mapOf(
                    "name" to pm.getApplicationLabel(appInfo).toString(),
                    "packageName" to appInfo.packageName,
                    "size" to size,
                    "isSystem" to isSystem,
                    "isWhitelisted" to (isSystem || whitelist.contains(appInfo.packageName))
                )
            }

        val payload = gson.toJson(mapOf(
            "timestamp" to System.currentTimeMillis(),
            "apps" to apps
        ))

        publish(topic, payload, qos = 1)
    }

    fun publishWhitelist() {
        val deviceId = deviceManager.getDeviceId()
        val topic = "$TOPIC_PREFIX/$deviceId/whitelist/current"
        val whitelist = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
            .getStringSet("whitelist", emptySet()) ?: emptySet()

        val payload = gson.toJson(mapOf(
            "packages" to whitelist.toList(),
            "timestamp" to System.currentTimeMillis()
        ))

        publish(topic, payload, qos = 1)
    }

    fun publishStatus() {
        val deviceId = deviceManager.getDeviceId()
        val topic = "$TOPIC_PREFIX/$deviceId/status"

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0

        val whitelist = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
            .getStringSet("whitelist", emptySet()) ?: emptySet()
        val pm = context.packageManager
        val userApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != context.packageName }
        val pendingUninstall = userApps.count { !whitelist.contains(it.packageName) }

        val payload = gson.toJson(mapOf(
            "online" to true,
            "battery" to battery,
            "paired" to deviceManager.isPaired(),
            "pendingUninstall" to pendingUninstall,
            "timestamp" to System.currentTimeMillis()
        ))

        publish(topic, payload, qos = 1, retained = true)
    }

    private fun handleMessage(topic: String?, message: MqttMessage?) {
        if (topic == null || message == null) return
        val payload = String(message.payload)
        Log.d(TAG, "Message received: topic=$topic, payload=$payload")

        val deviceId = deviceManager.getDeviceId()

        when {
            // Pairing: manager subscribed to our pairing topic
            topic.startsWith("$TOPIC_PREFIX/pair/") && !topic.endsWith("/response") -> {
                // Manager is requesting pairing
                if (isPairingMode) {
                    deviceManager.savePairedStatus()
                    isPairingMode = false
                    subscribeToCommands()
                    callback?.onPaired()
                }
            }

            // Whitelist set command
            topic == "$TOPIC_PREFIX/$deviceId/whitelist/set" -> {
                try {
                    val data = gson.fromJson<Map<String, Any>>(payload, object : TypeToken<Map<String, Any>>() {}.type)
                    @Suppress("UNCHECKED_CAST")
                    val packages = data["packages"] as? List<String> ?: return
                    callback?.onWhitelistReceived(packages)
                    // Update local whitelist
                    val prefs = context.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putStringSet("whitelist", packages.toSet()).apply()
                    // Report back current whitelist
                    publishWhitelist()
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing whitelist", e)
                }
            }

            // Command
            topic == "$TOPIC_PREFIX/$deviceId/command" -> {
                try {
                    val data = gson.fromJson<Map<String, String>>(payload, object : TypeToken<Map<String, String>>() {}.type)
                    val command = data["command"] ?: return
                    callback?.onCommandReceived(command)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing command", e)
                }
            }
        }
    }

    private fun publish(topic: String, payload: String, qos: Int = 1, retained: Boolean = false) {
        if (!isConnected()) {
            Log.w(TAG, "MQTT not connected, skipping publish to $topic")
            return
        }
        try {
            val message = MqttMessage(payload.toByteArray()).apply {
                this.qos = qos
                this.isRetained = retained
            }
            mqttClient?.publish(topic, message)
            Log.d(TAG, "Published to $topic (${payload.length} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Publish error: $topic", e)
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
            size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024))
            else -> String.format("%.1f GB", size / (1024.0 * 1024 * 1024))
        }
    }
}
