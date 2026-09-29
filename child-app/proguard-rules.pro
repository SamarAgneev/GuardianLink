# child-app/proguard-rules.pro
# ─────────────────────────────────────────────────────────────────────────────
# GuardianLink Child App ProGuard Rules
# ─────────────────────────────────────────────────────────────────────────────

# ── Firebase ──────────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep Firestore model classes (data classes used in toObject())
-keep class com.guardianlink.common.model.** { *; }
-keepclassmembers class com.guardianlink.common.model.** { *; }

# ── Hilt ──────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# ── WebRTC ────────────────────────────────────────────────────────────────────
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ── CameraX ───────────────────────────────────────────────────────────────────
-keep class androidx.camera.** { *; }

# ── WorkManager ───────────────────────────────────────────────────────────────
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }

# ── Room ──────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ── Security ──────────────────────────────────────────────────────────────────
-keep class androidx.security.crypto.** { *; }

# ── Accessibility / Admin ─────────────────────────────────────────────────────
-keep class com.guardianlink.child.accessibility.** { *; }
-keep class com.guardianlink.child.admin.** { *; }

# ── Receivers / Services ──────────────────────────────────────────────────────
-keep class com.guardianlink.child.receiver.** { *; }
-keep class com.guardianlink.child.service.** { *; }
-keep class com.guardianlink.child.vpn.** { *; }
-keep class com.guardianlink.child.firebase.** { *; }
-keep class com.guardianlink.child.worker.** { *; }

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── Serialization ─────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

# ── Timber ────────────────────────────────────────────────────────────────────
-dontwarn org.jetbrains.annotations.**
