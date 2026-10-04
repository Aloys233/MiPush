package com.aloys23.xposed

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 兼容层：替代传统 de.robv.android.xposed.XposedHelpers 的反射工具。
 * 纯 Java 反射实现，不依赖框架；沿父类链查找成员，语义尽量对齐传统实现。
 */
object XposedHelpers {

    class ClassNotFoundError(message: String, cause: Throwable) : Error(message, cause)

    class InvocationTargetError(cause: Throwable) : Error(cause)

    fun findClass(className: String, classLoader: ClassLoader?): Class<*> =
        try {
            Class.forName(className, false, classLoader ?: ClassLoader.getSystemClassLoader())
        } catch (e: ClassNotFoundException) {
            throw ClassNotFoundError(className, e)
        }

    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName, *parameterTypes).apply { isAccessible = true }
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        throw NoSuchMethodError("${clazz.name}#$methodName")
    }

    fun findConstructorExact(clazz: Class<*>, vararg parameterTypes: Class<*>): Constructor<*> =
        clazz.getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }

    fun findConstructorExact(className: String, classLoader: ClassLoader?, vararg parameterTypes: Class<*>): Constructor<*> =
        findConstructorExact(findClass(className, classLoader), *parameterTypes)

    fun findField(clazz: Class<*>, fieldName: String): Field {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldError("${clazz.name}#$fieldName")
    }

    fun callMethod(obj: Any, methodName: String, vararg args: Any?): Any? =
        invoke(findMethodByArgs(obj.javaClass, methodName, args), obj, args)

    fun callMethod(obj: Any, methodName: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? =
        invoke(findMethodExact(obj.javaClass, methodName, *parameterTypes), obj, args)

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? =
        invoke(findMethodByArgs(clazz, methodName, args), null, args)

    fun callStaticMethod(clazz: Class<*>, methodName: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? =
        invoke(findMethodExact(clazz, methodName, *parameterTypes), null, args)

    private fun invoke(method: Method, obj: Any?, args: Array<out Any?>): Any? =
        try {
            method.invoke(obj, *args)
        } catch (e: InvocationTargetException) {
            throw InvocationTargetError(e.cause ?: e)
        }

    private fun findMethodByArgs(clazz: Class<*>, methodName: String, args: Array<out Any?>): Method {
        var current: Class<*>? = clazz
        while (current != null) {
            for (method in current.declaredMethods) {
                if (method.name != methodName) continue
                val types = method.parameterTypes
                if (types.size != args.size) continue
                if (types.indices.all { isAssignable(types[it], args[it]) }) {
                    method.isAccessible = true
                    return method
                }
            }
            current = current.superclass
        }
        throw NoSuchMethodError("${clazz.name}#$methodName")
    }

    private fun isAssignable(paramType: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !paramType.isPrimitive
        return box(paramType).isAssignableFrom(arg.javaClass)
    }

    private fun box(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> type
    }
}
