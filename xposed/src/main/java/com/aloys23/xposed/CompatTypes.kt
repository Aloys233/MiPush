package com.aloys23.xposed

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/**
 * 兼容层：在 libxposed API 102 上复刻传统 XC_MethodHook 的参数对象。
 * hook 回调通过 [MethodHookParam] 访问 method/thisObject/args/result/throwable。
 */
class XC_MethodHook {
    class MethodHookParam internal constructor(
        val method: Executable,
        val thisObject: Any?,
        var args: Array<Any?>,
    ) {
        private var _result: Any? = null

        /** 是否由 hook 代码显式设置过 result(表示短路原方法)。 */
        internal var resultSet: Boolean = false

        var result: Any?
            get() = _result
            set(value) {
                _result = value
                resultSet = true
            }

        private var _throwable: Throwable? = null

        internal var throwableSet: Boolean = false

        var throwable: Throwable?
            get() = _throwable
            set(value) {
                _throwable = value
                throwableSet = true
            }

        fun hasThrowable(): Boolean = _throwable != null

        /** 记录框架执行原始方法得到的返回值(不触发短路标记)。 */
        internal fun assignResult(value: Any?) {
            _result = value
        }
    }
}

/**
 * 兼容层：替代传统 XC_LoadPackage.LoadPackageParam，仅在 hook 分发时携带最基本的信息。
 */
class XC_LoadPackage {
    class LoadPackageParam(
        val packageName: String?,
        val processName: String?,
        val classLoader: ClassLoader,
    )
}

/**
 * 兼容层：持有当前进程的 XposedModule 实例(供顶层 DSL 调用 libxposed 的 hook())，
 * 并提供传统 XposedBridge.log 形式的日志门面。
 */
object XposedBridge {
    @Volatile
    private var module: XposedModule? = null

    internal fun install(module: XposedModule) {
        this.module = module
    }

    internal fun require(): XposedModule =
        module ?: error("libxposed XposedModule 尚未挂载")

    fun log(message: String?) {
        val msg = message ?: "null"
        val m = module
        if (m != null) {
            m.log(Log.INFO, TAG, msg)
        } else {
            Log.i(TAG, msg)
        }
    }

    fun log(t: Throwable?) {
        if (t == null) return
        val m = module
        if (m != null) {
            m.log(Log.ERROR, TAG, t.message ?: "exception", t)
        } else {
            Log.e(TAG, t.message ?: "exception", t)
        }
    }

    private const val TAG = "MiPush"
}

/**
 * 兼容层：替代传统 android.app.AndroidAppHelper。
 * 优先取 ActivityThread.currentApplication()(应用/xmsf 进程)，
 * system_server 中该方法为 null，回退到 ActivityThread.getSystemContext()。
 */
object AndroidAppHelper {
    fun currentApplication(): Context {
        currentActivityThreadApplication()?.let { return it }
        return systemContext()
    }

    private fun currentActivityThreadApplication(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    } catch (t: Throwable) {
        null
    }

    private fun systemContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass
            .getMethod("currentActivityThread")
            .invoke(null)
        return activityThreadClass
            .getMethod("getSystemContext")
            .invoke(activityThread) as Context
    }
}
