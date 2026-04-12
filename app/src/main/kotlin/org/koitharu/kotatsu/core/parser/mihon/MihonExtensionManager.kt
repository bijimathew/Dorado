package org.koitharu.kotatsu.core.parser.mihon

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.online.HttpSource
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.parser.mihon.repo.MihonPrivateExtensionStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonExtensionManager @Inject constructor(
	@ApplicationContext private val context: Context,
	private val injektBridge: dagger.Lazy<MihonInjektBridge>,
	private val privateExtensionStore: MihonPrivateExtensionStore,
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
		val extensionPackages = (
			MihonExtensionPackageUtil.getInstalledPackages(pm)
				.asSequence()
				.map { MihonInstalledExtensionPackage(it, isPrivate = false) } +
			privateExtensionStore.listInstalledPackages()
				.asSequence()
				.map { MihonInstalledExtensionPackage(it, isPrivate = true) }
			)
			.filter { extensionPackage ->
				val pkgInfo = if (extensionPackage.isPrivate) {
					extensionPackage.packageInfo
				} else {
					MihonExtensionPackageUtil.refreshPackageInfoIfNeeded(pm, extensionPackage.packageInfo)
				}
				val isExtension = MihonExtensionPackageUtil.isMihonExtension(pkgInfo)
				if (isExtension) {
					Log.w(
						TAG,
						"Found ${if (extensionPackage.isPrivate) "private" else "shared"} Mihon extension candidate ${pkgInfo.packageName}",
					)
				}
				isExtension
			}
			.groupBy { it.packageInfo.packageName }
			.mapNotNull { (_, packages) ->
				MihonExtensionPackageUtil.selectPreferred(
					shared = packages.firstOrNull { !it.isPrivate },
					private = packages.firstOrNull { it.isPrivate },
				)
			}
			.toList()
		Log.w(TAG, "Scanning ${extensionPackages.size} Mihon extension packages")
		return extensionPackages
			.asSequence()
			.flatMap { loadSourcesFromPackage(pm, it).asSequence() }
			.sortedBy { it.wrapper.displayName?.lowercase() ?: it.wrapper.packageName.lowercase() }
			.toList()
	}

	private fun loadSourcesFromPackage(
		pm: PackageManager,
		extensionPackage: MihonInstalledExtensionPackage,
	): List<LoadedSource> {
		val completeInfo = if (extensionPackage.isPrivate) {
			extensionPackage.packageInfo
		} else {
			MihonExtensionPackageUtil.refreshPackageInfoIfNeeded(pm, extensionPackage.packageInfo)
		}
		val appInfo = completeInfo.applicationInfo ?: return emptyList()
		val metaData = appInfo.metaData ?: return emptyList()
		val sourceClassName = metaData.getString(MihonExtensionPackageUtil.METADATA_SOURCE_CLASS)
			?: metaData.getString(MihonExtensionPackageUtil.METADATA_SOURCE_FACTORY)
			?: return emptyList()
		val apkPath = appInfo.sourceDir ?: return emptyList()
		val libVersion = MihonExtensionPackageUtil.parseLibVersion(completeInfo.versionName)
		if (libVersion != null &&
			(libVersion < MihonExtensionPackageUtil.LIB_VERSION_MIN ||
				libVersion > MihonExtensionPackageUtil.LIB_VERSION_MAX)
		) {
			Log.i(TAG, "Skipping ${completeInfo.packageName}: unsupported lib version $libVersion")
			return emptyList()
		}
		return runCatching {
			Log.w(
				TAG,
				"Loading ${if (extensionPackage.isPrivate) "private" else "shared"} Mihon extension package ${completeInfo.packageName} from $apkPath",
			)
			val loader = ChildFirstPathClassLoader(
				dexPath = apkPath,
				librarySearchPath = appInfo.nativeLibraryDir,
				parent = context.classLoader,
			)
			val loadedClass = Class.forName(
				MihonExtensionPackageUtil.resolveEntryClassName(completeInfo.packageName, sourceClassName),
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

	companion object {
		private const val TAG = "MihonExtensionManager"
	}
}
