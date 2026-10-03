package com.aloys23.mipush.hook.system

import android.app.AndroidAppHelper
import android.app.Notification
import android.app.NotificationChannelGroup
import android.content.pm.ShortcutInfo
import android.os.Binder
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers.findClass
import de.robv.android.xposed.XposedHelpers.findMethodExact
import com.aloys23.mipush.common.ANDROID_PACKAGE_NAME
import com.aloys23.mipush.common.MIPUSH_PACKAGE_NAME
import com.aloys23.mipush.hook.XLog
import com.aloys23.mipush.hook.mipush.nm.SystemNotificationManager
import com.aloys23.xposed.HookCallback
import com.aloys23.xposed.hook

object ShortcutPermissionHooker {
    private fun fromMipush() = try {
        Binder.getCallingUid() == AndroidAppHelper.currentApplication().packageManager.getPackageUid(MIPUSH_PACKAGE_NAME, 0)
    } catch (e: Throwable) {
        false
    }

    private fun tryHookPermission(packageName: String): Boolean {
        if (fromMipush()) {
            Binder.clearCallingIdentity()
            return true
        }
        return false
    }

    private fun hookPermission(targetPackageNameParamIndex: Int, hookExtra: (XC_MethodHook.MethodHookParam.() -> Unit)? = null): HookCallback = {
        doBefore {
            if (tryHookPermission(args[targetPackageNameParamIndex] as String)) {
                hookExtra?.invoke(this)
            }
        }
    }

    fun hook(classShortcutService: Class<*>) {
        //    void pushDynamicShortcut(String packageName, in ShortcutInfo shortcut, int userId);
        findMethodExact(classShortcutService, "pushDynamicShortcut", String::class.java, ShortcutInfo::class.java, Int::class.java)
            .hook(hookPermission(0))

        //    int getMaxShortcutCountPerActivity(String packageName, int userId);
        findMethodExact(classShortcutService, "getMaxShortcutCountPerActivity", String::class.java, Int::class.java)
            .hook(hookPermission(0))

        //    void verifyCaller(@NonNull String packageName, @UserIdInt int userId)
        findMethodExact(classShortcutService, "verifyCaller", String::class.java, Int::class.java)
            .hook(hookPermission(0))
    }
}