# Proguard for Xposed.
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources

-keep class com.aloys23.mipush.hook.XposedMod{
    *;
}
