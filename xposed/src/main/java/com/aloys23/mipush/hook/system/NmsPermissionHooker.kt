package com.aloys23.mipush.hook.system

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Process
import com.aloys23.mipush.common.ANDROID_PACKAGE_NAME
import com.aloys23.mipush.common.MIPUSH_PACKAGE_NAME
import com.aloys23.mipush.hook.XLog
import com.aloys23.xposed.AndroidAppHelper
import com.aloys23.xposed.XC_MethodHook
import com.aloys23.xposed.XposedHelpers.findClass
import com.aloys23.xposed.XposedHelpers.findMethodExact
import com.aloys23.xposed.hook
import com.aloys23.xposed.hookMethod
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

object NmsPermissionHooker {
    private const val TAG = "NmsPermissionHooker"
    private const val PER_USER_RANGE = 100_000L
    private const val PID_MASK = 0xffff_ffffL

    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private const val PERMISSION_POST_PROMOTED_NOTIFICATIONS = "android.permission.POST_PROMOTED_NOTIFICATIONS"
    private const val OP_STR_POST_PROMOTED_NOTIFICATIONS = "android:post_promoted_notifications"

    private val promotedTargetPackages = ConcurrentHashMap.newKeySet<String>()
    private val currentPromotedTargetPkg = ThreadLocal<String?>()
    private val callingIdentityTokens = object : ThreadLocal<ArrayDeque<Long>>() {
        override fun initialValue(): ArrayDeque<Long> = ArrayDeque()
    }

    @Volatile
    private var cachedMipushUid = -1

    private fun getContext(): Context = AndroidAppHelper.currentApplication()

    private fun getMipushUid(): Int {
        if (cachedMipushUid <= 0) {
            runCatching {
                cachedMipushUid = getContext().packageManager.getPackageUid(MIPUSH_PACKAGE_NAME, 0)
            }
        }
        return cachedMipushUid
    }

    private fun fromMipush(): Boolean {
        val callingUid = Binder.getCallingUid()
        val uidMatched = runCatching {
            val primary = getMipushUid()
            primary > 0 && callingUid == primary
        }.getOrDefault(false)
        if (uidMatched) return true

        return runCatching {
            getContext().packageManager.getPackagesForUid(callingUid)
                ?.contains(MIPUSH_PACKAGE_NAME) == true
        }.getOrDefault(false)
    }

    private fun userIdFromUid(uid: Int): Int =
        uid.toLong().div(PER_USER_RANGE).toInt().coerceAtLeast(0)

    private fun isSystemUidForAnyUser(uid: Int): Boolean =
        uid >= 0 && uid.toLong().rem(PER_USER_RANGE) == Process.SYSTEM_UID.toLong()

    private fun systemIdentityForUser(userId: Int, pid: Int): Long {
        val normalizedUserId = userId.coerceAtLeast(0)
        val systemUid = normalizedUserId.toLong() * PER_USER_RANGE + Process.SYSTEM_UID
        return (systemUid shl 32) or (pid.toLong() and PID_MASK)
    }

    private fun resolveTargetUserId(
        args: Array<out Any?>,
        callingUid: Int,
        targetUidParamIndex: Int?,
        targetUserIdParamIndex: Int?,
    ): Int {
        targetUidParamIndex
            ?.let { args.getOrNull(it) as? Int }
            ?.takeIf { it >= 0 }
            ?.let(::userIdFromUid)
            ?.let { return it }
        targetUserIdParamIndex
            ?.let { args.getOrNull(it) as? Int }
            ?.takeIf { it >= 0 }
            ?.let { return it }
        return userIdFromUid(callingUid)
    }

    private fun getPackageUid(packageName: String, userId: Int? = null): Int {
        val packageManager = getContext().packageManager
        if (userId != null) {
            val uidAsUser = runCatching {
                PackageManager::class.java
                    .getMethod("getPackageUidAsUser", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(packageManager, packageName, userId) as Int
            }.getOrNull()
            if (uidAsUser != null) return uidAsUser
        }
        return packageManager.getPackageUid(packageName, 0)
    }

    private fun Method.hookWithPermission(
        targetPackageNameParamIndex: Int,
        targetUidParamIndex: Int? = null,
        targetUserIdParamIndex: Int? = null,
        hookExtra: (XC_MethodHook.MethodHookParam.() -> Unit)? = null,
    ) {
        val sentinelNoToken = Long.MIN_VALUE
        hook {
            doBefore {
                if (fromMipush()) {
                    val targetPkg = args.getOrNull(targetPackageNameParamIndex) as? String
                    val tokenToSave = if (targetPkg != MIPUSH_PACKAGE_NAME) {
                        val callingUid = Binder.getCallingUid()
                        val targetUserId = resolveTargetUserId(
                            args = args,
                            callingUid = callingUid,
                            targetUidParamIndex = targetUidParamIndex,
                            targetUserIdParamIndex = targetUserIdParamIndex,
                        )
                        val oldToken = Binder.clearCallingIdentity()
                        Binder.restoreCallingIdentity(systemIdentityForUser(targetUserId, Process.myPid()))
                        oldToken
                    } else {
                        sentinelNoToken
                    }
                    callingIdentityTokens.get()?.push(tokenToSave)
                    hookExtra?.invoke(this)
                }
            }
        }
        hook {
            doAfter {
                val stack = callingIdentityTokens.get()
                if (stack != null && stack.isNotEmpty()) {
                    val token = stack.pop()
                    if (token != sentinelNoToken) {
                        Binder.restoreCallingIdentity(token)
                    }
                }
            }
        }
    }

    private inline fun installStep(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            XLog.d(TAG, "NMS hook step '$name' skipped: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun hook(classINotificationManager: Class<*>) {
        // Android 17 Stub 入口调用者包名/用户校验
        installStep("enforceSystemOrSystemUIOrSamePackage") {
            findMethodExact(
                classINotificationManager,
                "enforceSystemOrSystemUIOrSamePackage",
                String::class.java,
                String::class.java,
            ).hook {
                doBefore {
                    if (fromMipush()) {
                        result = null
                    }
                }
            }
        }

        // boolean canNotifyAsPackage(String callingPkg, String targetPkg, int userId);
        installStep("canNotifyAsPackage") {
            findMethodExact(
                classINotificationManager,
                "canNotifyAsPackage",
                String::class.java,
                String::class.java,
                Int::class.java,
            ).hook {
                doBefore {
                    if (fromMipush()) {
                        result = true
                    }
                }
            }
        }

        // boolean areNotificationsEnabledForPackage(String pkg, int uid);
        installStep("areNotificationsEnabledForPackage") {
            findMethodExact(
                classINotificationManager,
                "areNotificationsEnabledForPackage",
                String::class.java,
                Int::class.java,
            ).hookWithPermission(0, targetUidParamIndex = 1)
        }

        installStep("getNotificationChannelForPackage") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                findMethodExact(
                    classINotificationManager,
                    "getNotificationChannelForPackage",
                    String::class.java, Int::class.java, String::class.java, String::class.java, Boolean::class.java,
                ).hookWithPermission(0, targetUidParamIndex = 1)
            } else {
                findMethodExact(
                    classINotificationManager,
                    "getNotificationChannelForPackage",
                    String::class.java, Int::class.java, String::class.java, Boolean::class.java,
                ).hookWithPermission(0, targetUidParamIndex = 1)
            }
        }

        // ParceledListSlice getNotificationChannelsForPackage(String pkg, int uid, boolean includeDeleted);
        installStep("getNotificationChannelsForPackage") {
            findMethodExact(
                classINotificationManager,
                "getNotificationChannelsForPackage",
                String::class.java,
                Int::class.java,
                Boolean::class.java,
            ).hookWithPermission(0, targetUidParamIndex = 1)
        }

        // void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id, Notification notification, int userId)
        installStep("enqueueNotificationWithTag") {
            val enqueueMethod = findMethodExact(
                classINotificationManager,
                "enqueueNotificationWithTag",
                String::class.java,
                String::class.java,
                String::class.java,
                Int::class.java,
                Notification::class.java,
                Int::class.java,
            )
            enqueueMethod.hookWithPermission(0, targetUserIdParamIndex = 5) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    args[1] = ANDROID_PACKAGE_NAME
                }
                val pkg = args[0] as? String
                val notification = args[4] as? Notification
                if (pkg != null && isPromotedOngoingRequest(notification)) {
                    promotedTargetPackages.add(pkg)
                    currentPromotedTargetPkg.set(pkg)
                }
            }
            enqueueMethod.hook {
                doAfter {
                    currentPromotedTargetPkg.remove()
                }
            }
        }

        // void createNotificationChannelsForPackage(String pkg, int uid, in ParceledListSlice channelsList);
        installStep("createNotificationChannelsForPackage") {
            findMethodExact(
                classINotificationManager,
                "createNotificationChannelsForPackage",
                String::class.java,
                Int::class.java,
                findClass("android.content.pm.ParceledListSlice", null),
            ).hookWithPermission(0, targetUidParamIndex = 1)
        }

        installStep("cancelNotificationWithTag") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // void cancelNotificationWithTag(String pkg, String opPkg, String tag, int id, int userId);
                findMethodExact(
                    classINotificationManager,
                    "cancelNotificationWithTag",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.java,
                    Int::class.java,
                ).hookWithPermission(0, targetUserIdParamIndex = 4) {
                    args[1] = ANDROID_PACKAGE_NAME
                }
            } else {
                findMethodExact(
                    classINotificationManager,
                    "cancelNotificationWithTag",
                    String::class.java,
                    String::class.java,
                    Int::class.java,
                    Int::class.java,
                ).hookWithPermission(0, targetUserIdParamIndex = 3)
            }
        }

        // void deleteNotificationChannel(String pkg, String channelId);
        installStep("deleteNotificationChannel") {
            findMethodExact(
                classINotificationManager,
                "deleteNotificationChannel",
                String::class.java,
                String::class.java,
            ).hookWithPermission(0)
        }

        // ParceledListSlice getAppActiveNotifications(String callingPkg, int userId);
        installStep("getAppActiveNotifications") {
            findMethodExact(
                classINotificationManager,
                "getAppActiveNotifications",
                String::class.java,
                Int::class.java,
            ).hookWithPermission(0, targetUserIdParamIndex = 1)
        }

        val deleteNotificationChannelHook: XC_MethodHook.MethodHookParam.() -> Unit = {
            val packageName = args[0] as String
            val callingUid = Binder.getCallingUid()
            if (isSystemUidForAnyUser(callingUid)) {
                args[1] = getPackageUid(packageName, userIdFromUid(callingUid))
            }
        }
        installStep("PreferencesHelper.deleteNotificationChannel") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val primaryHook = runCatching {
                    findClass("com.android.server.notification.PreferencesHelper", classINotificationManager.classLoader)
                        .hookMethod(
                            "deleteNotificationChannel",
                            String::class.java,
                            Int::class.java,
                            String::class.java,
                            Int::class.java,
                            Boolean::class.java,
                        ) {
                            doBefore { deleteNotificationChannelHook(this) }
                        }
                }
                if (primaryHook.isFailure) {
                    // Android 17 移除了公开版本，仅保留 deleteNotificationChannelLocked
                    findClass("com.android.server.notification.PreferencesHelper", classINotificationManager.classLoader)
                        .hookMethod(
                            "deleteNotificationChannelLocked",
                            String::class.java,
                            Int::class.java,
                            NotificationChannel::class.java,
                        ) {
                            doBefore { deleteNotificationChannelHook(this) }
                        }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                findClass("com.android.server.notification.PreferencesHelper", classINotificationManager.classLoader)
                    .hookMethod("deleteNotificationChannel", String::class.java, Int::class.java, String::class.java) {
                        doBefore { deleteNotificationChannelHook(this) }
                    }
            } else {
                findClass("com.android.server.notification.RankingHelper", classINotificationManager.classLoader)
                    .hookMethod("deleteNotificationChannel", String::class.java, Int::class.java, String::class.java) {
                        doBefore { deleteNotificationChannelHook(this) }
                    }
            }
        }

        // void updateNotificationChannelGroupForPackage(String pkg, int uid, in NotificationChannelGroup group);
        installStep("updateNotificationChannelGroupForPackage") {
            findMethodExact(
                classINotificationManager,
                "updateNotificationChannelGroupForPackage",
                String::class.java,
                Int::class.java,
                NotificationChannelGroup::class.java,
            ).hookWithPermission(0, targetUidParamIndex = 1)
        }

        // void createNotificationChannelGroup(String pkg, int uid, in NotificationChannelGroup group, boolean fromApp, boolean fromListener);
        installStep("createNotificationChannelGroup") {
            findMethodExact(
                classINotificationManager,
                "createNotificationChannelGroup",
                String::class.java,
                Int::class.java,
                NotificationChannelGroup::class.java,
                Boolean::class.java,
                Boolean::class.java,
            ).hookWithPermission(0, targetUidParamIndex = 1)
        }

        // NotificationChannelGroup getNotificationChannelGroupForPackage(String groupId, String pkg, int uid);
        installStep("getNotificationChannelGroupForPackage") {
            findMethodExact(
                classINotificationManager,
                "getNotificationChannelGroupForPackage",
                String::class.java,
                String::class.java,
                Int::class.java,
            ).hookWithPermission(1, targetUidParamIndex = 2)
        }

        // ParceledListSlice getNotificationChannelGroupsForPackage(String pkg, int uid, boolean includeDeleted);
        installStep("getNotificationChannelGroupsForPackage") {
            findMethodExact(
                classINotificationManager,
                "getNotificationChannelGroupsForPackage",
                String::class.java,
                Int::class.java,
                Boolean::class.java,
            ).hookWithPermission(0, targetUidParamIndex = 1)
        }

        // void deleteNotificationChannelGroup(String pkg, String channelGroupId);
        installStep("deleteNotificationChannelGroup") {
            findMethodExact(
                classINotificationManager,
                "deleteNotificationChannelGroup",
                String::class.java,
                String::class.java,
            ).hookWithPermission(0)
        }

        installStep("NotificationManagerService.checkCallerIsSystem") {
            val nmsClass = findClass(
                "com.android.server.notification.NotificationManagerService",
                classINotificationManager.classLoader,
            )
            findMethodExact(nmsClass, "checkCallerIsSystem").hook {
                doBefore {
                    if (fromMipush()) {
                        result = null
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 36) {
            hookPromotedNotificationSupport(classINotificationManager)
        }
    }

    private fun isPromotedOngoingRequest(notification: Notification?): Boolean {
        if (notification == null) return false
        return try {
            notification.extras?.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, false) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isPromotedTargetPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return pkg == currentPromotedTargetPkg.get() || promotedTargetPackages.contains(pkg)
    }

    private fun hookPromotedNotificationSupport(classINotificationManager: Class<*>) {
        val systemClassLoader = classINotificationManager.classLoader
        installStep("stubPromotedMethods") { hookStubPromotedMethods(classINotificationManager) }
        installStep("declaringClassPromotedMethods") {
            hookBooleanPromotedMethods(classINotificationManager.declaringClass)
        }
        installStep("NotificationManagerService.fixNotificationWithChannel") {
            hookFixNotificationWithChannel(systemClassLoader)
        }
        hookClassByName(systemClassLoader, "com.android.server.notification.NotificationManagerService") {
            hookBooleanPromotedMethods(it)
            hookPostPromotedPermissionMethods(it)
        }
        hookClassByName(systemClassLoader, "com.android.server.notification.PreferencesHelper") {
            hookBooleanPromotedMethods(it)
        }
        hookClassByName(systemClassLoader, "com.android.server.notification.PermissionHelper") {
            hookPostPromotedPermissionMethods(it)
        }
        hookClassByName(systemClassLoader, "com.android.server.pm.permission.PermissionManagerServiceImpl") {
            hookPackageManagerPostPromotedPermission(it)
        }
        hookClassByName(systemClassLoader, "com.android.server.pm.PackageManagerService\$IPackageManagerImpl") {
            hookPackageManagerPostPromotedPermission(it)
        }
        hookClassByName(systemClassLoader, "com.android.server.appop.AppOpsService") {
            hookAppOpsPostPromoted(it)
        }
    }

    private val promotedFlagValue: Int by lazy {
        runCatching {
            Notification::class.java.getField("FLAG_PROMOTED_ONGOING").getInt(null)
        }.getOrDefault(0)
    }

    private fun hookFixNotificationWithChannel(classLoader: ClassLoader?) {
        val nmsClass = findClass("com.android.server.notification.NotificationManagerService", classLoader)
        nmsClass.hookMethod(
            "fixNotificationWithChannel",
            Notification::class.java,
            NotificationChannel::class.java,
            Int::class.javaPrimitiveType!!,
            String::class.java,
        ) {
            doAfter {
                val notification = args.getOrNull(0) as? Notification ?: return@doAfter
                val channel = args.getOrNull(1) as? NotificationChannel ?: return@doAfter
                val pkg = args.getOrNull(3) as? String ?: return@doAfter
                if (!isPromotedTargetPackage(pkg)) return@doAfter
                if (!isPromotedOngoingRequest(notification)) return@doAfter
                if (channel.importance == NotificationManager.IMPORTANCE_NONE) return@doAfter
                val flag = promotedFlagValue
                if (flag == 0) return@doAfter
                val hasCharacteristics = runCatching {
                    notification.javaClass.getMethod("hasPromotableCharacteristics")
                        .invoke(notification) as? Boolean ?: false
                }.getOrDefault(false)
                if (hasCharacteristics) {
                    notification.flags = notification.flags or flag
                }
            }
        }
    }

    private inline fun hookClassByName(classLoader: ClassLoader?, className: String, block: (Class<*>) -> Unit) {
        try {
            val clazz = findClass(className, classLoader)
            block(clazz)
        } catch (t: Throwable) {
            XLog.d(TAG, "skip hook $className: ${t.message}")
        }
    }

    private fun hookStubPromotedMethods(stubClass: Class<*>) {
        for (method in stubClass.declaredMethods) {
            if (method.isSynthetic || method.isBridge) continue
            if (!method.name.contains("Promoted", ignoreCase = true)) continue
            try {
                val returnType = method.returnType
                if (method.name.startsWith("set", ignoreCase = true)) {
                    method.hookWithPermission(0)
                } else if (returnType == java.lang.Boolean.TYPE || returnType == Boolean::class.java) {
                    method.hookWithPermission(0) {
                        val pkg = args.firstOrNull { it is String } as? String
                        if (isPromotedTargetPackage(pkg)) {
                            result = true
                        }
                    }
                }
            } catch (t: Throwable) {
                XLog.d(TAG, "skip stub method ${method.name}: ${t.message}")
            }
        }
    }

    private fun hookBooleanPromotedMethods(clazz: Class<*>?) {
        if (clazz == null) return
        for (method in clazz.declaredMethods) {
            if (method.isSynthetic || method.isBridge) continue
            if (!method.name.contains("Promoted", ignoreCase = true)) continue
            val returnType = method.returnType
            if (returnType != java.lang.Boolean.TYPE && returnType != Boolean::class.java) continue
            try {
                method.hook {
                    doBefore {
                        val pkg = args.firstOrNull { it is String } as? String
                        if (isPromotedTargetPackage(pkg) || (pkg == null && currentPromotedTargetPkg.get() != null)) {
                            result = true
                        }
                    }
                }
            } catch (t: Throwable) {
                XLog.d(TAG, "skip promoted boolean method ${clazz.simpleName}.${method.name}: ${t.message}")
            }
        }
    }

    private fun hookPostPromotedPermissionMethods(clazz: Class<*>) {
        for (method in clazz.declaredMethods) {
            if (method.isSynthetic || method.isBridge) continue
            val matchesName = method.name.contains("Permission", ignoreCase = true)
                    || method.name.contains("Promoted", ignoreCase = true)
            if (!matchesName) continue
            val returnType = method.returnType
            val isBool = returnType == java.lang.Boolean.TYPE || returnType == Boolean::class.java
            val isInt = returnType == Integer.TYPE || returnType == Int::class.java
            if (!isBool && !isInt) continue
            if (isBool && method.name.contains("Promoted", ignoreCase = true) && !method.name.contains("Permission", ignoreCase = true)) {
                continue
            }
            try {
                method.hook {
                    doBefore {
                        val activePkg = currentPromotedTargetPkg.get()
                        val hasPermArg = args.any { it == PERMISSION_POST_PROMOTED_NOTIFICATIONS }
                        val pkgArg = args.firstOrNull { it is String && it != PERMISSION_POST_PROMOTED_NOTIFICATIONS } as? String
                        val allow = (hasPermArg && (activePkg != null || isPromotedTargetPackage(pkgArg)))
                                || (method.name.contains("Promoted", ignoreCase = true) && isPromotedTargetPackage(pkgArg))
                        if (allow) {
                            result = if (isBool) true else PackageManager.PERMISSION_GRANTED
                        }
                    }
                }
            } catch (t: Throwable) {
                XLog.d(TAG, "skip permission method ${clazz.simpleName}.${method.name}: ${t.message}")
            }
        }
    }

    private fun hookPackageManagerPostPromotedPermission(clazz: Class<*>) {
        for (method in clazz.declaredMethods) {
            if (method.isSynthetic || method.isBridge) continue
            if (!method.name.startsWith("checkPermission", ignoreCase = true)) continue
            val returnType = method.returnType
            if (returnType != Integer.TYPE && returnType != Int::class.java) continue
            try {
                method.hook {
                    doBefore {
                        val activePkg = currentPromotedTargetPkg.get() ?: return@doBefore
                        if (args.any { it == PERMISSION_POST_PROMOTED_NOTIFICATIONS }
                            && args.any { it == activePkg }
                        ) {
                            result = PackageManager.PERMISSION_GRANTED
                        }
                    }
                }
            } catch (t: Throwable) {
                XLog.d(TAG, "skip pm checkPermission ${clazz.simpleName}.${method.name}: ${t.message}")
            }
        }
    }

    private val promotedAppOpCode: Int by lazy {
        try {
            AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
                .invoke(null, OP_STR_POST_PROMOTED_NOTIFICATIONS) as? Int ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    private fun hookAppOpsPostPromoted(clazz: Class<*>) {
        for (method in clazz.declaredMethods) {
            if (method.isSynthetic || method.isBridge) continue
            val isCheckOrNote = method.name == "checkOperation"
                    || method.name == "checkOperationRaw"
                    || method.name == "noteOperation"
                    || method.name == "checkOpNoThrow"
            if (!isCheckOrNote) continue
            val returnType = method.returnType
            if (returnType != Integer.TYPE && returnType != Int::class.java) continue
            try {
                method.hook {
                    doBefore {
                        val activePkg = currentPromotedTargetPkg.get() ?: return@doBefore
                        if (!args.any { it == activePkg }) return@doBefore
                        val opCode = promotedAppOpCode
                        val matchesOp = (opCode >= 0 && args.any { it == opCode })
                                || args.any { it is String && it.contains("promoted", ignoreCase = true) }
                        if (matchesOp) {
                            result = AppOpsManager.MODE_ALLOWED
                        }
                    }
                }
            } catch (t: Throwable) {
                XLog.d(TAG, "skip AppOps ${method.name}: ${t.message}")
            }
        }
    }
}
