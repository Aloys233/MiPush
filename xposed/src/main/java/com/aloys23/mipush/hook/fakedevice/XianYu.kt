package com.aloys23.mipush.hook.fakedevice

import com.aloys23.mipush.hook.XLog
import com.aloys23.xposed.XC_LoadPackage
import com.aloys23.xposed.findClass
import com.aloys23.xposed.hookMethod

class XianYu : IFakeDevice {
    companion object {
        private const val TAG = "XianYu"

        // 闲鱼源码里 XiaomiChannel.getChannelId() 在 miui.os.MiuiInit 存在时返回的值
        private const val XIAOMI_CHANNEL_ID = "36359921407387"
    }

    override fun fake(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        // 通用属性伪装
        Common().fake(lpparam)

        return try {
            // 闲鱼选通道靠 DeviceRom.isXiaomi()(即 Build.MANUFACTURER/BRAND),真机上就成立;
            // 但 XiaomiChannel.getChannelId() 还要求 miui.os.MiuiInit 存在,非 MIUI ROM 上抛
            // ClassNotFoundException,导致永远不注册小米通道。
            // 这里定点 hook 该方法直接返回小米 appId,不做全局的 miui 类伪造。
            lpparam.classLoader.findClass("com.taobao.idlefish.preinstall.xiaomi.XiaomiChannel")
                .hookMethod("getChannelId") {
                    replace { XIAOMI_CHANNEL_ID }
                }
            true
        } catch (t: Throwable) {
            XLog.e(TAG, "fake XianYu failed", t)
            false
        }
    }
}
