package org.koitharu.kotatsu.alternatives.domain

import androidx.room.withTransaction
import coil3.request.CachePolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.migrations.MangaIdentityMerge
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.model.isSameStoredEntryAs
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.details.domain.ProgressUpdateUseCase
import org.koitharu.kotatsu.history.data.HistoryEntity
import org.koitharu.kotatsu.history.data.toMangaHistory
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.domain.Scrobbler
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.scrobbling.common.domain.tryScrobble
import org.koitharu.kotatsu.tracker.data.TrackEntity
import javax.inject.Inject

class MigrateUseCase
@Inject
constructor(
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val mangaDataRepository: MangaDataRepository,
	private val database: MangaDatabase,
	private val progressUpdateUseCase: ProgressUpdateUseCase,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
) {
	suspend operator fun invoke(
		oldManga: Manga,
		newManga: Manga,
	): Manga {
		// Fetch old + new details in parallel — both are independent network calls and on slow
		// sources this halves the wait the user sees before the migration actually starts. The
		// `newManga.isSameStoredEntryAs(oldDetails)` check needs oldDetails first; resolve that on
		// the fast path by checking against oldManga's stored id, which is what the original code
		// effectively did via `withStoredIdOf`.
		val (oldDetailsRaw, newDetailsRaw) = coroutineScope {
			val oldDeferred = async {
				if (oldManga.chapters.isNullOrEmpty()) {
					runCatchingCancellable {
						mangaRepositoryFactory.create(oldManga.source).getDetails(oldManga)
					}.getOrDefault(oldManga)
				} else {
					oldManga
				}
			}
			val newDeferred = async {
				if (newManga.chapters.isNullOrEmpty() || newManga.isSameStoredEntryAs(oldManga)) {
					mangaRepositoryFactory.create(newManga.source).getFreshDetails(newManga)
				} else {
					newManga
				}
			}
			oldDeferred.await() to newDeferred.await()
		}
		val oldDetails = oldDetailsRaw.withStoredIdOf(oldManga)
		val newDetails = newDetailsRaw
		mangaDataRepository.storeManga(newDetails, replaceExisting = true)
		if (oldDetails.id == newDetails.id) {
			progressUpdateUseCase(newDetails)
			return newDetails
		}
		var newHistory: HistoryEntity? = null
		database.withTransaction {
			// replace favorites
			val favoritesDao = database.getFavouritesDao()
			val oldFavourites = favoritesDao.findAllRaw(oldDetails.id)
			if (oldFavourites.isNotEmpty()) {
				favoritesDao.delete(oldDetails.id)
				for (f in oldFavourites) {
					val e =
						f.copy(
							mangaId = newDetails.id,
						)
					favoritesDao.upsert(e)
				}
			}
			// replace history
			val historyDao = database.getHistoryDao()
			val oldHistory = historyDao.find(oldDetails.id)
			if (oldHistory != null) {
				val history = makeNewHistory(oldDetails, newDetails, oldHistory)
				historyDao.delete(oldDetails.id)
				historyDao.upsert(history)
				newHistory = history
			}
			// track
			val tracksDao = database.getTracksDao()
			val oldTrack = tracksDao.find(oldDetails.id)
			if (oldTrack != null) {
				val lastChapter = newDetails.chapters?.lastOrNull()
				val newTrack =
					TrackEntity(
						mangaId = newDetails.id,
						lastChapterId = lastChapter?.id ?: 0L,
						newChapters = 0,
						lastCheckTime = System.currentTimeMillis(),
						lastChapterDate = lastChapter?.uploadDate ?: 0L,
						lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
						lastError = null,
					)
				tracksDao.delete(oldDetails.id)
				tracksDao.upsert(newTrack)
			}
			MangaIdentityMerge.mergeManga(database.openHelper.writableDatabase, oldDetails.id, newDetails.id)
		}
		// Scrobbling runs outside the DB transaction: each scrobbler hits the network and the
		// transaction shouldn't stay open for the whole thing. Each scrobbler runs in parallel so
		// one slow service doesn't block the others, and a failure in one doesn't break the migration
		// or stop the rest.
		val scrobblerWork = scrobblers.filter { it.isEnabled }.mapNotNull { scrobbler ->
			val prevInfo = scrobbler.getScrobblingInfoOrNull(oldDetails.id) ?: return@mapNotNull null
			scrobbler to prevInfo
		}
		if (scrobblerWork.isNotEmpty()) {
			coroutineScope {
				scrobblerWork.map { (scrobbler, prevInfo) ->
					async {
						runCatchingCancellable {
							scrobbler.unregisterScrobbling(oldDetails.id)
							scrobbler.linkManga(newDetails.id, prevInfo.targetId)
							scrobbler.updateScrobblingInfo(
								mangaId = newDetails.id,
								rating = prevInfo.rating,
								status = prevInfo.status ?: when {
									newHistory == null -> ScrobblingStatus.PLANNED
									newHistory?.percent == 1f -> ScrobblingStatus.COMPLETED
									else -> ScrobblingStatus.READING
								},
								comment = prevInfo.comment,
							)
							newHistory?.let { h ->
								scrobbler.tryScrobble(newDetails, h.chapterId)
							}
						}
					}
				}.awaitAll()
			}
		}
		progressUpdateUseCase(newDetails)
		return newDetails
	}

	private suspend fun MangaRepository.getFreshDetails(manga: Manga): Manga = if (this is CachingMangaRepository) {
		getDetails(manga, CachePolicy.WRITE_ONLY)
	} else {
		getDetails(manga)
	}

	// Parser updates may re-key the loaded old details. Database rows still live under the stored seed id.
	private fun Manga.withStoredIdOf(seed: Manga): Manga = if (id != seed.id && seed.id != 0L) {
		copy(id = seed.id)
	} else {
		this
	}

	private fun makeNewHistory(
		oldManga: Manga,
		newManga: Manga,
		history: HistoryEntity,
	): HistoryEntity {
		if (oldManga.chapters.isNullOrEmpty()) { // probably broken manga/source
			val branch = newManga.getPreferredBranch(null)
			val chapters = checkNotNull(newManga.getChapters(branch))
			val currentChapter =
				if (history.percent in 0f..1f) {
					chapters[(chapters.lastIndex * history.percent).toInt()]
				} else {
					chapters.first()
				}
			return HistoryEntity(
				mangaId = newManga.id,
				createdAt = history.createdAt,
				updatedAt = history.updatedAt,
				chapterId = currentChapter.id,
				page = history.page,
				scroll = history.scroll,
				percent = history.percent,
				deletedAt = 0,
				chaptersCount = chapters.count { it.branch == currentChapter.branch },
			)
		}
		val branch = oldManga.getPreferredBranch(history.toMangaHistory())
		val oldChapters = checkNotNull(oldManga.getChapters(branch))
		var index = oldChapters.indexOfFirst { it.id == history.chapterId }
		if (index < 0) {
			index =
				if (history.percent in 0f..1f) {
					(oldChapters.lastIndex * history.percent).toInt()
				} else {
					0
				}
		}
		val newChapters = checkNotNull(newManga.chapters).groupBy { it.branch }
		val newBranch =
			if (newChapters.containsKey(branch)) {
				branch
			} else {
				newManga.getPreferredBranch(null)
			}
		val newChapterId =
			checkNotNull(newChapters[newBranch])
				.let {
					val oldChapter = oldChapters[index]
					it.findByNumber(oldChapter.volume, oldChapter.number) ?: it.getOrNull(index) ?: it.last()
				}.id

		return HistoryEntity(
			mangaId = newManga.id,
			createdAt = history.createdAt,
			updatedAt = history.updatedAt,
			chapterId = newChapterId,
			page = history.page,
			scroll = history.scroll,
			percent = PROGRESS_NONE,
			deletedAt = 0,
			chaptersCount = checkNotNull(newChapters[newBranch]).size,
		)
	}

	private fun List<MangaChapter>.findByNumber(
		volume: Int,
		number: Float,
	): MangaChapter? =
		if (number <= 0f) {
			null
		} else {
			firstOrNull { it.volume == volume && it.number == number }
		}
}
