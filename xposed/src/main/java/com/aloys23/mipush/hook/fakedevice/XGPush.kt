package com.aloys23.mipush.hook.fakedevice

import com.aloys23.mipush.hook.XLog
import com.aloys23.xposed.XC_LoadPackage
import com.aloys23.xposed.XposedHelpers
import com.aloys23.xposed.findClass
import com.aloys23.xposed.hook
import java.lang.reflect.Method

open class XGPush : IFakeDevice {
    companion object {
        private const val TAG = "FakeForXGPush"
    }

    override fun fake(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        val classLoader = lpparam.classLoader

        XLog.d(TAG, "fake() called with: classLoader = $classLoader")

        return try {
            val classChannelUtils = classLoader.findClass("com.tencent.tpns.baseapi.base.util.ChannelUtils")
            fakeChannels(classChannelUtils)
            true
        } catch (e: XposedHelpers.ClassNotFoundError) {
            XLog.e(TAG, "fake ClassNotFoundError", e)
            false
        } catch (e: Throwable) {
            XLog.e(TAG, "fake error: ", e)
            false
        }
    }

    private fun fakeChannels(classChannelUtils: Class<*>): Boolean {
        XLog.d(TAG, "fakeChannels() called")

        classChannelUtils.declaredMethods.forEach { target ->
            target.hook {
                doBefore {
                    val method = this.method as Method

                    if (method.name == "getMiuiVersionCode") {
                        result = "13"
                    } else if (method.name == "getMiuiVersionName") {
                        result = "V130"
                    } else if (method.name == "isBrandXiaoMi") {
                        result = true
                    } else if (method.returnType == Boolean::class.java) {
                        result = false
                    } else if (method.returnType == String::class.java) {
                        result = ""
                    }
                }
            }
        }
        return true
    }


}
