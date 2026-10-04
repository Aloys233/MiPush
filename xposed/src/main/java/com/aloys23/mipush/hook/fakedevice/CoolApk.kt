package com.aloys23.mipush.hook.fakedevice

import android.app.Application
import com.aloys23.xposed.XC_LoadPackage
import com.aloys23.xposed.hookMethod

class CoolApk : XGPush() {
    override fun fake(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        Application::class.java.hookMethod("onCreate") {
            doAfter { super.fake(lpparam) }
        }
        return true
    }
}