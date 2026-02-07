# UninstallApp

Android 批量卸载工具，支持 MQTT 远程管理和 Shizuku 静默卸载。

## 功能

- **批量卸载**: 一键卸载非白名单的第三方应用
- **白名单管理**: 设置需要保留的应用，防止误卸载
- **Shizuku 静默卸载**: 通过 Shizuku 获取 ADB 权限，无需手动确认即可卸载
- **无障碍模式(备用)**: 自动点击确认按钮完成卸载
- **MQTT 远程管理**: 通过 Web 管理端远程查看设备状态、应用列表、设置白名单、触发卸载
- **配对机制**: 6位数字配对码，安全连接设备与管理端

## 架构

```
Web 管理端 ←→ MQTT Broker (broker-cn.emqx.io) ←→ Android 客户端
                                                      ↓
                                              Shizuku (ADB权限)
                                                      ↓
                                              pm uninstall (静默卸载)
```

## MQTT 通信协议

| Topic | 方向 | 说明 |
|-------|------|------|
| `uninstall/{deviceId}/status` | Android→Web | 设备状态 (在线/电量/待卸载数) |
| `uninstall/{deviceId}/apps` | Android→Web | 已安装应用列表 |
| `uninstall/{deviceId}/whitelist` | Android→Web | 当前白名单 |
| `uninstall/{deviceId}/whitelist/set` | Web→Android | 设置白名单 |
| `uninstall/{deviceId}/command` | Web→Android | 指令 (uninstall/refresh/unpair) |
| `uninstall/pair/{code}` | 双向 | 配对握手 |

## 项目结构

```
app/src/main/
├── java/com/example/uninstallapp/
│   ├── MainActivity.kt          # 主界面 (Shizuku状态卡片、批量卸载)
│   ├── SetupActivity.kt         # 配对页面
│   ├── SettingsActivity.kt      # 白名单管理
│   ├── MqttManager.kt           # MQTT 连接与通信
│   ├── MqttService.kt           # 前台服务 (保持MQTT连接、处理远程指令)
│   ├── DeviceManager.kt         # 设备ID与配对状态管理
│   ├── ShizukuUninstaller.kt    # Shizuku 静默卸载 (User Service)
│   ├── UserService.kt           # Shizuku AIDL 服务实现
│   ├── AutoClickService.kt      # 无障碍自动点击 (备用)
│   ├── AppListAdapter.kt        # 应用列表适配器
│   └── AppInfo.kt               # 应用信息数据类
├── aidl/com/example/uninstallapp/
│   └── IUserService.aidl         # Shizuku 服务接口
└── res/
    ├── layout/                   # 界面布局
    └── xml/                      # 无障碍配置
```

## 构建

```bash
# 设置环境变量
$env:JAVA_HOME = "path/to/jdk-17"
$env:ANDROID_HOME = "path/to/android-sdk"

# 构建 APK
./gradlew.bat assembleDebug
```

## 依赖

- Shizuku API 13.1.5 (静默卸载)
- Paho MQTT Android 4.2 (远程通信)
- AndroidX (UI组件)
- Material Components (界面设计)

## Web 管理端

Web 管理端部署在 GitHub Pages:
- 仓库: [HL946067429.github.io](https://github.com/HL946067429/HL946067429.github.io)
- 路径: `.vuepress/public/uninstall/index.html`
- 访问: `https://hl946067429.github.io/uninstall/`

## Shizuku 配置

1. 安装 [Shizuku](https://github.com/RikkaApps/Shizuku/releases) APK
2. 开启开发者选项中的 "USB调试" 和 "USB调试（安全设置）"
3. 通过 ADB 启动 Shizuku 服务器
4. 在 UninstallApp 中点击 "授权 Shizuku"
