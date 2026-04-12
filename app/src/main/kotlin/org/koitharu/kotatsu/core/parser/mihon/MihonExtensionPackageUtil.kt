package org.koitharu.kotatsu.core.parser.mihon

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal data class MihonInstalledExtensionPackage(
	val packageInfo: PackageInfo,
	val isPrivate: Boolean,
)

internal object MihonExtensionPackageUtil {

	const val EXTENSION_FEATURE = "tachiyomi.extension"
	const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
	const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
	const val LIB_VERSION_MIN = 1.2
	const val LIB_VERSION_MAX = 1.9

	private val scanFlags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS

	@Suppress("DEPRECATION")
	private val packageQueryFlags = PackageManager.GET_META_DATA or
		PackageManager.GET_CONFIGURATIONS or
		PackageManager.GET_SIGNATURES or
		(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

	fun getInstalledPackages(pm: PackageManager): List<PackageInfo> {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(scanFlags.toLong()))
		} else {
			@Suppress("DEPRECATION")
			pm.getInstalledPackages(scanFlags)
		}
	}

	fun getPackageInfoOrNull(pm: PackageManager, packageName: String): PackageInfo? {
		return try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(packageQueryFlags.toLong()))
			} else {
				@Suppress("DEPRECATION")
				pm.getPackageInfo(packageName, packageQueryFlags)
			}
		} catch (_: PackageManager.NameNotFoundException) {
			null
		}
	}

	fun getPackageArchiveInfoOrNull(pm: PackageManager, apkFile: File): PackageInfo? {
		val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(packageQueryFlags.toLong()))
		} else {
			@Suppress("DEPRECATION")
			pm.getPackageArchiveInfo(apkFile.absolutePath, packageQueryFlags)
		}
		return info?.also { pkgInfo ->
			pkgInfo.applicationInfo?.fixBasePaths(apkFile.absolutePath)
		}
	}

	fun refreshPackageInfoIfNeeded(pm: PackageManager, pkgInfo: PackageInfo): PackageInfo {
		val needsRefresh = pkgInfo.applicationInfo?.metaData == null || pkgInfo.reqFeatures == null
		if (!needsRefresh) {
			return pkgInfo
		}
		return getPackageInfoOrNull(pm, pkgInfo.packageName) ?: pkgInfo
	}

	fun isMihonExtension(pkgInfo: PackageInfo): Boolean {
		val metaData = pkgInfo.applicationInfo?.metaData
		val hasFeature = pkgInfo.reqFeatures?.any { it.name == EXTENSION_FEATURE } == true
		val hasMetadata = metaData?.containsKey(METADATA_SOURCE_CLASS) == true ||
			metaData?.containsKey(METADATA_SOURCE_FACTORY) == true
		val looksLikeExtension = pkgInfo.packageName.contains(".extension") ||
			pkgInfo.packageName.startsWith("eu.kanade.tachiyomi.") ||
			pkgInfo.packageName.startsWith("org.keiyoushi.")
		return hasFeature || (hasMetadata && looksLikeExtension)
	}

	fun parseLibVersion(versionName: String?): Double? {
		versionName ?: return null
		return runCatching {
			versionName.split('.').let { parts ->
				if (parts.size >= 2) {
					"${parts[0]}.${parts[1]}".toDouble()
				} else {
					parts[0].toDouble()
				}
			}
		}.getOrNull()
	}

	fun resolveEntryClassName(packageName: String, className: String): String {
		return if (className.startsWith('.')) {
			packageName + className
		} else {
			className
		}
	}

	fun getSignatures(pkgInfo: PackageInfo): List<String>? {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			val signingInfo = pkgInfo.signingInfo ?: return null
			if (signingInfo.hasMultipleSigners()) {
				signingInfo.apkContentsSigners
			} else {
				signingInfo.signingCertificateHistory
			}
		} else {
			@Suppress("DEPRECATION")
			pkgInfo.signatures
		}
			?.map { it.sha256Fingerprint() }
			?.toList()
	}

	fun selectPreferred(
		shared: MihonInstalledExtensionPackage?,
		private: MihonInstalledExtensionPackage?,
	): MihonInstalledExtensionPackage? {
		return when {
			shared == null -> private
			private == null -> shared
			PackageInfoCompat.getLongVersionCode(shared.packageInfo) >=
				PackageInfoCompat.getLongVersionCode(private.packageInfo) -> shared
			else -> private
		}
	}

	private fun ApplicationInfo.fixBasePaths(apkPath: String) {
		if (sourceDir == null) {
			sourceDir = apkPath
		}
		if (publicSourceDir == null) {
			publicSourceDir = apkPath
		}
	}

	private fun Signature.sha256Fingerprint(): String {
		return MessageDigest.getInstance("SHA-256")
			.digest(toByteArray())
			.joinToString(separator = "") { byte ->
				"%02x".format(Locale.US, byte)
			}
	}
}
