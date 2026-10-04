package com.aloys23.mipush.hook.fakedevice

import com.aloys23.xposed.XC_LoadPackage
import com.aloys23.mipush.hook.XLog

open class Common : IFakeDevice {
    companion object {
        private const val TAG = "Common"
    }

    // 与原版 HMSPush 的 Common 保持一致:只做属性伪造(SystemProperties/Runtime.exec/Build 字段),
    // 不 hook Class.forName 之类的全局方法。
    // 之前在这里 hook Class.forName 伪造 miui.os.Build/MiuiInit,会把闲鱼(阿里系 Mtop/preload)
    // 搞到启动即崩(FishRuntimeExeption: MtopInitializeMonitor NPE)。
    override fun fake(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        XLog.d(TAG, "fake() called with: packageName = ${lpparam.packageName}")
        fakeAllBuildInProperties()
        return true
    }
}
