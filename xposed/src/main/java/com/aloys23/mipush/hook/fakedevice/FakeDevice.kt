package com.aloys23.mipush.hook.fakedevice

import com.aloys23.mipush.hook.XLog
import com.aloys23.xposed.XC_LoadPackage

object FakeDevice {
    private const val TAG = "FakeDevice"

    // 用工厂 lambda 直接 new,而不是 Class.newInstance() 反射实例化:
    // 反射调用对 R8 不可见,会导致这些类被优化/合并、无参构造被删,
    // 运行时抛 java.lang.InstantiationException,伪装逻辑整个不执行。
    private val Default: Array<() -> IFakeDevice> = arrayOf({ Common() })

    private val FakeDeviceConfig: Map<String, Array<() -> IFakeDevice>> = mapOf(
        "com.coolapk.market" to arrayOf({ CoolApk() }),
        "com.tencent.mobileqq" to arrayOf({ QQ() }),
        "com.tencent.tim" to arrayOf({ QQ() }),
        "com.sankuai.meituan" to arrayOf({ Common() }),
        "com.sankuai.meituan.takeoutnew" to arrayOf({ Common() }),
        "com.dianping.v1" to arrayOf({ Common() }),
        "com.eg.android.AlipayGphone" to arrayOf({ Alipay() }),
        "com.xunmeng.pinduoduo" to arrayOf({ Common() }),
        "com.ss.android.ugc.aweme" to arrayOf({ DouYin() }),
        "com.taobao.idlefish" to arrayOf({ XianYu() }),
    )

    fun fake(lpparam: XC_LoadPackage.LoadPackageParam) {
        XLog.d(TAG, "fake() called with: packageName = ${lpparam.packageName}, processName = ${lpparam.processName}")
        if (lpparam.packageName == "com.google.android.webview") {
            XLog.d(TAG, "fake() called, ignore ${lpparam.packageName}")
            return
        }

        val fakes = FakeDeviceConfig[lpparam.packageName] ?: Default
        fakes.forEach { it().fake(lpparam) }
    }
}
