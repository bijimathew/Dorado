    -optimizationpasses 8
-dontobfuscate
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void checkExpressionValueIsNotNull(...);
	public static void checkNotNullExpressionValue(...);
	public static void checkReturnedValueIsNotNull(...);
	public static void checkFieldIsNotNull(...);
	public static void checkParameterIsNotNull(...);
	public static void checkNotNullParameter(...);
}

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn coil3.PlatformContext

-keep class org.koitharu.kotatsu.settings.NotificationSettingsLegacyFragment
-keep class org.koitharu.kotatsu.settings.about.changelog.ChangelogFragment

-keep class org.koitharu.kotatsu.core.exceptions.* { *; }
-keep class org.koitharu.kotatsu.core.prefs.ScreenshotsPolicy { *; }
-keep class org.koitharu.kotatsu.backups.ui.periodical.PeriodicalBackupSettingsFragment { *; }
-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.** {
    public <init>(...);
    public protected *;
}

-keep class org.acra.security.NoKeyStoreFactory { *; }
-keep class org.acra.config.DefaultRetryPolicy { *; }
-keep class org.acra.attachment.DefaultAttachmentProvider { *; }
-keep class org.acra.sender.JobSenderService

# Mihon extension support
# Hosted extension APKs depend on these shared host classes and on preserved
# generic signatures for Injekt's runtime type references.
-keepattributes Signature
-keepattributes Annotation
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations

-keep class eu.kanade.tachiyomi.** { *; }
-keep interface eu.kanade.tachiyomi.** { *; }
-keepclassmembers class eu.kanade.tachiyomi.** {
    public <init>(...);
    public protected *;
}

-keep class uy.kohesive.injekt.** { *; }
-keep interface uy.kohesive.injekt.** { *; }
-keepclassmembers class uy.kohesive.injekt.** {
    public <init>(...);
    public protected *;
}

-keep class rx.** { *; }
-keep interface rx.** { *; }
-dontwarn rx.**

-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepclassmembers class okhttp3.** {
    public <init>(...);
}
-keep class okio.** { *; }
-keep interface okio.** { *; }
-dontwarn okio.**
-dontwarn okhttp3.**

# Kotlin runtime used by hosted Mihon extensions.
# Release builds must keep these host classes available because extensions
# link against the host app's stdlib/coroutines/serialization artifacts.
-keep class kotlin.** { *; }
-keep interface kotlin.** { *; }
-dontwarn kotlin.**

-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}
-keepclassmembers class **$$serializer {
    *** INSTANCE;
}
-dontwarn kotlinx.serialization.**

-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
