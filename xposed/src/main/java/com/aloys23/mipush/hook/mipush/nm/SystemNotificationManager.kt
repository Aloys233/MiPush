package com.aloys23.mipush.hook.mipush.nm

import android.app.*
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.StatusBarNotification
import com.aloys23.xposed.AndroidAppHelper
import com.aloys23.xposed.XposedHelpers
import com.aloys23.mipush.common.ANDROID_PACKAGE_NAME
import com.aloys23.mipush.hook.XLog
import com.aloys23.xposed.callMethod
import com.aloys23.xposed.callStaticMethod
import com.aloys23.xposed.setField
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException


object SystemNotificationManager {
    private const val TAG = "SystemNotificationManager"

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

    private val notificationManager: Any = NotificationManager::class.java.callStaticMethod("getService")!!

    private fun getUid(packageName: String): Int {
        return AndroidAppHelper.currentApplication().packageManager.getPackageUid(packageName, 0)
    }

    private fun getUserId(): Int {
        return AndroidAppHelper.currentApplication().callMethod("getUserId") as Int? ?: 0
    }

    fun notify(
        packageName: String,
        tag: String?, id: Int, notification: Notification
    ) {
        XLog.d(TAG, "notify() called with: packageName = $packageName, tag = $tag, id = $id, notification = $notification")

        stripAppNameSubText(packageName, notification)
        handleCallNotification(packageName, notification)

        //enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id, Notification notification, int userId)
        val methodEnqueueNotificationWithTag = XposedHelpers.findMethodExact(notificationManager.javaClass, "enqueueNotificationWithTag", String::class.java, String::class.java, String::class.java, Int::class.java, Notification::class.java, Int::class.java)
        val opPkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ANDROID_PACKAGE_NAME else packageName
        methodEnqueueNotificationWithTag.invoke(notificationManager, packageName, opPkg, tag, id, notification, getUserId())
    }

    // 部分应用会把应用名塞进 subText，而 SystemUI 头部本就会显示应用名，
    // 导致出现 "AppName · AppName"。当 subText 与目标应用的应用名完全一致时清除它。
    private fun stripAppNameSubText(packageName: String, notification: Notification) {
        val extras = notification.extras ?: return
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT) ?: return
        if (isSameAsAppName(subText, packageName)) {
            extras.remove(Notification.EXTRA_SUB_TEXT)
        }
    }

    private fun isSameAsAppName(subText: CharSequence, packageName: String): Boolean {
        return try {
            val packageManager = AndroidAppHelper.currentApplication().packageManager
            val appLabel = packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
            subText.toString() == appLabel.toString()
        } catch (t: Throwable) {
            XLog.d(TAG, "isSameAsAppName: failed to resolve app label for $packageName: ${t.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun handleCallNotification(packageName: String, notification: Notification) {
        if (!isIncomingCall(packageName, notification)) return

        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        XLog.d(TAG, "handleCallNotification: detected incoming call for $packageName: title=$title, text=$text")

        try {
            // 1. 注入全屏意图 (fullScreenIntent): 锁屏时由系统全屏拉起，解锁时以高优先级横幅提醒
            if (notification.fullScreenIntent == null) {
                if (notification.contentIntent != null) {
                    notification.fullScreenIntent = notification.contentIntent
                } else {
                    val context = AndroidAppHelper.currentApplication()
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        } else {
                            PendingIntent.FLAG_UPDATE_CURRENT
                        }
                        notification.fullScreenIntent = PendingIntent.getActivity(context, 0, launchIntent, flags)
                    }
                }
            }

            // 2. 标记通话类型与高优先级（去掉FLAG_INSISTENT，避免短提示音无限死循环重复播放）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                notification.category = Notification.CATEGORY_CALL
            }
            notification.flags = notification.flags or Notification.FLAG_HIGH_PRIORITY

            // 3. 动态提升对应通知渠道的重要性 (Android 8.0+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                elevateChannelImportance(packageName, notification.channelId)
            }

            // 4. 主动唤醒 QQ 进程并以零延迟直拉接听界面
            wakeUpApp(packageName, notification)
        } catch (t: Throwable) {
            XLog.e(TAG, "handleCallNotification error for $packageName", t)
        }
    }

    private fun isIncomingCall(packageName: String, notification: Notification): Boolean {
        if (packageName != "com.tencent.mobileqq" && packageName != "com.tencent.tim") {
            return false
        }

        val extras = notification.extras ?: return false
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val tickerText = notification.tickerText?.toString().orEmpty()
        val fullText = "$title $text $subText $tickerText"

        // 排除已结束或未接挂断的提醒
        val isCanceledOrMissed = fullText.contains("已结束") ||
                fullText.contains("已挂断") ||
                fullText.contains("已取消") ||
                fullText.contains("未接听") ||
                fullText.contains("未接来电") ||
                fullText.contains("未接呼叫")
        if (isCanceledOrMissed) return false

        // 匹配通话相关关键字：包含"呼叫"、"通话"、"电话"等
        val isCallKeyword = fullText.contains("呼叫") ||
                fullText.contains("语音通话") ||
                fullText.contains("视频通话") ||
                fullText.contains("QQ电话") ||
                (fullText.contains("邀请你") && (fullText.contains("语音") || fullText.contains("视频") || fullText.contains("通话")))

        if (isCallKeyword) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (notification.category == Notification.CATEGORY_CALL) {
                return true
            }
        }

        return false
    }

    private fun elevateChannelImportance(packageName: String, channelId: String?) {
        if (channelId.isNullOrEmpty()) return
        try {
            val channel = getNotificationChannel(packageName, channelId) ?: return
            if (channel.importance < NotificationManager.IMPORTANCE_HIGH) {
                channel.importance = NotificationManager.IMPORTANCE_HIGH
                channel.enableVibration(true)
                createNotificationChannels(packageName, listOf(channel))
                XLog.d(TAG, "elevateChannelImportance: elevated $channelId to IMPORTANCE_HIGH for $packageName")
            }
        } catch (t: Throwable) {
            XLog.e(TAG, "elevateChannelImportance failed for $packageName, channel=$channelId", t)
        }
    }

    private fun wakeUpApp(packageName: String, notification: Notification) {
        val context = AndroidAppHelper.currentApplication()

        // 1. 点亮屏幕并申请唤醒锁，保持 CPU 活跃 15 秒，避免 QQ 在冷启动加载 dex 和连网握手时被降频压制
        try {
            val powerManager = context.getSystemService(PowerManager::class.java)
            if (powerManager != null) {
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "mipush:call_wakeup"
                )
                wakeLock.acquire(15000)
            }
        } catch (t: Throwable) {
            XLog.e(TAG, "wakeUpApp acquire wakeLock failed", t)
        }

        // 2. 并行拉起 QQ 核心网络服务 (:MSF 进程)，促使呼叫信令立即与腾讯服务器握手
        try {
            val msfIntent = Intent().apply {
                component = ComponentName(packageName, "com.tencent.mobileqq.msf.service.MsfService")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.startService(msfIntent)
            XLog.d(TAG, "wakeUpApp: pre-warmed MsfService for $packageName")
        } catch (_: Throwable) {
            // Android 8.0+ 后台启动可能受限，安全忽略
        }

        // 3. 发送显式唤醒广播拉起 QQ 的 PushReceiver
        try {
            val intent = Intent("com.xiaomi.mipush.RECEIVE_MESSAGE").apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent)
            XLog.d(TAG, "wakeUpApp: sent wakeup broadcast to $packageName")
        } catch (t: Throwable) {
            XLog.e(TAG, "wakeUpApp broadcast failed for $packageName", t)
        }

        // 4. 零延迟拉起来电界面：携带后台启动豁免 Options，绕过系统的 5~10 秒排队惩罚！
        try {
            val options = makeBackgroundActivityStartOptions()
            val contentIntent = notification.contentIntent
            if (contentIntent != null) {
                XLog.d(TAG, "wakeUpApp: launching call UI via contentIntent with BAL options")
                contentIntent.send(context, 0, null, null, null, null, options)
            } else {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    context.startActivity(launchIntent, options)
                }
            }
        } catch (t: Throwable) {
            XLog.e(TAG, "wakeUpApp launch call UI failed for $packageName", t)
        }
    }

    private fun makeBackgroundActivityStartOptions(): Bundle? {
        return try {
            val options = ActivityOptions.makeBasic()
            // Android 13/14+ (API 34): MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1
            try {
                options.callMethod("setPendingIntentBackgroundActivityStartMode", 1)
            } catch (_: Throwable) {}
            try {
                // Android 10-12
                options.callMethod("setPendingIntentBackgroundActivityLaunchAllowed", true)
            } catch (_: Throwable) {}
            options.toBundle()
        } catch (t: Throwable) {
            XLog.d(TAG, "makeBackgroundActivityStartOptions error: ${t.message}")
            null
        }
    }

    fun cancel(
        packageName: String,
        tag: String?, id: Int
    ) {
        XLog.d(TAG, "cancel() called with: packageName = $packageName, tag = $tag, id = $id")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            //void cancelNotificationWithTag(String pkg, String opPkg, String tag, int id, int userId);
            val methodCancelNotificationWithTag = XposedHelpers.findMethodExact(notificationManager.javaClass, "cancelNotificationWithTag", String::class.java, String::class.java, String::class.java, Int::class.java, Int::class.java)
            methodCancelNotificationWithTag.invoke(notificationManager, packageName, ANDROID_PACKAGE_NAME, tag, id, getUserId())
        } else {
            //  public void cancelNotificationWithTag(String pkg, String tag, int id, int userId)
            val methodCancelNotificationWithTag = XposedHelpers.findMethodExact(notificationManager.javaClass, "cancelNotificationWithTag", String::class.java, String::class.java, Int::class.java, Int::class.java)
            methodCancelNotificationWithTag.invoke(notificationManager, packageName, tag, id, getUserId())
        }
    }

    fun createNotificationChannels(
        packageName: String,
        channels: List<NotificationChannel>
    ) {
        XLog.d(TAG, "createNotificationChannels() called with: packageName = $packageName, channels = $channels")

        val channelsList = XposedHelpers.findConstructorExact("android.content.pm.ParceledListSlice", null, List::class.java)
            .newInstance(channels)
        notificationManager.callMethod("createNotificationChannelsForPackage", packageName, getUid(packageName), channelsList)
    }

    fun getNotificationChannel(
        packageName: String,
        channelId: String?
    ): NotificationChannel? {
        XLog.d(TAG, "createNotificationChannels() called with: packageName = $packageName, channelId = $channelId")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            //NotificationChannel getNotificationChannelForPackage(String pkg, int uid, String channelId, String conversationId, boolean includeDeleted);
            XposedHelpers.findMethodExact(notificationManager.javaClass, "getNotificationChannelForPackage", String::class.java, Int::class.java, String::class.java, String::class.java, Boolean::class.java)
                .invoke(notificationManager, packageName, getUid(packageName), channelId, null, false) as NotificationChannel?
        } else {
            //NotificationChannel getNotificationChannelForPackage(String pkg, int uid, String channelId, boolean includeDeleted);
            XposedHelpers.findMethodExact(notificationManager.javaClass, "getNotificationChannelForPackage", String::class.java, Int::class.java, String::class.java, Boolean::class.java)
                .invoke(notificationManager, packageName, getUid(packageName), channelId, false) as NotificationChannel?
        }
    }

    fun getNotificationChannels(
        packageName: String
    ): List<NotificationChannel?>? {
        XLog.d(TAG, "getNotificationChannels() called with: packageName = $packageName")
        //ParceledListSlice getNotificationChannelsForPackage(String pkg, int uid, boolean includeDeleted);
        val parceledListSlice = XposedHelpers.findMethodExact(notificationManager.javaClass, "getNotificationChannelsForPackage", String::class.java, Int::class.java, Boolean::class.java)
                .invoke(notificationManager, packageName, getUid(packageName), false)
        val list = parceledListSlice?.callMethod("getList") as List<NotificationChannel?>?
        return list
    }

    fun deleteNotificationChannel(
        packageName: String,
        channelId: String
    ) {
        XLog.d(TAG, "deleteNotificationChannel() called with: packageName = $packageName, channelId = $channelId")
        notificationManager.callMethod("deleteNotificationChannel", packageName, channelId)
    }


    fun createNotificationChannelGroups(
        packageName: String,
        groups: List<NotificationChannelGroup>
    ) {
        XLog.d(TAG, "createNotificationChannelGroups() called with: packageName = $packageName, groups = $groups")

        // 无法指定 uid，调用成功也不会生效
        // void createNotificationChannelGroups(String pkg, in ParceledListSlice channelGroupList);
        // val list = XposedHelpers.findConstructorExact("android.content.pm.ParceledListSlice", null, List::class.java)
        //     .newInstance(groups)
        // notificationManager.callMethod("createNotificationChannelGroups", packageName, list)

        groups.forEach {
            it.setField("mName", "Mi Push", String::class.java)

            // 无法 hook
            // void createNotificationChannelGroup(String pkg, int uid, NotificationChannelGroup group, boolean fromApp, boolean fromListener)
            // notificationManager.callMethod("createNotificationChannelGroup", packageName, getUid(packageName), it, true, false)
            try {
                // void updateNotificationChannelGroupForPackage(String pkg, int uid, in NotificationChannelGroup group);
                // 因 createNotificationChannelGroup 的 fromApp 为 false，首次创建会产生 NullPointerException
                notificationManager.callMethod(
                    "updateNotificationChannelGroupForPackage",
                    packageName,
                    getUid(packageName),
                    it
                )
            } catch (e: Throwable) {
                // ignore
                // Attempt to invoke virtual method 'boolean android.app.NotificationChannelGroup.isBlocked()' on a null object reference
            }
        }
    }

    fun getNotificationChannelGroup(
        packageName: String,
        groupId: String
    ): NotificationChannelGroup? {
        XLog.d(TAG, "getNotificationChannelGroup() called with: packageName = $packageName, groupId = $groupId")
        //NotificationChannelGroup getNotificationChannelGroupForPackage(String groupId, String pkg, int uid);
        return notificationManager.callMethod("getNotificationChannelGroupForPackage", groupId, packageName, getUid(packageName)) as NotificationChannelGroup?
    }

    fun getNotificationChannelGroups(
        packageName: String
    ): List<NotificationChannelGroup?>? {
        XLog.d(TAG, "getNotificationChannelGroups() called with: packageName = $packageName")

        //ParceledListSlice getNotificationChannelGroupsForPackage(String pkg, int uid, boolean includeDeleted);
        val parceledListSlice = XposedHelpers.findMethodExact(notificationManager.javaClass, "getNotificationChannelGroupsForPackage", String::class.java, Int::class.java, Boolean::class.java)
            .invoke(notificationManager, packageName, getUid(packageName), false)
        val list = parceledListSlice?.callMethod("getList") as List<NotificationChannelGroup?>?
        return list
    }

    fun deleteNotificationChannelGroup(
        packageName: String,
        groupId: String
    ) {
        XLog.d(TAG, "deleteNotificationChannelGroup() called with: packageName = $packageName, groupId = $groupId")
        //void deleteNotificationChannelGroup(String pkg, String channelGroupId);
        notificationManager.callMethod("deleteNotificationChannelGroup", packageName, groupId)
    }

    fun areNotificationsEnabled(
        packageName: String
    ): Boolean {
        XLog.d(TAG, "areNotificationsEnabled() called with: packageName = $packageName")
        return notificationManager.callMethod("areNotificationsEnabledForPackage", packageName, getUid(packageName)) as Boolean
    }

    fun getActiveNotifications(
        packageName: String
    ): Array<StatusBarNotification?>? {
        XLog.d(TAG, "getActiveNotifications() called with: packageName = $packageName")
        val parceledListSlice = notificationManager.callMethod("getAppActiveNotifications", packageName, getUserId())
        val list = parceledListSlice?.callMethod("getList") as List<StatusBarNotification>
        return list.toTypedArray()
    }

}