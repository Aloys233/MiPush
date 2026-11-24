package com.aloys23.mipush.hook.system

import android.content.pm.ShortcutInfo
import android.os.Binder
import com.aloys23.xposed.AndroidAppHelper
import com.aloys23.xposed.XC_MethodHook
import com.aloys23.xposed.XposedHelpers.findMethodExact
import com.aloys23.mipush.common.MIPUSH_PACKAGE_NAME
import com.aloys23.mipush.hook.XLog
import com.aloys23.xposed.hook
import java.lang.reflect.Method

object ShortcutPermissionHooker {
    private const val TAG = "ShortcutPermissionHooker"
    private val savedToken = ThreadLocal<Long?>()

    private fun fromMipush() = try {
        Binder.getCallingUid() == AndroidAppHelper.currentApplication().packageManager.getPackageUid(MIPUSH_PACKAGE_NAME, 0)
    } catch (e: Throwable) {
        false
    }

    private fun hookPermission(method: Method, hookExtra: (XC_MethodHook.MethodHookParam.() -> Unit)? = null) {
        method.hook {
            doBefore {
                if (fromMipush()) {
                    savedToken.set(Binder.clearCallingIdentity())
                    hookExtra?.invoke(this)
                }
            }
        }
        method.hook {
            doAfter {
                val token = savedToken.get()
                if (token != null) {
                    savedToken.remove()
                    Binder.restoreCallingIdentity(token)
                }
            }
        }
    }

    fun hook(classShortcutService: Class<*>) {
        //    void pushDynamicShortcut(String packageName, in ShortcutInfo shortcut, int userId);
        runCatching {
            hookPermission(
                findMethodExact(classShortcutService, "pushDynamicShortcut", String::class.java, ShortcutInfo::class.java, Int::class.java)
            )
        }.onFailure { XLog.d(TAG, "skip pushDynamicShortcut: ${it.message}") }

        //    int getMaxShortcutCountPerActivity(String packageName, int userId);
        runCatching {
            hookPermission(
                findMethodExact(classShortcutService, "getMaxShortcutCountPerActivity", String::class.java, Int::class.java)
            )
        }.onFailure { XLog.d(TAG, "skip getMaxShortcutCountPerActivity: ${it.message}") }

        // 注意：不 hook verifyCaller，避免部分 ROM 上在未初始化进程触发死循环 ANR。
    }
}