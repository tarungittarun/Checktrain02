# R8 is on for release builds (see app/build.gradle). The two rules below are what the zero-reflection
# design actually needs:

# ViewBinding, RecyclerView and the lifecycle VMs are all reached from generated code whose classes
# live in the app package; keep their members so the reflective plumbing Android itself does survives.
-keep class in.opentrack.india.databinding.** { *; }
-keep class in.opentrack.india.ui.*Activity { *; }
-keepclassmembers class in.opentrack.india.ui.**ViewModel { *; }

# The snapshot codec reads map keys that mirror the wire contract; the names are data, not code.
-keepclassmembers class in.opentrack.india.remote.SnapshotCodec$Wire { *; }

# Kotlin stdlib / coroutines hygiene (AGP supplies most of this; kept explicit so a future AGP bump
# cannot silently start shrinking away a coroutine intrinsic).
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Release diagnostics: keep line numbers for the Play crash report, drop nothing else.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
