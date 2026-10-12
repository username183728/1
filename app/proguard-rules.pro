# GITLS / Bit AI - R8 rules
#
# Default Android/AGP rules already keep manifest-declared Activities,
# Services, Receivers and Providers. Do not keep the whole application package:
# doing so prevents R8 from removing unused bytecode and makes the APK larger.
#
# Keep source/line information for readable release stack traces.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Main launcher Activity is declared in AndroidManifest.xml. This explicit rule
# also protects its public constructor when R8 analyzes generated entry points.
-keep class com.example.aidetest.MainActivity {
    public <init>();
}

# These model names are consumed by Android/JVM serialization helpers in a few
# optional tools. Keep their public structure without disabling shrinking for
# the rest of the app.
-keep class com.example.aidetest.data.finance.** { *; }

# The app contains a small amount of EditText reflection for Android's internal
# editor implementation. This is runtime Android-framework reflection, not app
# class reflection, so no blanket -keep rule is required here.

# Optional libraries can contain references that are not present on every API.
-dontwarn javax.annotation.**
