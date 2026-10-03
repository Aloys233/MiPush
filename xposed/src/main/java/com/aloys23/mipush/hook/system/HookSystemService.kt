package com.aloys23.mipush.hook.system

import android.app.AndroidAppHelper
import android.app.NotificationManager
import android.os.Binder
import de.robv.android.xposed.XposedHelpers
import com.aloys23.mipush.common.IS_SYSTEM_HOOK_READY
import com.aloys23.mipush.hook.XLog
import com.aloys23.xposed.callMethod
import com.aloys23.xposed.get
import com.aloys23.xposed.hookMethod

class HookSystemService {
    companion object {
        private const val TAG = "HookSystemService"

        val isSystemHookReady: Boolean by lazy {
            try {
                val nm = AndroidAppHelper.currentApplication().getSystemService(NotificationManager::class.java)
                nm.callMethod("isSystemConditionProviderEnabled", IS_SYSTEM_HOOK_READY) as Boolean
            } catch (t: Throwable) {
                XLog.e(TAG, "isSystemHookReady error", t)
                false
            }
        }

    }

    fun hook(classLoader: ClassLoader) {
        val classNotificationManagerService = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService", classLoader)

        classNotificationManagerService.hookMethod("onStart") {
            doAfter {
                XLog.d(TAG, "onStart invoked")
                val stubClass = thisObject.get<Any>("mService").javaClass
                hookPermission(stubClass)
                hookSystemReadyFlag(stubClass)
            }
        }

        //private boolean isPackageSuspendedForUser(String pkg, int uid)
        classNotificationManagerService.hookMethod("isPackageSuspendedForUser", String::class.java, Int::class.java) {
            doBefore {
                if (Binder.getCallingUid() == 1000) {
                    //suspend app can not show notification, fake its state
                    result = false
                }
            }
        }


        val classShortcutService = XposedHelpers.findClass("com.android.server.pm.ShortcutService", classLoader)
        ShortcutPermissionHooker.hook(classShortcutService)
    }

    private fun hookSystemReadyFlag(stubClass: Class<Any>) {
        stubClass.hookMethod("isSystemConditionProviderEnabled", String::class.java) {
            doBefore {
                if (args[0] == IS_SYSTEM_HOOK_READY) {
                    result = true
                }
            }
        }
    }

    private fun hookPermission(stubClass: Class<Any>) {
        NmsPermissionHooker.hook(stubClass)
    }
}