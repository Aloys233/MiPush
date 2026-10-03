package com.aloys23.mipush.hook.fakedevice

import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.aloys23.xposed.findClass
import com.aloys23.xposed.hook

class Alipay : IFakeDevice {
    override fun fake(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        lpparam.classLoader.findClass("com.alipay.pushsdk.thirdparty.xiaomi.XiaoMIPushWorker")
            .declaredMethods
            .find { it.returnType == Boolean::class.java }
            ?.hook { replace { true } }

        return true
    }
}