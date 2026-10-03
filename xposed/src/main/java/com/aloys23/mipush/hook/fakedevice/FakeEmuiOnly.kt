package com.aloys23.mipush.hook.fakedevice

import de.robv.android.xposed.callbacks.XC_LoadPackage

class FakeEmuiOnly : IFakeDevice {
    override fun fake(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        fakeProperty(Property.EMUI_VERSION)
        return true
    }
}