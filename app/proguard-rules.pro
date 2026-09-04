# R8 rules for the release build. Task W8.7.
#
# The release build has been running with `proguardFiles(... , "proguard-rules.pro")` and
# no such file, which R8 reports as a warning and then carries on with the default
# configuration alone. That is worse than it sounds: everything below is a rule the default
# configuration does not know it needs, and each one is a failure that appears only in the
# release APK on a handset -- which is the build the demonstration runs and the last one
# anybody tests.
#
# The failures these prevent share a shape: a name that only exists at runtime. R8 cannot
# see a call that arrives from C, from a serializer generated at compile time, or from a
# reflective lookup, so it removes or renames the target and the crash lands somewhere far
# from the cause.

# ── sherpa-onnx: called from native code, by name ────────────────────────────
#
# libsherpa-onnx-jni.so resolves these classes and their fields through JNI
# `FindClass`/`GetFieldID`, using the names as written. Renaming any of them turns a working
# recogniser into a `NoSuchMethodError` at first decode, on device, in release only.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── our own native entry points ──────────────────────────────────────────────
#
# The RNNoise binding declares `external` methods that librnnoise_jni.so looks up by name.
# The library is not built yet (W7.6), which makes this rule easy to forget until it is.
-keep class org.itantra.audio.RnNoiseSuppressor { *; }

# ── kotlinx.serialization: generated serializers ─────────────────────────────
#
# The plugin generates a `Companion.serializer()` and a `$$serializer` for every
# @Serializable class. Nothing in the source calls them directly -- the call is synthesised
# -- so R8 sees them as unreachable and strips them, and the manifest, the rule files and
# the template profile all fail to parse at install time.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.itantra.**$$serializer { *; }
-keepclassmembers class org.itantra.** {
    *** Companion;
}
-keepclasseswithmembers class org.itantra.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── enum values() over the wire ──────────────────────────────────────────────
#
# Language, MessageType and TransportClass are read back from serialised names and from
# `entries`. R8 can rewrite an enum it believes is only compared by identity.
-keepclassmembers enum org.itantra.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── keep the reasons, not just the code ──────────────────────────────────────
#
# Line numbers survive so a crash report from a rehearsal is readable. The source file name
# is renamed rather than kept, which is the usual compromise: the stack trace stays useful
# and the class layout is not published.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
