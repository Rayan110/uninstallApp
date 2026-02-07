package com.example.uninstallapp

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.uninstallapp.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var adapter: AppListAdapter
    private lateinit var prefs: SharedPreferences
    private var appList: MutableList<AppInfo> = mutableListOf()

    companion object {
        private const val PREFS_NAME = "whitelist_prefs"
        private const val KEY_WHITELIST = "whitelist"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setupRecyclerView()
        loadInstalledApps()

        // 返回按钮
        binding.btnBack.setOnClickListener {
            finish()
        }

        // 全选保留按钮（仅影响用户应用）
        binding.btnSelectAll.setOnClickListener {
            selectAllUserApps(true)
        }

        // 全不选按钮（仅影响用户应用）
        binding.btnSelectNone.setOnClickListener {
            selectAllUserApps(false)
        }

        // 下拉刷新
        binding.swipeRefresh.setOnRefreshListener {
            loadInstalledApps()
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter(appList) { appInfo, isChecked ->
            updateWhitelist(appInfo.packageName, isChecked)
            updateStats()
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun loadInstalledApps() {
        appList.clear()
        val whitelist = getWhitelist()

        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        for (appInfo in packages) {
            // 排除自己
            if (appInfo.packageName == packageName) continue

            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val name = pm.getApplicationLabel(appInfo).toString()
            val icon = pm.getApplicationIcon(appInfo)
            val size = getAppSize(appInfo.packageName)
            val sizeBytes = getAppSizeBytes(appInfo.packageName)

            // 系统应用默认勾选（白名单），用户应用看SharedPreferences
            val isWhitelisted = if (isSystem) {
                true
            } else {
                whitelist.contains(appInfo.packageName) ||
                    !prefs.contains(KEY_WHITELIST) // 首次使用，全部默认保留
            }

            appList.add(
                AppInfo(
                    name = name,
                    packageName = appInfo.packageName,
                    icon = icon,
                    size = size,
                    sizeBytes = sizeBytes,
                    isWhitelisted = isWhitelisted,
                    isSystemApp = isSystem
                )
            )
        }

        // 排序：用户应用在前，系统应用在后；各自按名称排序
        appList.sortWith(compareBy<AppInfo> { it.isSystemApp }.thenBy { it.name.lowercase() })

        // 如果是首次使用，将所有用户应用添加到白名单
        if (!prefs.contains(KEY_WHITELIST)) {
            val allUserPackages = appList
                .filter { !it.isSystemApp }
                .map { it.packageName }
                .toSet()
            prefs.edit().putStringSet(KEY_WHITELIST, allUserPackages).apply()
        }

        adapter.updateList(appList)
        updateStats()
    }

    private fun getWhitelist(): Set<String> {
        return prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
    }

    private fun updateWhitelist(packageName: String, isWhitelisted: Boolean) {
        val whitelist = getWhitelist().toMutableSet()
        if (isWhitelisted) {
            whitelist.add(packageName)
        } else {
            whitelist.remove(packageName)
        }
        prefs.edit().putStringSet(KEY_WHITELIST, whitelist).apply()
    }

    private fun selectAllUserApps(select: Boolean) {
        val whitelist = getWhitelist().toMutableSet()

        appList.forEach { app ->
            if (!app.isSystemApp) {
                app.isWhitelisted = select
                if (select) {
                    whitelist.add(app.packageName)
                } else {
                    whitelist.remove(app.packageName)
                }
            }
        }

        prefs.edit().putStringSet(KEY_WHITELIST, whitelist).apply()
        adapter.notifyDataSetChanged()
        updateStats()
    }

    private fun updateStats() {
        val userApps = appList.filter { !it.isSystemApp }
        val totalCount = appList.size
        val whitelist = getWhitelist()
        val toUninstallCount = userApps.count { !whitelist.contains(it.packageName) }
        val whitelistCount = totalCount - toUninstallCount

        binding.tvTotalCount.text = totalCount.toString()
        binding.tvWhitelistCount.text = whitelistCount.toString()
        binding.tvUninstallCount.text = toUninstallCount.toString()
    }

    private fun getAppSizeBytes(packageName: String): Long {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val file = java.io.File(appInfo.sourceDir)
            file.length()
        } catch (e: Exception) {
            0L
        }
    }

    private fun getAppSize(packageName: String): String {
        val size = getAppSizeBytes(packageName)
        return formatFileSize(size)
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
