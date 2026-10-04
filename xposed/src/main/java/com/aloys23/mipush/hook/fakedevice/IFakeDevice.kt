package com.aloys23.mipush.hook.fakedevice

import com.aloys23.xposed.XC_LoadPackage

interface IFakeDevice {
    fun fake(lpparam: XC_LoadPackage.LoadPackageParam): Boolean
}