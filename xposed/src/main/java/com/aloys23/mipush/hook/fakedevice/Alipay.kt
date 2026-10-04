package com.aloys23.mipush.hook.fakedevice

import com.aloys23.mipush.hook.XLog
import com.aloys23.xposed.XC_LoadPackage
import com.aloys23.xposed.findClass
import com.aloys23.xposed.hook

class Alipay : IFakeDevice {
    companion object {
        private const val TAG = "Alipay"
    }

    override fun fake(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        // 先做通用的小米设备伪装:否则支付宝可能根本不会去加载/走 XiaoMIPushWorker。
        Common().fake(lpparam)

        return try {
            lpparam.classLoader.findClass("com.alipay.pushsdk.thirdparty.xiaomi.XiaoMIPushWorker")
                .declaredMethods
                .find { it.returnType == Boolean::class.java }
                ?.hook { replace { true } }
            true
        } catch (t: Throwable) {
            XLog.e(TAG, "fake Alipay failed", t)
            false
        }
    }
}
