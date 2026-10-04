# libxposed API 102 模块入口类需保留，java_init.list 内的类名需同步混淆后名称。
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-adaptresourcefilecontents META-INF/xposed/java_init.list
# 伪设备处理器:保留类与无参构造,防止 R8 合并/改写导致运行时实例化失败。
-keep class * implements com.aloys23.mipush.hook.fakedevice.IFakeDevice {
    public <init>();
}
-dontwarn io.github.libxposed.annotation.**
-dontwarn io.github.libxposed.**

# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile