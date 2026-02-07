package com.example.uninstallapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

class AutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoClickService"

        // 是否启用自动点击
        var isAutoClickEnabled = false

        // 服务实例
        var instance: AutoClickService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                   AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
        serviceInfo = info
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAutoClickEnabled) return
        if (event == null) return

        // 检测是否是卸载确认对话框
        val packageName = event.packageName?.toString() ?: return

        // 系统包管理器的包名（不同系统可能不同）
        val systemPackages = listOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.huawei.packageinstaller",
            "com.oppo.packageinstaller",
            "com.vivo.packageinstaller",
            "com.coloros.packageinstaller",
            "android"
        )

        if (packageName in systemPackages || packageName.contains("packageinstaller")) {
            tryClickUninstallButton()
        }
    }

    private fun tryClickUninstallButton() {
        val rootNode = rootInActiveWindow ?: return

        // 要点击的按钮文字（覆盖各种系统的不同文字）
        val targetTexts = listOf(
            "卸载", "确定", "确认", "好", "好的", "允许", "继续",
            "Uninstall", "OK", "Confirm", "Yes", "Allow", "Continue"
        )

        for (text in targetTexts) {
            if (findAndClickByText(rootNode, text)) {
                Log.d(TAG, "自动点击: $text")
                break
            }
        }

        rootNode.recycle()
    }

    private fun findAndClickByText(node: AccessibilityNodeInfo, text: String): Boolean {
        // 查找包含指定文字的节点
        val nodes = node.findAccessibilityNodeInfosByText(text)

        for (n in nodes) {
            // 检查是否是可点击的按钮
            if (n.isClickable && n.isEnabled) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                n.recycle()
                return true
            }

            // 如果节点本身不可点击，尝试点击其父节点
            var parent = n.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isClickable && parent.isEnabled) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    n.recycle()
                    return true
                }
                val grandParent = parent.parent
                parent.recycle()
                parent = grandParent
                depth++
            }
            n.recycle()
        }

        // 递归查找子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickByText(child, text)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "无障碍服务销毁")
    }
}
