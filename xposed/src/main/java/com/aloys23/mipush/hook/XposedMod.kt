package com.aloys23.mipush.hook

import android.os.Build
import com.aloys23.mipush.common.MIPUSH_CORE_PROCESS
import com.aloys23.mipush.common.MIPUSH_PACKAGE_NAME
import com.aloys23.mipush.common.doOnce
import com.aloys23.mipush.hook.fakedevice.FakeDevice
import com.aloys23.mipush.hook.mipush.HookMIPUSH
import com.aloys23.mipush.hook.system.HookSystemService
import com.aloys23.xposed.XC_LoadPackage
import com.aloys23.xposed.XposedBridge
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import org.lsposed.hiddenapibypass.HiddenApiBypass


class XposedMod : XposedModule() {
    companion object {
        private const val TAG = "XposedMod"
    }

    private var processName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        XposedBridge.install(this)
        processName = param.processName
        // 迁移后 shim 全面改用反射(ActivityThread、SystemProperties、Build 字段等),
        // 必须在每个进程开启隐藏 API 豁免。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
            } catch (t: Throwable) {
                XLog.e(TAG, "addHiddenApiExemptions failed", t)
            }
        }
        XLog.d(TAG, "onModuleLoaded process=${param.processName} systemServer=${param.isSystemServer}")
    }

    // onPackageReady 在 classloader 就绪、Application 创建之前触发,且 minSdk 26 下也可用,
    // 语义最接近传统 handleLoadPackage。
    override fun onPackageReady(param: PackageReadyParam) {
        val lpparam = XC_LoadPackage.LoadPackageParam(
            packageName = param.packageName,
            processName = processName,
            classLoader = param.classLoader,
        )
        doOnce(lpparam.classLoader) { hook(lpparam) }
    }

    // system_server 走这里,替代传统的 packageName == "android" 分支。
    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        HookSystemService().hook(param.classLoader)
    }

    private fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        XLog.d(TAG, "Loaded app: " + lpparam.packageName + " process:" + lpparam.processName)

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
