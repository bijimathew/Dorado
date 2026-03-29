package org.koitharu.kotatsu.explore.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.dao.MangaSourcesDao
import org.koitharu.kotatsu.core.db.entity.MangaSourceEntity
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.external.ExternalMangaSource
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionManager
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.flattenLatest
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val mihonExtensionManager: MihonExtensionManager,
) {

	data class ParserSourceSnapshot(
		val source: MangaSource,
		val isEnabled: Boolean,
		val addedIn: Int,
	)

	private val isNewSourcesAssimilated = AtomicBoolean(false)
	private val dao: MangaSourcesDao
		get() = db.getSourcesDao()

	val allMangaSources: Set<MangaParserSource> = Collections.unmodifiableSet(
		EnumSet.noneOf<MangaParserSource>(MangaParserSource::class.java).also {
            MangaParserSource.entries.filterNotTo(it, MangaParserSource::isBroken)
        }
	)

	suspend fun getEnabledSources(): List<MangaSource> {
		val mihonSources = getMihonSources()
		assimilateAvailableSources(mihonSources)
		val order = settings.sourcesSortOrder
		val enabled = dao.findAll(!settings.isAllSourcesEnabled, order).toSources(
			skipNsfwSources = settings.isNsfwContentDisabled,
			sortOrder = order,
			hideBrokenSources = settings.isBrokenSourcesHidden,
			mihonSources = mihonSources.associateBy { it.name },
		)
		val external = getExternalSources()
		return ArrayList<MangaSourceInfo>(enabled.size + external.size).also { list ->
			external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
			list.addAll(enabled)
		}
	}

	suspend fun getPinnedSources(): Set<MangaSource> {
		val mihonSources = getMihonSources()
		assimilateAvailableSources(mihonSources)
		val skipNsfw = settings.isNsfwContentDisabled
		val hideBroken = settings.isBrokenSourcesHidden
		val mihonByName = mihonSources.associateBy { it.name }
		return buildSet {
			addAll(getExternalSources())
			addAll(dao.findAllPinned().mapNotNullToSet {
				it.source.toInstalledSourceOrNull(mihonByName)?.takeUnless { x ->
					(skipNsfw && x.isNsfw()) || (hideBroken && x is MangaParserSource && x.isBroken)
				}
			})
		}
	}

	suspend fun getTopSources(limit: Int): List<MangaSource> {
		val mihonSources = getMihonSources()
		assimilateAvailableSources(mihonSources)
		return dao.findLastUsed(limit).toSources(
			skipNsfwSources = settings.isNsfwContentDisabled,
			sortOrder = null,
			hideBrokenSources = settings.isBrokenSourcesHidden,
			mihonSources = mihonSources.associateBy { it.name },
		)
	}

	suspend fun getDisabledSources(): Set<MangaSource> {
		val mihonSources = getMihonSources()
		assimilateAvailableSources(mihonSources)
		if (settings.isAllSourcesEnabled) {
			return emptySet()
		}
		val skipNsfw = settings.isNsfwContentDisabled
		val hideBroken = settings.isBrokenSourcesHidden
		val result = LinkedHashSet<MangaSource>(allMangaSources.size + mihonSources.size)
		allMangaSources.filterNotTo(result) { source ->
			(skipNsfw && source.isNsfw()) || (hideBroken && source.isBroken)
		}
		mihonSources.filterNotTo(result) { skipNsfw && it.isNsfw() }
		val enabled = dao.findAllEnabledNames()
		result.removeAll { it.name in enabled }
		return result
	}

	suspend fun queryParserSources(
		isDisabledOnly: Boolean,
		isNewOnly: Boolean,
		excludeBroken: Boolean,
		types: Set<ContentType>,
		query: String?,
		locale: String?,
		isMihonOnly: Boolean,
		sortOrder: SourcesSortOrder?,
		snapshot: List<ParserSourceSnapshot>? = null,
	): List<MangaSource> {
		val entries = snapshot ?: getParserSourcesSnapshot()
		val coroutineContext = currentCoroutineContext()
		val sources = entries.mapTo(ArrayList(entries.size)) { it.source }
		val hideBrokenSources = settings.isBrokenSourcesHidden
		if (settings.isNsfwContentDisabled) {
			sources.removeAll { it.isNsfw() }
		}
		coroutineContext.ensureActive()
		if (hideBrokenSources) {
			sources.removeAll { it is MangaParserSource && it.isBroken }
		}
		coroutineContext.ensureActive()
		if (isDisabledOnly && !settings.isAllSourcesEnabled) {
			val enabledSources = entries.asSequence()
				.filter { it.isEnabled }
				.mapTo(HashSet(entries.size)) { it.source }
			sources.removeAll(enabledSources)
		}
		coroutineContext.ensureActive()
		if (isNewOnly) {
			val newSources = entries.asSequence()
				.filter { it.addedIn == BuildConfig.VERSION_CODE }
				.mapTo(HashSet(entries.size)) { it.source }
			sources.retainAll(newSources)
		}
		coroutineContext.ensureActive()
		if (locale != null) {
			sources.retainAll { source ->
				when (source) {
					is MangaParserSource -> source.locale == locale
					is MihonMangaSource -> source.resolved().locale == locale
					else -> false
				}
			}
		}
		coroutineContext.ensureActive()
		if (isMihonOnly) {
			sources.retainAll { it is MihonMangaSource }
		}
		coroutineContext.ensureActive()
		if (excludeBroken && !hideBrokenSources) {
			sources.removeAll { it is MangaParserSource && it.isBroken }
		}
		coroutineContext.ensureActive()
		if (types.isNotEmpty()) {
			sources.retainAll { source ->
				when (source) {
					is MangaParserSource -> source.contentType in types
					is MihonMangaSource -> ContentType.MANGA in types
					else -> false
				}
			}
		}
		coroutineContext.ensureActive()
		if (!query.isNullOrEmpty()) {
			var checked = 0
			val iterator = sources.listIterator()
			while (iterator.hasNext()) {
				val source = iterator.next()
				if (!source.getTitle(context).contains(query, ignoreCase = true) &&
					!source.name.contains(query, ignoreCase = true)
				) {
					iterator.remove()
				}
				if (++checked % 32 == 0) {
					coroutineContext.ensureActive()
				}
			}
		}
		if (sortOrder == SourcesSortOrder.ALPHABETIC) {
			sources.sortBy { it.getTitle(context) }
		}
		return sources
	}

	suspend fun getParserSourcesSnapshot(): List<ParserSourceSnapshot> {
		val mihonSources = getMihonSources()
		assimilateAvailableSources(mihonSources)
		val mihonByName = mihonSources.associateBy { it.name }
		return dao.findAll().mapNotNull { entity ->
			val source = entity.source.toInstalledSourceOrNull(mihonByName) ?: return@mapNotNull null
			if (source is MangaParserSource && source !in allMangaSources) {
				return@mapNotNull null
			}
			ParserSourceSnapshot(
				source = source,
				isEnabled = entity.isEnabled,
				addedIn = entity.addedIn,
			)
		}
	}

	fun observeIsEnabled(source: MangaSource): Flow<Boolean> {
		return dao.observeIsEnabled(source.name).onStart { ensureSourceTracked(source) }
	}

	fun observeEnabledSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeHideBrokenSources(),
			observeAllEnabled(),
			observeMihonSources(),
		) { skipNsfw, hideBroken, isAllSourcesEnabled, mihonSources ->
			assimilateAvailableSources(mihonSources)
			val mihonByName = mihonSources.associateBy { it.name }
			dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL).map { sources ->
				sources.count {
					it.source.toInstalledSourceOrNull(mihonByName)?.let { source ->
						(!skipNsfw || !source.isNsfw()) &&
							(!hideBroken || source !is MangaParserSource || !source.isBroken)
					} == true
				}
			}
		}.flattenLatest().distinctUntilChanged().onStart { assimilateAvailableSources() }
	}

	fun observeAvailableSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeHideBrokenSources(),
			observeAllEnabled(),
			observeMihonSources(),
		) { skipNsfw, hideBroken, isAllSourcesEnabled, mihonSources ->
			if (isAllSourcesEnabled) {
				return@combine flowOf(0)
			}
			assimilateAvailableSources(mihonSources)
			val available = LinkedHashMap<String, MangaSource>(allMangaSources.size + mihonSources.size)
			allMangaSources.forEach { source ->
				if ((!skipNsfw || !source.isNsfw()) && (!hideBroken || !source.isBroken)) {
					available[source.name] = source
				}
			}
			mihonSources.forEach { source ->
				if (!skipNsfw || !source.isNsfw()) {
					available[source.name] = source
				}
			}
			dao.observeAll(enabledOnly = true, order = SourcesSortOrder.MANUAL).map { enabledSources ->
				val enabled = enabledSources.mapTo(HashSet(enabledSources.size)) { it.source }
				available.count { (name, _) -> name !in enabled }
			}
		}.flattenLatest().distinctUntilChanged().onStart { assimilateAvailableSources() }
	}

	fun observeEnabledSources(): Flow<List<MangaSourceInfo>> = combine(
		observeIsNsfwDisabled(),
		observeHideBrokenSources(),
		observeAllEnabled(),
		observeSortOrder(),
		observeMihonSources(),
	) { skipNsfw, hideBroken, allEnabled, order, mihonSources ->
		assimilateAvailableSources(mihonSources)
		dao.observeAll(!allEnabled, order).map {
			it.toSources(
				skipNsfwSources = skipNsfw,
				sortOrder = order,
				hideBrokenSources = hideBroken,
				mihonSources = mihonSources.associateBy { source -> source.name },
			)
		}
	}.flattenLatest()
		.onStart { assimilateAvailableSources() }
		.combine(observeExternalSources()) { enabled, external ->
			val list = ArrayList<MangaSourceInfo>(enabled.size + external.size)
			external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
			list.addAll(enabled)
			list
		}

	fun observeAll(): Flow<List<Pair<MangaSource, Boolean>>> = dao.observeAll().map { entities ->
		val result = ArrayList<Pair<MangaSource, Boolean>>(entities.size)
		for (entity in entities) {
			val source = entity.source.toParserSourceOrNull() ?: continue
			if (source in allMangaSources) {
				result.add(source to entity.isEnabled)
			}
		}
		result
	}.onStart { assimilateNewParserSources() }

	suspend fun setSourcesEnabled(sources: Collection<MangaSource>, isEnabled: Boolean): ReversibleHandle {
		setSourcesEnabledImpl(sources, isEnabled)
		return ReversibleHandle {
			setSourcesEnabledImpl(sources, !isEnabled)
		}
	}

	suspend fun setSourcesEnabledExclusive(sources: Set<MangaSource>) {
		val mihonSources = getMihonSources()
		db.withTransaction {
			assimilateAvailableSources(mihonSources)
			for (s in allMangaSources) {
				dao.setEnabled(s.name, s in sources)
			}
			for (s in mihonSources) {
				dao.setEnabled(s.name, s in sources)
			}
		}
	}

	suspend fun disableAllSources() {
		db.withTransaction {
			assimilateAvailableSources()
			dao.disableAllSources()
		}
	}

	suspend fun setPositions(sources: List<MangaSource>) {
		db.withTransaction {
			for ((index, item) in sources.withIndex()) {
				dao.setSortKey(item.name, index)
			}
		}
	}

	fun observeHasNewSources(): Flow<Boolean> = observeIsNsfwDisabled().map { skipNsfw ->
		val sources = dao.findAllFromVersion(BuildConfig.VERSION_CODE).toSources(
			skipNsfwSources = skipNsfw,
			sortOrder = null,
			hideBrokenSources = settings.isBrokenSourcesHidden,
			mihonSources = emptyMap(),
		)
		sources.isNotEmpty() && sources.size != allMangaSources.size
	}.onStart { assimilateNewParserSources() }

	fun observeHasNewSourcesForBadge(): Flow<Boolean> = combine(
		settings.observeAsFlow(AppSettings.KEY_SOURCES_VERSION) { sourcesVersion },
		observeIsNsfwDisabled(),
		observeHideBrokenSources(),
	) { version, skipNsfw, hideBroken ->
		if (version < BuildConfig.VERSION_CODE) {
			val sources = dao.findAllFromVersion(version).toSources(
				skipNsfwSources = skipNsfw,
				sortOrder = null,
				hideBrokenSources = hideBroken,
				mihonSources = emptyMap(),
			)
			sources.isNotEmpty()
		} else {
			false
		}
	}.onStart { assimilateNewParserSources() }

	fun clearNewSourcesBadge() {
		settings.sourcesVersion = BuildConfig.VERSION_CODE
	}

	fun observeInstalledMihonSources(): Flow<List<MihonMangaSource>> = observeMihonSources()

	suspend fun refreshInstalledMihonSources(): Boolean {
		mihonExtensionManager.invalidate()
		return assimilateInstalledMihonSources(getMihonSources())
	}

	private suspend fun assimilateAvailableSources(): Boolean {
		return assimilateAvailableSources(getMihonSources())
	}

	private suspend fun assimilateAvailableSources(mihonSources: List<MihonMangaSource>): Boolean {
		val parsersUpdated = assimilateNewParserSources()
		val mihonUpdated = assimilateInstalledMihonSources(mihonSources)
		return parsersUpdated || mihonUpdated
	}

	private suspend fun assimilateNewParserSources(): Boolean {
		if (isNewSourcesAssimilated.getAndSet(true)) {
			return false
		}
		val new = getNewParserSources()
		if (new.isEmpty()) {
			return false
		}
		var maxSortKey = dao.getMaxSortKey()
		val isAllEnabled = settings.isAllSourcesEnabled
		val entities = new.map { x ->
			MangaSourceEntity(
				source = x.name,
				isEnabled = isAllEnabled,
				sortKey = ++maxSortKey,
				addedIn = BuildConfig.VERSION_CODE,
				lastUsedAt = 0,
				isPinned = false,
				cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
			)
		}
		dao.insertIfAbsent(entities)
		return true
	}

	private suspend fun assimilateInstalledMihonSources(mihonSources: List<MihonMangaSource>): Boolean {
		if (mihonSources.isEmpty()) {
			return false
		}
		val known = dao.findAll().mapTo(HashSet()) { it.source }
		val missing = mihonSources.filterNot { it.name in known }
		if (missing.isEmpty()) {
			return false
		}
		var maxSortKey = dao.getMaxSortKey()
		val isAllEnabled = settings.isAllSourcesEnabled
		val entities = missing.map { source ->
			MangaSourceEntity(
				source = source.name,
				isEnabled = isAllEnabled,
				sortKey = ++maxSortKey,
				addedIn = BuildConfig.VERSION_CODE,
				lastUsedAt = 0,
				isPinned = false,
				cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
			)
		}
		dao.insertIfAbsent(entities)
		return true
	}

	suspend fun isSetupRequired(): Boolean {
		return settings.sourcesVersion == 0 && dao.findAllEnabledNames().isEmpty()
	}

	suspend fun setIsPinned(sources: Collection<MangaSource>, isPinned: Boolean): ReversibleHandle {
		setSourcesPinnedImpl(sources, isPinned)
		return ReversibleHandle {
			setSourcesEnabledImpl(sources, !isPinned)
		}
	}

	suspend fun trackUsage(source: MangaSource) {
		if (!settings.isIncognitoModeEnabled(source.isNsfw())) {
			ensureSourceTracked(source)
			dao.setLastUsed(source.name, System.currentTimeMillis())
		}
	}

	private suspend fun setSourcesEnabledImpl(sources: Collection<MangaSource>, isEnabled: Boolean) {
		if (sources.size == 1) { // fast path
			dao.setEnabled(sources.first().name, isEnabled)
			return
		}
		db.withTransaction {
			for (source in sources) {
				dao.setEnabled(source.name, isEnabled)
			}
		}
	}

	private suspend fun getNewParserSources(): MutableSet<out MangaSource> {
		val entities = dao.findAll()
		val result = EnumSet.copyOf(allMangaSources)
		for (e in entities) {
			result.remove(e.source.toParserSourceOrNull() ?: continue)
		}
		return result
	}

	private suspend fun setSourcesPinnedImpl(sources: Collection<MangaSource>, isPinned: Boolean) {
		if (sources.size == 1) { // fast path
			dao.setPinned(sources.first().name, isPinned)
			return
		}
		db.withTransaction {
			for (source in sources) {
				dao.setPinned(source.name, isPinned)
			}
		}
	}

	private fun observeExternalSources(): Flow<List<ExternalMangaSource>> {
		return callbackFlow {
			val receiver = object : BroadcastReceiver() {
				override fun onReceive(context: Context?, intent: Intent?) {
					trySendBlocking(intent)
				}
			}
			ContextCompat.registerReceiver(
				context,
				receiver,
				IntentFilter().apply {
					addAction(Intent.ACTION_PACKAGE_ADDED)
					addAction(Intent.ACTION_PACKAGE_VERIFIED)
					addAction(Intent.ACTION_PACKAGE_REPLACED)
					addAction(Intent.ACTION_PACKAGE_REMOVED)
					addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
					addDataScheme("package")
				},
				ContextCompat.RECEIVER_EXPORTED,
			)
			awaitClose { context.unregisterReceiver(receiver) }
		}.onStart {
			emit(null)
		}.map {
			getExternalSources()
		}.distinctUntilChanged()
			.conflate()
	}

	private fun observeMihonSources(): Flow<List<MihonMangaSource>> {
		return callbackFlow {
			val receiver = object : BroadcastReceiver() {
				override fun onReceive(context: Context?, intent: Intent?) {
					trySendBlocking(intent)
				}
			}
			ContextCompat.registerReceiver(
				context,
				receiver,
				IntentFilter().apply {
					addAction(Intent.ACTION_PACKAGE_ADDED)
					addAction(Intent.ACTION_PACKAGE_VERIFIED)
					addAction(Intent.ACTION_PACKAGE_REPLACED)
					addAction(Intent.ACTION_PACKAGE_REMOVED)
					addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
					addDataScheme("package")
				},
				ContextCompat.RECEIVER_EXPORTED,
			)
			awaitClose { context.unregisterReceiver(receiver) }
		}.onStart {
			emit(null)
		}.map {
			mihonExtensionManager.invalidate()
			getMihonSources()
		}.distinctUntilChanged()
			.conflate()
	}

	fun getExternalSources(): List<ExternalMangaSource> = context.packageManager.queryIntentContentProviders(
		Intent("app.kotatsu.parser.PROVIDE_MANGA"), 0,
	).map { resolveInfo ->
		ExternalMangaSource(
			packageName = resolveInfo.providerInfo.packageName,
			authority = resolveInfo.providerInfo.authority,
		)
	}

	private suspend fun getMihonSources(): List<MihonMangaSource> = runInterruptible(Dispatchers.IO) {
		mihonExtensionManager.getInstalledSources()
	}

	private suspend fun ensureSourceTracked(source: MangaSource) {
		when (source) {
			is MangaParserSource -> assimilateNewParserSources()
			is MihonMangaSource -> {
				val installed = mihonExtensionManager.resolve(source)?.wrapper ?: return
				assimilateInstalledMihonSources(listOf(installed))
			}
		}
	}

	private fun List<MangaSourceEntity>.toSources(
		skipNsfwSources: Boolean,
		sortOrder: SourcesSortOrder?,
		hideBrokenSources: Boolean,
		mihonSources: Map<String, MihonMangaSource>,
	): MutableList<MangaSourceInfo> {
		val isAllEnabled = settings.isAllSourcesEnabled
		val result = ArrayList<MangaSourceInfo>(size)
		for (entity in this) {
			val source = entity.source.toInstalledSourceOrNull(mihonSources) ?: continue
			if (skipNsfwSources && source.isNsfw()) {
				continue
			}
			if (hideBrokenSources && source is MangaParserSource && source.isBroken) {
				continue
			}
			result.add(
				MangaSourceInfo(
					mangaSource = source,
					isEnabled = entity.isEnabled || isAllEnabled,
					isPinned = entity.isPinned,
				),
			)
		}
		if (sortOrder == SourcesSortOrder.ALPHABETIC) {
			result.sortWith(compareBy<MangaSourceInfo> { !it.isPinned }.thenBy { it.getTitle(context) })
		}
		return result
	}

	private fun observeIsNsfwDisabled() = settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) {
		isNsfwContentDisabled
	}

	private fun observeHideBrokenSources() = settings.observeAsFlow(AppSettings.KEY_SOURCES_HIDE_BROKEN) {
		isBrokenSourcesHidden
	}

	private fun observeSortOrder() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ORDER) {
		sourcesSortOrder
	}

	private fun observeAllEnabled() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ENABLED_ALL) {
		isAllSourcesEnabled
	}

	private fun String.toParserSourceOrNull(): MangaParserSource? = MangaParserSource.entries.find { it.name == this }

	private fun String.toInstalledSourceOrNull(mihonSources: Map<String, MihonMangaSource>): MangaSource? {
		return toParserSourceOrNull() ?: mihonSources[this]
	}
}
