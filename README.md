# ADB_X - 无线ADB调试增强 Xposed 模块

## 功能

- **固定ADB无线调试端口** - 自定义端口号，避免每次随机分配
- **查看配对码** - 从系统捕获并显示无线调试配对码
- **读取保存的WiFi列表** - 获取系统中已保存的WiFi网络
- **设置信任WiFi** - 从列表中勾选信任的WiFi
- **自动开启无线调试** - 连接到信任WiFi时自动启用ADB无线调试
- **自动关闭无线调试** - 离开信任WiFi时自动关闭（可选）

## 环境要求

- Android 11 (API 30) 及以上
- LSPosed / Xposed 框架
- Root 权限（固定端口和配对码功能需要）

## 构建

```bash
# Windows
gradlew.bat assembleRelease

# Linux/macOS
./gradlew assembleRelease
```

生成的 APK 在 `app/build/outputs/apk/release/`

## 安装

1. 安装 APK 到设备
2. 在 LSPosed 管理器中启用本模块
3. 作用域选择 `android` 和 `设置 (com.android.settings)`
4. 重启系统或相关进程
5. 打开 ADB_X 应用进行配置

## 工作原理

### 固定端口
通过 Hook `SystemProperties.set` 拦截 `service.adb.tls.port` 属性设置，将随机端口替换为用户指定的固定端口。

### 自动开启
在 system_server 中注册 WiFi 状态监听器，当连接到信任WiFi时自动写入 `Settings.Global.ADB_WIFI_ENABLED`。

### 配对码
Hook `AdbDebuggingManager` 和设置应用的配对对话框，捕获配对码并写入 `/data/local/tmp/adb_x_pairing_code` 供应用读取。

### WiFi列表
通过 `WifiManager.getConfiguredNetworks()` 或扫描结果获取可用的WiFi网络列表。

## 项目结构

```
ADB_X/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/xposed_init
│       ├── kotlin/top/cbug/adbx/
│       │   ├── App.kt
│       │   ├── MainActivity.kt
│       │   ├── store/Settings.kt
│       │   ├── util/
│       │   │   ├── AdbHelper.kt
│       │   │   ├── ShellUtils.kt
│       │   │   └── WifiHelper.kt
│       │   ├── ui/WifiAdapter.kt
│       │   └── xposed/
│       │       ├── XposedInit.kt
│       │       ├── AdbSystemHooks.kt
│       │       └── SettingsHooks.kt
│       └── res/
│           ├── layout/
│           ├── values/
│           └── ...
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/wrapper/
```

## License

MIT
