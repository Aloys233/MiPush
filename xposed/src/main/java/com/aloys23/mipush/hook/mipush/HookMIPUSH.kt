package com.aloys23.mipush.hook.mipush

import com.aloys23.xposed.*

class HookMIPUSH {
    companion object {
        private const val TAG = "HookMIPUSH"
    }

    fun hook(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (HookPushNC.canHook(lpparam.classLoader)) {
            HookPushNC.hook(lpparam.classLoader)
        }
    }

}
