package org.koitharu.kotatsu.core.parser.mihon.repo

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionPackageUtil
import org.koitharu.kotatsu.core.parser.mihon.MihonInstalledExtensionPackage
import org.koitharu.kotatsu.core.util.ext.subdir
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonPrivateExtensionStore @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	private val pm
		get() = context.packageManager

	private val extensionDir: File
		get() = context.filesDir.subdir(DIR_NAME)

	fun listInstalledPackages(): List<PackageInfo> {
		return extensionDir.listFiles()
			.orEmpty()
			.asSequence()
			.filter { it.isFile && it.extension == FILE_EXTENSION }
			.mapNotNull { MihonExtensionPackageUtil.getPackageArchiveInfoOrNull(pm, it) }
			.filter(MihonExtensionPackageUtil::isMihonExtension)
			.toList()
	}

	fun findInstalledPackage(pkgName: String): PackageInfo? {
		val target = File(extensionDir, "$pkgName.$FILE_EXTENSION")
		if (!target.isFile) {
			return null
		}
		return MihonExtensionPackageUtil.getPackageArchiveInfoOrNull(pm, target)
			?.takeIf(MihonExtensionPackageUtil::isMihonExtension)
	}

	private fun getInstalledPackage(pkgName: String): MihonInstalledExtensionPackage? {
		val privatePkg = findInstalledPackage(pkgName)?.let {
			MihonInstalledExtensionPackage(it, isPrivate = true)
		}
		val sharedPkg = MihonExtensionPackageUtil.getPackageInfoOrNull(pm, pkgName)
			?.takeIf(MihonExtensionPackageUtil::isMihonExtension)
			?.let { MihonInstalledExtensionPackage(it, isPrivate = false) }
		return MihonExtensionPackageUtil.selectPreferred(sharedPkg, privatePkg)
	}

	fun uninstall(pkgName: String): Boolean {
		val target = File(extensionDir, "$pkgName.$FILE_EXTENSION")
		val removed = target.delete()
		if (removed) {
			notifyChanged()
		}
		return removed
	}

	fun installDownloadedExtension(
		file: File,
		extension: MihonAvailableExtension,
	): InstallResult {
		val packageInfo = MihonExtensionPackageUtil.getPackageArchiveInfoOrNull(pm, file)
			?: return InstallResult.InvalidPackage
		if (!MihonExtensionPackageUtil.isMihonExtension(packageInfo)) {
			return InstallResult.InvalidPackage
		}
		if (packageInfo.packageName != extension.pkgName) {
			return InstallResult.PackageMismatch
		}
		val signatures = MihonExtensionPackageUtil.getSignatures(packageInfo)
			?: return InstallResult.UnsignedPackage
		if (extension.repo.signingKeyFingerprint !in signatures) {
			return InstallResult.UntrustedSignature
		}
		val currentPackage = getInstalledPackage(packageInfo.packageName)?.packageInfo
		if (currentPackage != null) {
			if (PackageInfoCompat.getLongVersionCode(packageInfo) <
				PackageInfoCompat.getLongVersionCode(currentPackage)
			) {
				return InstallResult.DowngradeNotAllowed
			}
			val currentSignatures = MihonExtensionPackageUtil.getSignatures(currentPackage)
			if (!currentSignatures.isNullOrEmpty() && !signatures.containsAll(currentSignatures)) {
				return InstallResult.SignatureMismatch
			}
		}
		val target = File(extensionDir, "${packageInfo.packageName}.$FILE_EXTENSION")
		return runCatching {
			target.delete()
			file.copyTo(target, overwrite = true)
			target.setReadOnly()
			notifyChanged()
		}.fold(
			onSuccess = { InstallResult.Success },
			onFailure = { error -> InstallResult.CopyFailed(error) },
		)
	}

	private fun notifyChanged() {
		context.sendBroadcast(
			Intent(ACTION_PRIVATE_EXTENSIONS_CHANGED).setPackage(context.packageName),
		)
	}

	sealed interface InstallResult {
		data object Success : InstallResult
		data object InvalidPackage : InstallResult
		data object PackageMismatch : InstallResult
		data object UnsignedPackage : InstallResult
		data object UntrustedSignature : InstallResult
		data object DowngradeNotAllowed : InstallResult
		data object SignatureMismatch : InstallResult
		data class CopyFailed(val error: Throwable) : InstallResult
	}

	companion object {
		const val ACTION_PRIVATE_EXTENSIONS_CHANGED =
			"org.koitharu.kotatsu.action.MIHON_PRIVATE_EXTENSIONS_CHANGED"

		private const val DIR_NAME = "mihon_ext"
		private const val FILE_EXTENSION = "apk"
	}
}
