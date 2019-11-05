# Add project specific ProGuard rules here.
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

# I added these because I was getting this error:
# 2019-11-05 09:52:17.180 2053-2053/? E/LoadedApk: Unable to instantiate appComponentFactory
#    java.lang.ClassNotFoundException: Didn't find class "androidx.core.app.CoreComponentFactory" on path: DexPathList[[],nativeLibraryDirectories=[/data/app/org.mozilla.samples.browser-9cbtbDLQwu-b9PsUA1W7RQ==/lib/x86, /data/app/org.mozilla.samples.browser-9cbtbDLQwu-b9PsUA1W7RQ==/base.apk!/lib/x86, /system/lib, /system/product/lib]]
#        at dalvik.system.BaseDexClassLoader.findClass(BaseDexClassLoader.java:196)
# See https://issuetracker.google.com/issues/137646829
-keep class androidx.** {*;}
-keep interface androidx.** { *; }
-keep class androidx.core.app.CoreComponentFactory { *; }