# R8 / ProGuard rules for Otto release build.
# Default Android rules cover most things; the extras below are for libraries
# and patterns the optimiser cannot see (annotations, reflection, JNI).

# Keep stack traces useful when triaging Crashlytics / on-device crash logs.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotation metadata so Room + Jetpack reflection-based libs still work.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- Room ---------------------------------------------------------------------
# Room generates implementation classes for @Dao/@Database. Keep them and their
# default constructors so Room's DB builder can instantiate them at runtime.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class **_Impl { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# --- App database classes -----------------------------------------------------
# Conservative — keep all .db package fields so column annotations resolve.
-keep class com.example.obd.db.** { *; }

# --- Gemini JSON wire model ---------------------------------------------------
# We only use org.json (no reflection-driven serialiser), but keep all model-style
# classes on the off chance someone refactors to Gson/Moshi. Cheap to be safe.
-keep class com.example.obd.AiVisionProvider { *; }
-keep class com.example.obd.GeminiVisionProvider { *; }

# --- Reflection-touched controllers ------------------------------------------
# MainActivity dynamically instantiates these via the nav listener and
# instanceof checks. R8 is usually fine here but keep their public surface so
# stack traces stay readable in shrunken builds.
-keepnames class com.example.obd.*Controller { *; }
-keepnames class com.example.obd.MainActivity

# --- ELM327 command hierarchy -------------------------------------------------
# Used via reflection through Supplier<List<ObdCommand>> in PollGroup. Keep so
# the dispatch table stays intact post-shrink.
-keep class com.example.obd.ObdCommand { *; }
-keep class * extends com.example.obd.ObdCommand { *; }
-keep enum com.example.obd.PollGroup { *; }

# --- Application class --------------------------------------------------------
-keep class com.example.obd.ObdApp { *; }
-keep class com.example.obd.CrashHandler { *; }

# --- AppCompat / Material / AndroidX ----------------------------------------
# These libraries ship their own consumer ProGuard rules — kept here for
# belt-and-braces in case a transitive dep doesn't.
-dontwarn com.google.android.material.**
-dontwarn androidx.**

# --- MPAndroidChart -----------------------------------------------------------
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# --- Coroutines / Reflection-light libs -------------------------------------
# We don't use Kotlin coroutines but Material does transitively; suppress.
-dontwarn kotlinx.coroutines.**
