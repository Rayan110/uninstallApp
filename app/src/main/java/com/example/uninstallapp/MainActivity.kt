package com.example.uninstallapp

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.uninstallapp.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var deviceManager: DeviceManager
    private lateinit var shizukuUninstaller: ShizukuUninstaller

    // 待卸载队列 (accessibility fallback)
    private var uninstallQueue: MutableList<String> = mutableListOf()
    private var isUninstalling = false
    private var currentUninstallPackage: String? = null
    private val handler = Handler(Looper.getMainLooper())

    // MQTT service binding
    private var mqttService: MqttService? = null
    private var serviceBound = false

    companion object {
        private const val PREFS_NAME = "whitelist_prefs"
        private const val KEY_WHITELIST = "whitelist"
    }

    // Shizuku listeners
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateUninstallModeStatus() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { updateUninstallModeStatus() }
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == ShizukuUninstaller.REQUEST_CODE) {
            runOnUiThread { updateUninstallModeStatus() }
        }
    }

    private val whitelistReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateStats()
        }
    }

    private val unpairReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            goToSetup()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MqttService.LocalBinder
            mqttService = binder.getService()
            serviceBound = true
            updateConnectionStatus()
            startConnectionStatusCheck()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mqttService = null
            serviceBound = false
            updateConnectionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        deviceManager = DeviceManager(this)
        shizukuUninstaller = ShizukuUninstaller(this)

        // Check pairing status — redirect to setup if not paired
        if (!deviceManager.isPaired()) {
            goToSetup()
            return
        }

        // Register Shizuku listeners
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {}

        setupUI()
        startMqttService()

        // Handle remote uninstall command via intent
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == MqttService.ACTION_UNINSTALL) {
            handler.postDelayed({
                startBatchUninstall()
            }, 500)
        }
    }

    private fun setupUI() {
        // Display device ID
        val deviceId = deviceManager.getDeviceId()
        binding.tvDeviceId.text = "ID: ${deviceId.take(8)}..."

        updateUninstallModeStatus()
        updateStats()

        // 一键卸载按钮
        binding.btnUninstallAll.setOnClickListener {
            startBatchUninstall()
        }

        // 状态卡片按钮 (Shizuku权限 / 无障碍开启)
        binding.btnEnableAccessibility.setOnClickListener {
            if (shizukuUninstaller.isAvailable()) {
                if (!shizukuUninstaller.isPermissionGranted()) {
                    shizukuUninstaller.requestPermission()
                }
            } else {
                openAccessibilitySettings()
            }
        }

        // 管理白名单按钮
        binding.btnManageWhitelist.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 重新配对按钮
        binding.btnRepair.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("重新配对")
                .setMessage("确定要清除当前配对状态并重新配对吗？")
                .setPositiveButton("确定") { _, _ ->
                    deviceManager.clearPairedStatus()
                    goToSetup()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // Register broadcast receivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(whitelistReceiver, IntentFilter("com.example.uninstallapp.WHITELIST_UPDATED"), RECEIVER_NOT_EXPORTED)
            registerReceiver(unpairReceiver, IntentFilter("com.example.uninstallapp.UNPAIRED"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(whitelistReceiver, IntentFilter("com.example.uninstallapp.WHITELIST_UPDATED"))
            registerReceiver(unpairReceiver, IntentFilter("com.example.uninstallapp.UNPAIRED"))
        }
    }

    private fun startMqttService() {
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

    private fun goToSetup() {
        startActivity(Intent(this, SetupActivity::class.java))
        finish()
    }

    private fun updateConnectionStatus() {
        val isConnected = mqttService?.getMqttManager()?.isConnected() == true

        if (isConnected) {
            binding.connectionStatusBar.setBackgroundColor(0xFF4CAF50.toInt())
            binding.tvConnectionStatus.text = "远程管理：已连接"
        } else {
            binding.connectionStatusBar.setBackgroundColor(0xFFF44336.toInt())
            binding.tvConnectionStatus.text = "远程管理：未连接"
        }
    }

    private var connectionCheckRunnable: Runnable? = null

    private fun startConnectionStatusCheck() {
        stopConnectionStatusCheck()
        connectionCheckRunnable = object : Runnable {
            override fun run() {
                if (!isDestroyed && !isFinishing) {
                    updateConnectionStatus()
                    handler.postDelayed(this, 5000)
                }
            }
        }
        handler.postDelayed(connectionCheckRunnable!!, 5000)
    }

    private fun stopConnectionStatusCheck() {
        connectionCheckRunnable?.let { handler.removeCallbacks(it) }
        connectionCheckRunnable = null
    }

    private fun updateUninstallModeStatus() {
        val shizukuAvailable = shizukuUninstaller.isAvailable()
        val shizukuGranted = shizukuAvailable && shizukuUninstaller.isPermissionGranted()
        val accessibilityEnabled = isAccessibilityServiceEnabled()

        when {
            shizukuGranted -> {
                // Shizuku ready - best mode
                binding.accessibilityCard.setCardBackgroundColor(0xFF4CAF50.toInt())
                binding.tvAccessibilityStatus.text = "Shizuku 已就绪"
                binding.tvAccessibilityHint.text = "可静默卸载，无需手动确认"
                binding.btnEnableAccessibility.visibility = View.GONE
            }
            shizukuAvailable -> {
                // Shizuku running but no permission
                binding.accessibilityCard.setCardBackgroundColor(0xFF2196F3.toInt())
                binding.tvAccessibilityStatus.text = "Shizuku 运行中"
                binding.tvAccessibilityHint.text = "点击授权后即可静默卸载"
                binding.btnEnableAccessibility.visibility = View.VISIBLE
                binding.btnEnableAccessibility.text = "授权 Shizuku"
                binding.btnEnableAccessibility.setTextColor(0xFF2196F3.toInt())
            }
            accessibilityEnabled -> {
                // Fallback: accessibility mode
                binding.accessibilityCard.setCardBackgroundColor(0xFF4CAF50.toInt())
                binding.tvAccessibilityStatus.text = "自动点击已开启"
                binding.tvAccessibilityHint.text = "使用无障碍模式卸载"
                binding.btnEnableAccessibility.visibility = View.GONE
            }
            else -> {
                // Nothing available
                binding.accessibilityCard.setCardBackgroundColor(0xFFFF9800.toInt())
                binding.tvAccessibilityStatus.text = "请安装并启动 Shizuku"
                binding.tvAccessibilityHint.text = "Shizuku 可实现静默卸载，无需手动确认"
                binding.btnEnableAccessibility.visibility = View.VISIBLE
                binding.btnEnableAccessibility.text = "开启无障碍(备用)"
                binding.btnEnableAccessibility.setTextColor(0xFFFF9800.toInt())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return

        updateUninstallModeStatus()
        updateConnectionStatus()

        // 检查当前卸载的应用是否已被卸载 (accessibility mode)
        if (isUninstalling && currentUninstallPackage != null) {
            handler.postDelayed({
                currentUninstallPackage = null
                processNextUninstall()
            }, 500)
        } else {
            updateStats()
        }

        // Report app list to MQTT when resuming
        if (serviceBound && deviceManager.isPaired()) {
            mqttService?.getMqttManager()?.publishAppList()
        }
    }

    override fun onDestroy() {
        stopConnectionStatusCheck()
        handler.removeCallbacksAndMessages(null)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(whitelistReceiver)
            unregisterReceiver(unpairReceiver)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${AutoClickService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(':').any { service ->
            service.equals(serviceName, ignoreCase = true) ||
            service.contains(AutoClickService::class.java.simpleName, ignoreCase = true)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "请找到\"应用卸载器\"并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开设置，请手动前往 设置→无障碍", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun updateStats() {
        Thread {
            val whitelist = getWhitelist()
            val userApps = getUserAppPackages()
            val toUninstallCount = userApps.count { !whitelist.contains(it) }
            val whitelistCount = userApps.size - toUninstallCount

            if (!isDestroyed && !isFinishing) {
                runOnUiThread {
                    binding.tvUninstallCount.text = toUninstallCount.toString()
                    binding.tvWhitelistCount.text = whitelistCount.toString()

                    if (toUninstallCount > 0) {
                        binding.btnUninstallAll.isEnabled = true
                        binding.btnUninstallAll.text = "一键卸载 ($toUninstallCount 个)"
                        binding.btnUninstallAll.alpha = 1f
                    } else {
                        binding.btnUninstallAll.isEnabled = false
                        binding.btnUninstallAll.text = "一键卸载 (请先设置白名单)"
                        binding.btnUninstallAll.alpha = 0.5f
                    }
                }
            }
        }.start()
    }

    private fun getUserAppPackages(): List<String> {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return packages
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != packageName }
            .map { it.packageName }
    }

    private fun getWhitelist(): Set<String> {
        return prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
    }

    fun startBatchUninstall() {
        // Prefer Shizuku for silent uninstall
        if (shizukuUninstaller.isAvailable() && shizukuUninstaller.isPermissionGranted()) {
            confirmAndStartShizukuUninstall()
            return
        }

        // Fallback: accessibility-based uninstall
        startAccessibilityUninstall()
    }

    private fun confirmAndStartShizukuUninstall() {
        val packages = shizukuUninstaller.getPackagesToUninstall()
        if (packages.isEmpty()) {
            Toast.makeText(this, "没有需要卸载的应用", Toast.LENGTH_SHORT).show()
            return
        }

        // Build preview list (show up to 10 app names)
        val pm = packageManager
        val appNames = packages.take(10).map { pkg ->
            try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
            catch (_: Exception) { pkg }
        }
        val preview = appNames.joinToString("\n") { "  • $it" }
        val moreText = if (packages.size > 10) "\n  ... 等 ${packages.size - 10} 个应用" else ""

        AlertDialog.Builder(this)
            .setTitle("确认卸载")
            .setMessage("即将静默卸载 ${packages.size} 个应用：\n\n$preview$moreText\n\n此操作不可撤销，确定继续？")
            .setPositiveButton("确定卸载") { _, _ ->
                startShizukuUninstall(packages)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startShizukuUninstall(packages: List<String>) {
        isUninstalling = true
        binding.progressContainer.visibility = View.VISIBLE
        binding.tvProgress.text = "准备静默卸载 ${packages.size} 个应用..."
        binding.btnUninstallAll.isEnabled = false

        val failedApps = mutableListOf<String>()

        mqttService?.getMqttManager()?.publishStatus()

        shizukuUninstaller.uninstallPackages(packages, object : ShizukuUninstaller.UninstallCallback {
            override fun onProgress(packageName: String, appName: String, current: Int, total: Int) {
                binding.tvProgress.text = "正在卸载 $current/$total: $appName"
            }

            override fun onResult(packageName: String, success: Boolean, message: String) {
                if (!success) failedApps.add(message)
            }

            override fun onComplete(successCount: Int, failCount: Int) {
                isUninstalling = false
                binding.progressContainer.visibility = View.GONE
                binding.btnUninstallAll.isEnabled = true
                updateStats()

                if (serviceBound) {
                    mqttService?.getMqttManager()?.apply {
                        publishStatus()
                        publishAppList()
                    }
                }

                // Show result dialog with details
                if (failCount > 0) {
                    val failList = failedApps.joinToString("\n") { "  • $it" }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("卸载完成")
                        .setMessage("成功: $successCount\n失败: $failCount\n\n失败列表：\n$failList")
                        .setPositiveButton("确定", null)
                        .show()
                } else {
                    Toast.makeText(this@MainActivity,
                        "全部卸载成功: $successCount 个", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun startAccessibilityUninstall() {
        if (!isAccessibilityServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要卸载权限")
                .setMessage("请安装 Shizuku 实现静默卸载，或开启无障碍服务自动点击确认。")
                .setPositiveButton("开启无障碍") { _, _ ->
                    openAccessibilitySettings()
                }
                .setNegativeButton("仍然卸载") { _, _ ->
                    doAccessibilityUninstall()
                }
                .show()
            return
        }
        doAccessibilityUninstall()
    }

    private fun doAccessibilityUninstall() {
        val whitelist = getWhitelist()
        val userApps = getUserAppPackages()

        uninstallQueue = userApps
            .filter { !whitelist.contains(it) }
            .toMutableList()

        if (uninstallQueue.isEmpty()) {
            Toast.makeText(this, "没有需要卸载的应用", Toast.LENGTH_SHORT).show()
            return
        }

        isUninstalling = true
        AutoClickService.isAutoClickEnabled = true

        binding.progressContainer.visibility = View.VISIBLE
        binding.tvProgress.text = "准备卸载 ${uninstallQueue.size} 个应用..."

        mqttService?.getMqttManager()?.publishStatus()

        processNextUninstall()
    }

    private fun processNextUninstall() {
        if (uninstallQueue.isEmpty()) {
            finishUninstall()
            return
        }

        val packageName = uninstallQueue.removeAt(0)

        if (!isPackageInstalled(packageName)) {
            processNextUninstall()
            return
        }

        currentUninstallPackage = packageName
        val remaining = uninstallQueue.size
        binding.tvProgress.text = "正在卸载... 还剩 ${remaining + 1} 个"

        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            handler.postDelayed({
                processNextUninstall()
            }, 300)
        }
    }

    private fun finishUninstall() {
        isUninstalling = false
        AutoClickService.isAutoClickEnabled = false
        binding.progressContainer.visibility = View.GONE
        Toast.makeText(this, "卸载完成！", Toast.LENGTH_SHORT).show()
        updateStats()

        // Report completion via MQTT
        if (serviceBound) {
            mqttService?.getMqttManager()?.apply {
                publishStatus()
                publishAppList()
            }
        }
    }
}
