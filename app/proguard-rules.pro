# 正式版（R8）混淆规则
# 目标：类名/方法名/字段名全部重命名，包内类被压到一个短包名下，
#       让反编译出来的东西既没有可读的名字，也没有可用的调用结构。

-allowaccessmodification
-repackageclasses 'hpt'
-overloadaggressively
-optimizationpasses 5

# 清单里注册的组件必须保住（R8 默认会保，显式写出更稳）
-keep public class com.hpt.adbtool.MainActivity { public *; }
-keepclassmembers class com.hpt.adbtool.MainActivity {
    native <methods>;
}

# 手写 ADB / scrcpy 协议类不做额外保留：它们只被本包内代码直接调用，
# 混淆后照样能跑；JNI 相关（无）与反射（无）都不涉及。

-keepattributes Signature
-keepattributes *Annotation*

# 调试日志（debug）在正式包里去掉，避免把内部流程暴露给分析者
-assumenosideeffects class android.util.Log {
    public static int v(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
}

-dontwarn **
