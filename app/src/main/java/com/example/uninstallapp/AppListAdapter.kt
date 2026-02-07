package com.example.uninstallapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.uninstallapp.databinding.ItemAppSettingBinding

class AppListAdapter(
    private var appList: MutableList<AppInfo>,
    private val onCheckedChange: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemAppSettingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppSettingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val appInfo = appList[position]

        holder.binding.apply {
            ivAppIcon.setImageDrawable(appInfo.icon)
            tvAppName.text = appInfo.name
            tvAppSize.text = appInfo.size

            // 系统应用标签
            if (appInfo.isSystemApp) {
                tvSystemBadge.visibility = View.VISIBLE
            } else {
                tvSystemBadge.visibility = View.GONE
            }

            // 设置复选框状态（不触发监听器）
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = appInfo.isWhitelisted

            // 系统应用：checkbox禁用，强制勾选
            if (appInfo.isSystemApp) {
                checkbox.isEnabled = false
                checkbox.alpha = 0.5f
                root.alpha = 0.8f
                tvStatus.text = "系统"
                tvStatus.setTextColor(0xFF78909C.toInt()) // 灰蓝色
            } else if (appInfo.isWhitelisted) {
                checkbox.isEnabled = true
                checkbox.alpha = 1f
                root.alpha = 1f
                tvStatus.text = "✓ 保留"
                tvStatus.setTextColor(0xFF4CAF50.toInt()) // 绿色
            } else {
                checkbox.isEnabled = true
                checkbox.alpha = 1f
                root.alpha = 0.7f
                tvStatus.text = "✗ 将卸载"
                tvStatus.setTextColor(0xFFF44336.toInt()) // 红色
            }

            // 复选框监听（仅非系统应用）
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (!appInfo.isSystemApp) {
                    appInfo.isWhitelisted = isChecked
                    onCheckedChange(appInfo, isChecked)
                    notifyItemChanged(position)
                }
            }

            // 点击整个卡片切换选中状态（仅非系统应用）
            root.setOnClickListener {
                if (!appInfo.isSystemApp) {
                    checkbox.isChecked = !checkbox.isChecked
                }
            }
        }
    }

    override fun getItemCount(): Int = appList.size

    fun updateList(newList: MutableList<AppInfo>) {
        appList = newList
        notifyDataSetChanged()
    }
}
