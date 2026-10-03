package com.aloys23.mipush.hook.mipush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.service.notification.StatusBarNotification
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.ClassNotFoundError
import com.aloys23.mipush.hook.XLog
import com.aloys23.mipush.hook.mipush.nm.SystemNotificationManager
import com.aloys23.mipush.hook.system.HookSystemService
import com.aloys23.xposed.findClass
import com.aloys23.xposed.hookMethod
import com.aloys23.xposed.set
import java.lang.reflect.InvocationTargetException

object HookPushNC {
    private const val TAG = "HookPushNC"

    private const val TargetClass = "com.nihility.notification.NotificationManagerEx"

    private val hookCheck = { HookSystemService.isSystemHookReady }

    fun canHook(classLoader: ClassLoader): Boolean {
        return try {
            classLoader.findClass(TargetClass)
            true
        } catch (e: ClassNotFoundError) {
            false
        }
    }

    fun hook(classLoader: ClassLoader) {
        XLog.d(TAG, "hookPushNC() called with: classLoader = $classLoader")

        val classNotificationManager = classLoader.findClass(TargetClass)

        try {
            classNotificationManager["isHooked"] = true
        } catch (_: Throwable) {

        }

        // 逐个 hook 独立容错:目标方法缺失/签名变化时只跳过它,不影响其余 hook。
        // notify(packageName: String, tag: String?, id: Int, notification: Notification)
        hookSafe("notify") {
            classNotificationManager.hookMethod(
                "notify",
                String::class.java,
                String::class.java,
                Int::class.java,
                Notification::class.java
            ) {
                replace(hookCheck) {
                    tryInvoke {
                        SystemNotificationManager.notify(
                            args[0] as String,
                            args[1] as String?,
                            args[2] as Int,
                            args[3] as Notification
                        )
                    }
                }
            }
        }

        // cancel(packageName: String, tag: String?, id: Int)
        hookSafe("cancel") {
            classNotificationManager.hookMethod(
                "cancel",
                String::class.java,
                String::class.java,
                Int::class.java
            ) {
                replace(hookCheck) {
                    tryInvoke {
                        SystemNotificationManager.cancel(
                            args[0] as String,
                            args[1] as String?,
                            args[2] as Int
                        )
                    }
                }
            }
        }

        // createNotificationChannels(packageName: String, channels: List<NotificationChannel?>)
        hookSafe("createNotificationChannels") {
            classNotificationManager.hookMethod(
                "createNotificationChannels",
                String::class.java,
                List::class.java
            ) {
                replace(hookCheck) {
                    tryInvoke {
                        SystemNotificationManager.createNotificationChannels(
                            args[0] as String,
                            args[1] as List<NotificationChannel>
                        )
                    }
                }
            }
        }

        // getNotificationChannel(packageName: String, channelId: String?): NotificationChannel?
        hookSafe("getNotificationChannel") {
            classNotificationManager.hookMethod(
                "getNotificationChannel",
                String::class.java,
                String::class.java
            ) {
                replace() {
                    tryInvoke {
                        return@replace SystemNotificationManager.getNotificationChannel(
                            args[0] as String,
                            args[1] as String
                        ) as NotificationChannel?
                    }
                }
            }
        }

        // getNotificationChannels(packageName: String): List<NotificationChannel?>?
        hookSafe("getNotificationChannels") {
            classNotificationManager.hookMethod("getNotificationChannels", String::class.java) {
                replace(hookCheck) {
                    tryInvoke {
                        return@replace SystemNotificationManager.getNotificationChannels(args[0] as String) as List<NotificationChannel?>?
                    }
                }
            }
        }

        // deleteNotificationChannel(packageName: String, channelId: String?)
        hookSafe("deleteNotificationChannel") {
            classNotificationManager.hookMethod(
                "deleteNotificationChannel",
                String::class.java,
                String::class.java
            ) {
                replace(hookCheck) {
                    tryInvoke {
                        SystemNotificationManager.deleteNotificationChannel(
                            args[0] as String,
                            args[1] as String
                        )
                    }
                }
            }
        }

        // createNotificationChannelGroups(packageName: String, groups: List<NotificationChannelGroup?>)
        hookSafe("createNotificationChannelGroups") {
            classNotificationManager.hookMethod(
                "createNotificationChannelGroups",
                String::class.java,
                List::class.java
            ) {
                replace(hookCheck) {
                    tryInvoke {
                        SystemNotificationManager.createNotificationChannelGroups(
                            args[0] as String,
                            args[1] as List<NotificationChannelGroup>
                        )
                    }
                }
            }
        }

        // getNotificationChannelGroups(packageName: String): List<NotificationChannelGroup?>?
        hookSafe("getNotificationChannelGroups") {
            classNotificationManager.hookMethod("getNotificationChannelGroups", String::class.java) {
                replace(hookCheck) {
                    tryInvoke {
                        return@replace SystemNotificationManager.getNotificationChannelGroups(args[0] as String) as List<NotificationChannelGroup?>?
                    }
                }
            }
        }

        // deleteNotificationChannelGroup(packageName: String, groupId: String?)
        hookSafe("deleteNotificationChannelGroup") {
            classNotificationManager.hookMethod(
                "deleteNotificationChannelGroup",
                String::class.java,
                String::class.java
            ) {
                replace(hookCheck) {
                    tryInvoke {
                        SystemNotificationManager.deleteNotificationChannelGroup(
                            args[0] as String,
                            args[1] as String
                        )
                    }
                }
            }
        }

        // areNotificationsEnabled(packageName: String): Boolean
        hookSafe("areNotificationsEnabled") {
            classNotificationManager.hookMethod("areNotificationsEnabled", String::class.java) {
                replace(hookCheck) {
                    tryInvoke {
                        return@replace SystemNotificationManager.areNotificationsEnabled(args[0] as String)
                    }
                }
            }
        }

        // getActiveNotifications(packageName: String): Array<StatusBarNotification?>?
        hookSafe("getActiveNotifications") {
            classNotificationManager.hookMethod("getActiveNotifications", String::class.java) {
                replace(hookCheck) {
                    tryInvoke {
                        return@replace SystemNotificationManager.getActiveNotifications(args[0] as String) as Array<StatusBarNotification?>?
                    }
                }
            }
        }
    }

    private inline fun hookSafe(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            XLog.e(TAG, "failed to hook $name: ${t.message}", t)
        }
    }

    private inline fun <R> tryInvoke(invoke: () -> R): R {
        try {
            return invoke()
        } catch (e: XposedHelpers.InvocationTargetError) {
            XLog.e(TAG, "tryInvoke: ", e)
            XLog.e(TAG, "tryInvoke targetException: ", e.cause)
            throw e.cause ?: e
        } catch (e: InvocationTargetException) {
            XLog.e(TAG, "tryInvoke: ", e)
            XLog.e(TAG, "tryInvoke targetException: ", e.targetException)
            throw e.targetException ?: e
        } catch (e: Throwable) {
            XLog.e(TAG, "tryInvoke: ", e)
            XLog.e(TAG, "tryInvoke cause: ", e.cause)
            throw e.cause ?: e
        }
    }
}
