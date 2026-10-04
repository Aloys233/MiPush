package com.aloys23.xposed

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

typealias HookAction = XC_MethodHook.MethodHookParam.() -> Unit
typealias ReplaceAction = XC_MethodHook.MethodHookParam.() -> Any?
typealias HookCallback = HookContext.() -> Unit

// ---------------------------------------------------------------------------
// Hook 注册(在 libxposed 的 hook().intercept() 之上复刻传统 DSL)
// ---------------------------------------------------------------------------

fun Executable.hook(callback: HookCallback) {
    XposedBridge.require()
        .hook(this)
        // PASSTHROUGH:用户回调已在本类的拦截器内 try/catch;只有"故意抛出的 throwable"
        // 和"原方法异常"才会逃逸,最贴近传统 XposedBridge 的语义。
        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
        .intercept(MethodHook(callback))
}

fun Class<*>.hookMethod(methodName: String, vararg parameterTypes: Class<*>, callback: HookCallback) =
    XposedHelpers.findMethodExact(this, methodName, *parameterTypes).hook(callback)

fun ClassLoader.findClass(className: String): Class<*> = XposedHelpers.findClass(className, this)

// ---------------------------------------------------------------------------
// 方法调用 DSL
// ---------------------------------------------------------------------------

fun Any.callMethod(methodName: String, vararg args: Any?): Any? =
    XposedHelpers.callMethod(this, methodName, *args)

fun Any.callMethod(methodName: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? =
    XposedHelpers.callMethod(this, methodName, parameterTypes, *args)

fun Class<*>.callStaticMethod(methodName: String, vararg args: Any?): Any? =
    XposedHelpers.callStaticMethod(this, methodName, *args)

fun Class<*>.callStaticMethod(methodName: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? =
    XposedHelpers.callStaticMethod(this, methodName, parameterTypes, *args)

// ---------------------------------------------------------------------------
// 字段访问 DSL
// ---------------------------------------------------------------------------

inline operator fun <reified T> Any.get(name: String): T = getField(name, T::class.java)!!

inline operator fun <reified T> Any.set(name: String, value: T?) = setField(name, value, T::class.java)

fun <T> Any.getField(name: String, fieldClazz: Class<T>): T? {
    val obj = if (this is Class<*>) null else this
    val thisClass = if (this is Class<*>) this else this.javaClass
    val field = XposedHelpers.findField(thisClass, name)

    val value = when (fieldClazz) {
        Boolean::class.java -> field.getBoolean(obj)
        Byte::class.java -> field.getByte(obj)
        Char::class.java -> field.getChar(obj)
        Double::class.java -> field.getDouble(obj)
        Float::class.java -> field.getFloat(obj)
        Int::class.java -> field.getInt(obj)
        Long::class.java -> field.getLong(obj)
        Short::class.java -> field.getShort(obj)
        else -> field.get(obj)
    }
    return value as? T?
}

private val fieldAccessFlagsField: java.lang.reflect.Field? by lazy {
    try {
        java.lang.reflect.Field::class.java.getDeclaredField("accessFlags").apply { isAccessible = true }
    } catch (t: Throwable) {
        null
    }
}

// android.os.Build.BRAND 等是 static final,直接 Field.set 会抛 IllegalAccessException。
// 先清掉 FINAL 修饰符再写入。
private fun java.lang.reflect.Field.clearFinalModifier() {
    try {
        if (!java.lang.reflect.Modifier.isFinal(modifiers)) return
        fieldAccessFlagsField?.setInt(this, modifiers and java.lang.reflect.Modifier.FINAL.inv())
    } catch (t: Throwable) {
        // ignore
    }
}

fun <T> Any.setField(name: String, value: T?, fieldClass: Class<T>) {
    val obj = if (this is Class<*>) null else this
    val thisClass = if (this is Class<*>) this else this.javaClass

    val field = XposedHelpers.findField(thisClass, name)
    field.clearFinalModifier()

    when (fieldClass) {
        Boolean::class.java -> field.setBoolean(obj, value as Boolean)
        Byte::class.java -> field.setByte(obj, value as Byte)
        Char::class.java -> field.setChar(obj, value as Char)
        Double::class.java -> field.setDouble(obj, value as Double)
        Float::class.java -> field.setFloat(obj, value as Float)
        Int::class.java -> field.setInt(obj, value as Int)
        Long::class.java -> field.setLong(obj, value as Long)
        Short::class.java -> field.setShort(obj, value as Short)
        else -> field.set(obj, value as T)
    }
}

// ---------------------------------------------------------------------------
// 回调上下文(doBefore / doAfter / replace)
// ---------------------------------------------------------------------------

class HookContext {
    internal var beforeAction: HookAction? = null
        private set

    internal var afterAction: HookAction? = null
        private set

    internal var replaceAction: ReplaceAction? = null
        private set

    internal var needHook: (() -> Boolean)? = null
        private set

    fun doBefore(action: HookAction) {
        this.beforeAction = action
    }

    fun doAfter(action: HookAction) {
        this.afterAction = action
    }

    fun replace(action: ReplaceAction) {
        this.replaceAction = action
    }

    fun replace(hookCheck: () -> Boolean, action: ReplaceAction) {
        this.needHook = hookCheck
        this.replaceAction = action
    }
}

/**
 * 把传统 before/after/replace 回调语义映射到 libxposed 的拦截器链:
 * - replace:直接返回自定义值(不 proceed);异常写入 throwable 并抛出。
 * - before 设置 result 时短路原方法;修改 args 通过 proceed(newArgs) 生效。
 * - after 在 proceed 之后运行,可读取/覆盖 result。
 * - 原方法异常写入 throwable 并在最后抛出。
 * 用户回调自身的异常按传统 XposedBridge 行为记录日志后吞掉。
 */
class MethodHook(callback: HookCallback) : XposedInterface.Hooker {
    private val context = HookContext().apply(callback)

    override fun intercept(chain: XposedInterface.Chain): Any? {
        val param = XC_MethodHook.MethodHookParam(
            method = chain.executable,
            thisObject = chain.thisObject,
            args = chain.args.toTypedArray(),
        )

        val replaceAction = context.replaceAction
        if (replaceAction != null) {
            if (context.needHook?.invoke() == false) {
                return chain.proceed(param.args)
            }
            return try {
                val replaced = replaceAction(param)
                param.assignResult(replaced)
                replaced
            } catch (t: Throwable) {
                param.throwable = t
                throw t
            }
        }

        context.beforeAction?.let { action ->
            try {
                action(param)
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }

        if (!param.throwableSet && !param.resultSet) {
            try {
                param.assignResult(chain.proceed(param.args))
            } catch (t: Throwable) {
                param.throwable = t
            }
        }

        context.afterAction?.let { action ->
            try {
                action(param)
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }

        if (param.throwableSet) {
            throw param.throwable!!
        }
        return param.result
    }
}
