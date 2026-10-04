package com.aloys23.mipush.hook.fakedevice

import com.aloys23.xposed.XC_LoadPackage

class QQ : Common() {

    override fun fake(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        if (lpparam.packageName == lpparam.processName || lpparam.processName?.endsWith(":MSF") == true) {
            return super.fake(lpparam)
        }
        return false
    }
}