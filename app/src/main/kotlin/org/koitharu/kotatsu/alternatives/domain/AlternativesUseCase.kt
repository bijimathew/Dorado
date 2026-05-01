package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.search.domain.SearchKind
import org.koitharu.kotatsu.search.domain.SearchV2Helper
import java.util.Locale
import javax.inject.Inject

private const val MAX_PARALLELISM = 4

class AlternativesUseCase @Inject constructor(
	private val sourcesRepository: MangaSourcesRepository,
	private val searchHelperFactory: SearchV2Helper.Factory,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) {

	suspend operator fun invoke(manga: Manga, throughDisabledSources: Boolean): Flow<Manga> {
		val sources = getSources(manga.source, throughDisabledSources)
		if (sources.isEmpty()) {
			return emptyFlow()
		}
		val semaphore = Semaphore(MAX_PARALLELISM)
		return channelFlow {
			val seen = HashSet<MangaEntryKey>().apply {
				add(manga.entryKey())
			}
			for (source in sources) {
				launch {
					val searchHelper = searchHelperFactory.create(source)
					val list = runCatchingCancellable {
						semaphore.withPermit {
							searchHelper(manga.title, SearchKind.TITLE)?.manga
						}
					}.getOrNull()
					list?.forEach { m ->
						if (m.isSameEntryAs(manga)) {
							return@forEach
						}
						val rawKey = m.entryKey()
						if (!seen.addSynchronized(rawKey)) {
							return@forEach
						}
						launch {
							val details = runCatchingCancellable {
								mangaRepositoryFactory.create(m.source).getDetails(m)
							}.getOrDefault(m)
							if (details.isSameEntryAs(manga)) {
								return@launch
							}
							val detailsKey = details.entryKey()
							if (detailsKey != rawKey && !seen.addSynchronized(detailsKey)) {
								return@launch
							}
							send(details)
						}
					}
				}
			}
		}
	}

	private suspend fun getSources(ref: MangaSource, disabled: Boolean): List<MangaSource> = buildList {
		val refSource = ref.unwrap()
		if (!disabled) {
			add(refSource)
		}
		val sources = if (disabled) {
			sourcesRepository.getDisabledSources()
		} else {
			sourcesRepository.getEnabledSources()
		}
		for (source in sources) {
			val unwrapped = source.unwrap()
			if (unwrapped.name != refSource.name) {
				add(unwrapped)
			}
		}
	}.distinctBy { it.name }
		.sortedByDescending { it.priority(ref) }

	private fun Manga.isSameEntryAs(other: Manga): Boolean {
		if (source.nameKey() != other.source.nameKey()) {
			return false
		}
		val identityUrl = identityUrl()
		return (id != 0L && id == other.id) ||
			(identityUrl.isNotBlank() && identityUrl == other.identityUrl()) ||
			(publicUrl.isNotBlank() && publicUrl.trimEnd('/') == other.publicUrl.trimEnd('/'))
	}

	private fun Manga.entryKey(): MangaEntryKey {
		return MangaEntryKey(source.nameKey(), identityUrl().ifBlank { id.toString() })
	}

	private fun MutableSet<MangaEntryKey>.addSynchronized(key: MangaEntryKey): Boolean = synchronized(this) {
		add(key)
	}

	private fun MangaSource.priority(ref: MangaSource): Int {
		var res = 0
		val source = unwrap()
		val refSource = ref.unwrap()
		if (source.name == refSource.name) {
			res += 8
		}
		if (source is MangaParserSource && refSource is MangaParserSource) {
			if (source.locale == refSource.locale) {
				res += 4
			} else if (source.locale.toLocale() == Locale.getDefault()) {
				res += 2
			}
			if (source.contentType == refSource.contentType) {
				res++
			}
		}
		return res
	}

	private fun Manga.identityUrl(): String {
		val sourceName = source.nameKey()
		return url.toIdentityUrl(sourceName).ifBlank {
			publicUrl.toIdentityUrl(sourceName)
		}
	}

	private fun MangaSource.nameKey(): String = unwrap().name

	private fun MangaSource.unwrap(): MangaSource = (this as? MangaSourceInfo)?.mangaSource ?: this

	private fun String.toIdentityUrl(sourceName: String): String {
		var result = trim().trimEnd('/')
		val schemeIndex = result.indexOf("://")
		if (schemeIndex >= 0) {
			val pathStart = result.indexOf('/', startIndex = schemeIndex + 3)
			result = if (pathStart >= 0) {
				result.substring(pathStart)
			} else {
				""
			}
		}
		if (sourceName == MangaParserSource.REMANGA.name) {
			result = result.trimEnd('_')
		}
		return result
	}

	private data class MangaEntryKey(
		val sourceName: String,
		val urlOrId: String,
	)
}
