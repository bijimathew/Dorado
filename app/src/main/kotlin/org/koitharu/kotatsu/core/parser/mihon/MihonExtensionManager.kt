package org.koitharu.kotatsu.core.parser.mihon

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.HttpSource
import org.koitharu.kotatsu.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonExtensionManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val injektBridge: dagger.Lazy<MihonInjektBridge>,
) {

	data class LoadedSource(
		val wrapper: MihonMangaSource,
		val catalogueSource: CatalogueSource,
	)

	@Volatile
	private var cachedSources: List<LoadedSource>? = null

	fun invalidate() {
		cachedSources = null
	}

	fun getInstalledSources(): List<MihonMangaSource> = ensureLoaded().map { it.wrapper }

	fun resolve(source: MihonMangaSource): LoadedSource? {
		return ensureLoaded().firstOrNull { it.wrapper.matches(source) }
	}

	private fun ensureLoaded(): List<LoadedSource> {
		cachedSources?.let { return it }
		synchronized(this) {
			cachedSources?.let { return it }
			Log.w(TAG, "Initializing Mihon extension manager")
			injektBridge.get().initialize()
			return loadInstalledSources().also {
				Log.w(TAG, "Loaded ${it.size} Mihon catalogue sources")
				cachedSources = it
			}
		}
	}

	private fun loadInstalledSources(): List<LoadedSource> {
		val pm = context.packageManager
		val installedPackages = getInstalledPackages(pm)
		Log.w(TAG, "Scanning ${installedPackages.size} installed packages for Mihon extensions")
		return installedPackages
			.asSequence()
			.filter { pkgInfo ->
				val isExtension = isMihonExtension(pkgInfo)
				if (isExtension) {
					Log.w(TAG, "Found Mihon extension candidate ${pkgInfo.packageName}")
				}
				isExtension
			}
			.flatMap { loadSourcesFromPackage(pm, it).asSequence() }
			.sortedBy { it.wrapper.displayName?.lowercase() ?: it.wrapper.packageName.lowercase() }
			.toList()
	}

	private fun loadSourcesFromPackage(pm: PackageManager, pkgInfo: PackageInfo): List<LoadedSource> {
		val completeInfo = refreshPackageInfoIfNeeded(pm, pkgInfo)
		val appInfo = completeInfo.applicationInfo ?: return emptyList()
		val metaData = appInfo.metaData ?: return emptyList()
		val sourceClassName = metaData.getString(METADATA_SOURCE_CLASS)
			?: metaData.getString(METADATA_SOURCE_FACTORY)
			?: return emptyList()
		val apkPath = appInfo.sourceDir ?: return emptyList()
		val libVersion = parseLibVersion(completeInfo.versionName)
		if (libVersion != null && (libVersion < LIB_VERSION_MIN || libVersion > LIB_VERSION_MAX)) {
			Log.i(TAG, "Skipping ${completeInfo.packageName}: unsupported lib version $libVersion")
			return emptyList()
		}
		return runCatching {
			Log.w(TAG, "Loading Mihon extension package ${completeInfo.packageName} from $apkPath")
			val loader = ChildFirstPathClassLoader(
				dexPath = apkPath,
				librarySearchPath = appInfo.nativeLibraryDir,
				parent = context.classLoader,
			)
			val loadedClass = Class.forName(
				resolveEntryClassName(completeInfo.packageName, sourceClassName),
				false,
				loader,
			)
			if (BuildConfig.DEBUG) {
				Log.d(
					TAG,
					"Loaded entry class ${loadedClass.name} for ${completeInfo.packageName}, " +
						"factory=${SourceFactory::class.java.isAssignableFrom(loadedClass)}, " +
						"source=${Source::class.java.isAssignableFrom(loadedClass)}",
				)
			}
			val sourceInstances = when {
				SourceFactory::class.java.isAssignableFrom(loadedClass) -> {
					val factory = loadedClass.getDeclaredConstructor().newInstance() as SourceFactory
					factory.createSources()
				}

				Source::class.java.isAssignableFrom(loadedClass) -> {
					listOf(loadedClass.getDeclaredConstructor().newInstance() as Source)
				}

				else -> emptyList()
			}
			if (BuildConfig.DEBUG) {
				Log.d(
					TAG,
					"Created ${sourceInstances.size} Mihon source instances for ${completeInfo.packageName}: " +
						sourceInstances.joinToString { it.javaClass.name },
				)
			}
			Log.w(
				TAG,
				"Created ${sourceInstances.size} source instances for ${completeInfo.packageName}",
			)
			sourceInstances.mapNotNull { source ->
				val catalogueSource = source as? CatalogueSource ?: run {
					Log.w(TAG, "Ignoring non-catalogue Mihon source ${source.javaClass.name}")
					return@mapNotNull null
				}
				// Mihon's manifest flag is extension-wide, not source-wide.
				// Treating it as a hard source NSFW bit hides mixed catalogues such as MangaDex
				// from Kaisoku when "Disable NSFW" is enabled.
				val wrapper = MihonMangaSource(
					packageName = completeInfo.packageName,
					sourceId = catalogueSource.id,
					displayName = catalogueSource.name,
					locale = catalogueSource.lang.takeIf { it.isNotBlank() },
					isNsfwSource = false,
				)
				MihonSourceRegistry.register(
					sourceInstance = catalogueSource,
					source = wrapper,
					defaultReferer = (catalogueSource as? HttpSource)?.baseUrl?.trimEnd('/')?.plus("/"),
				)
				Log.w(TAG, "Registered Mihon source ${wrapper.name} (${wrapper.displayName})")
				LoadedSource(
					wrapper = wrapper,
					catalogueSource = catalogueSource,
				)
			}
		}.onFailure {
			Log.w(TAG, "Failed to load ${completeInfo.packageName}", it)
		}.getOrDefault(emptyList())
	}

	private fun isMihonExtension(pkgInfo: PackageInfo): Boolean {
		val completeInfo = refreshPackageInfoIfNeeded(context.packageManager, pkgInfo)
		val metaData = completeInfo.applicationInfo?.metaData
		val hasFeature = completeInfo.reqFeatures?.any { it.name == EXTENSION_FEATURE } == true
		val hasMetadata = metaData?.containsKey(METADATA_SOURCE_CLASS) == true ||
			metaData?.containsKey(METADATA_SOURCE_FACTORY) == true
		val looksLikeExtension = completeInfo.packageName.contains(".extension") ||
			completeInfo.packageName.startsWith("eu.kanade.tachiyomi.") ||
			completeInfo.packageName.startsWith("org.keiyoushi.")
		return hasFeature || (hasMetadata && looksLikeExtension)
	}

	private fun parseLibVersion(versionName: String?): Double? {
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

	private fun resolveEntryClassName(packageName: String, className: String): String {
		return if (className.startsWith('.')) {
			packageName + className
		} else {
			className
		}
	}

	private fun getInstalledPackages(pm: PackageManager): List<PackageInfo> {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(SCAN_FLAGS.toLong()))
		} else {
			@Suppress("DEPRECATION")
			pm.getInstalledPackages(SCAN_FLAGS)
		}
	}

	private fun refreshPackageInfoIfNeeded(pm: PackageManager, pkgInfo: PackageInfo): PackageInfo {
		val needsRefresh = pkgInfo.applicationInfo?.metaData == null || pkgInfo.reqFeatures == null
		if (!needsRefresh) {
			return pkgInfo
		}
		return getPackageInfoOrNull(pm, pkgInfo.packageName) ?: pkgInfo
	}

	private fun getPackageInfoOrNull(pm: PackageManager, packageName: String): PackageInfo? {
		return try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PACKAGE_QUERY_FLAGS.toLong()))
			} else {
				@Suppress("DEPRECATION")
				pm.getPackageInfo(packageName, PACKAGE_QUERY_FLAGS)
			}
		} catch (_: PackageManager.NameNotFoundException) {
			null
		}
	}

	companion object {
		private const val TAG = "MihonExtensionManager"
		private const val EXTENSION_FEATURE = "tachiyomi.extension"
		private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
		private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
		private const val METADATA_NSFW = "tachiyomi.extension.nsfw"
		private const val LIB_VERSION_MIN = 1.2
		private const val LIB_VERSION_MAX = 1.9
		private val SCAN_FLAGS = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS
		@Suppress("DEPRECATION")
		private val PACKAGE_QUERY_FLAGS = PackageManager.GET_META_DATA or
			PackageManager.GET_CONFIGURATIONS or
			PackageManager.GET_SIGNATURES or
			(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
	}
}
