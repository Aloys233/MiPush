package com.aloys23.mipush.hook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import com.aloys23.mipush.common.ANDROID_PACKAGE_NAME
import com.aloys23.mipush.common.MIPUSH_CORE_PROCESS
import com.aloys23.mipush.common.MIPUSH_PACKAGE_NAME
import com.aloys23.mipush.common.doOnce
import com.aloys23.mipush.hook.fakedevice.FakeDevice
import com.aloys23.mipush.hook.mipush.HookMIPUSH
import com.aloys23.mipush.hook.system.HookSystemService


class XposedMod : IXposedHookLoadPackage {
    companion object {
        private const val TAG = "XposedMod"
    }

    @Throws(Throwable::class)
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        doOnce(lpparam.classLoader) {
            hook(lpparam)
        }
    }

    private fun hook(lpparam: LoadPackageParam) {
        XLog.d(TAG, "Loaded app: " + lpparam.packageName + " process:" + lpparam.processName)

        // system_server 的 processName 是 "system"(甚至为 null),但其 packageName 是 "android"。
        // 必须按 packageName 判断,否则会把系统进程当成普通应用去 FakeDevice。
        if (lpparam.packageName == ANDROID_PACKAGE_NAME) {
            HookSystemService().hook(lpparam.classLoader)
            return
        }

        if (lpparam.packageName == MIPUSH_PACKAGE_NAME) {
            if (lpparam.processName == MIPUSH_CORE_PROCESS) {
                HookMIPUSH().hook(lpparam)
            }
            return
        }

        // processName 为 null 的是系统进程里的资源加载(如 system_server 加载某个包),不是真正的应用进程,
        // 不能在这里伪装设备。
        if (lpparam.processName == null) return

        FakeDevice.fake(lpparam)
    }
}
