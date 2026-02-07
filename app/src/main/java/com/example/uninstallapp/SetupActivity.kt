package com.example.uninstallapp

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.uninstallapp.databinding.ActivitySetupBinding

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var deviceManager: DeviceManager
    private var mqttService: MqttService? = null
    private var serviceBound = false
    private var pairingCode: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var pairingCheckRunnable: Runnable? = null
    private var pairingTimeoutRunnable: Runnable? = null
    private companion object {
        private const val PAIRING_CHECK_INTERVAL = 3000L // 3 seconds
        private const val PAIRING_TIMEOUT = 120_000L // 2 minutes
    }

    private val pairedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            onPairingSuccess()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MqttService.LocalBinder
            mqttService = binder.getService()
            serviceBound = true
            startPairing()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mqttService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceManager = DeviceManager(this)
        pairingCode = deviceManager.getPairingCode()

        // Display pairing code
        binding.tvPairingCode.text = pairingCode

        // Skip pairing button
        binding.btnSkipPairing.setOnClickListener {
            goToMain()
        }

        // Register paired broadcast receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pairedReceiver, IntentFilter("com.example.uninstallapp.PAIRED"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pairedReceiver, IntentFilter("com.example.uninstallapp.PAIRED"))
        }

        // Start polling to check paired status (fallback for broadcast)
        startPairingCheck()

        // Start and bind to MQTT service
        val serviceIntent = Intent(this, MqttService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        stopPairingCheck()
        handler.removeCallbacksAndMessages(null)
        if (serviceBound) {
            mqttService?.getMqttManager()?.stopPairing(pairingCode)
            unbindService(serviceConnection)
            serviceBound = false
        }
        try { unregisterReceiver(pairedReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startPairing() {
        val mqttManager = mqttService?.getMqttManager() ?: return
        binding.tvConnectionStatus.text = "已连接，等待管理端配对..."
        binding.progressPairing.visibility = View.VISIBLE
        mqttManager.startPairing(pairingCode)
    }

    private fun startPairingCheck() {
        stopPairingCheck()
        pairingCheckRunnable = object : Runnable {
            override fun run() {
                if (deviceManager.isPaired()) {
                    onPairingSuccess()
                } else if (!isDestroyed && !isFinishing) {
                    handler.postDelayed(this, PAIRING_CHECK_INTERVAL)
                }
            }
        }
        handler.postDelayed(pairingCheckRunnable!!, PAIRING_CHECK_INTERVAL)

        // Set pairing timeout
        pairingTimeoutRunnable = Runnable {
            if (!deviceManager.isPaired() && !isDestroyed && !isFinishing) {
                binding.tvConnectionStatus.text = "配对超时，请确认管理端已输入配对码"
                binding.tvConnectionStatus.setTextColor(0xFFFF9800.toInt())
                binding.progressPairing.visibility = View.GONE
                // Continue checking but slower
            }
        }
        handler.postDelayed(pairingTimeoutRunnable!!, PAIRING_TIMEOUT)
    }

    private fun stopPairingCheck() {
        pairingCheckRunnable?.let { handler.removeCallbacks(it) }
        pairingCheckRunnable = null
        pairingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pairingTimeoutRunnable = null
    }

    private fun onPairingSuccess() {
        stopPairingCheck()
        binding.tvConnectionStatus.text = "配对成功！"
        binding.tvConnectionStatus.setTextColor(0xFF4CAF50.toInt())
        binding.progressPairing.visibility = View.GONE

        binding.root.postDelayed({
            goToMain()
        }, 1000)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
