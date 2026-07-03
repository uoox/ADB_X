package top.cbug.adbx.xposed

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

object AdbSystemHooks {

    fun hook(lpparam: LoadPackageParam) {
        XposedInit.log("AdbSystemHooks: lightweight mode loaded into " + lpparam.packageName)
        // 所有功能由 app 进程的前台服务 AdbMonitorService 通过 su 命令实现：
        // - 固定端口: su setprop + su settings put
        // - 自定义配对码: 写入文件 + su 指令
        // - 信任WiFi自动开启: 前台服务 + BroadcastReceiver
        // - 连接看门狗: 前台服务定期检测
        //
        // 为什么移除了所有激进钩子:
        // - SystemProperties.set 钩子导致 system_server 严重卡顿和 ADB 断连
        // - ActivityThread.systemMain() 在 Xposed 加载前执行, WiFi 接收器注册失败
        // - API 36 Rust adbd 下 AdbDebuggingManager/AdbService Java 类不存在
    }
}
