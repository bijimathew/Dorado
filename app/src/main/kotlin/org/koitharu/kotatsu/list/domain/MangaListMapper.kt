package org.koitharu.kotatsu.list.domain

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.IntDef
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.ui.model.MangaCompactListModel
import org.koitharu.kotatsu.list.ui.model.MangaDetailedListModel
import org.koitharu.kotatsu.list.ui.model.MangaGridModel
import org.koitharu.kotatsu.list.ui.model.MangaListModel
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.tracker.domain.TrackingRepository
import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import javax.inject.Inject

@Reusable
class MangaListMapper @Inject constructor(
	@ApplicationContext context: Context,
	private val settings: AppSettings,
	private val trackingRepository: TrackingRepository,
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: FavouritesRepository,
	private val localMangaIndex: LocalMangaIndex,
	private val dataRepository: MangaDataRepository,
) {


	suspend fun toListModelList(
		manga: Collection<Manga>,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
	): List<MangaListModel> = ArrayList<MangaListModel>(manga.size).apply {
		toListModelList(
			destination = this,
			manga = manga,
			mode = mode,
			flags = flags,
		)
	}

	suspend fun toListModelList(
		destination: MutableCollection<in MangaListModel>,
		manga: Collection<Manga>,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
	) {
		val options = getOptions(flags)
		val ids = manga.mapTo(LinkedHashSet(manga.size)) { it.id }
		val badges = loadBadges(ids, options)
		val overrides = dataRepository.getOverrides(ids)
		manga.mapTo(destination) {
			toListModelImpl(it, mode, overrides[it.id], badges[it.id] ?: EmptyBadges)
		}
	}

	suspend fun toListModel(
		manga: Manga,
		mode: ListMode,
		@Flags flags: Int = DEFAULTS,
	): MangaListModel {
		val options = getOptions(flags)
		return toListModelImpl(
			manga = manga,
			mode = mode,
			override = dataRepository.getOverride(manga.id),
			badges = getBadges(manga.id, options),
		)
	}

	suspend fun toFeedItem(logItem: TrackingLogItem) = FeedItem(
		id = logItem.id,
		override = dataRepository.getOverride(logItem.manga.id),
		count = logItem.chapters.size,
		manga = logItem.manga,
		isNew = logItem.isNew,
	)

	suspend fun toFeedItemList(logItems: Collection<TrackingLogItem>): List<FeedItem> {
		if (logItems.isEmpty()) {
			return emptyList()
		}
		val ids = logItems.mapTo(LinkedHashSet(logItems.size)) { it.manga.id }
		val overrides = dataRepository.getOverrides(ids)
		return logItems.map {
			FeedItem(
				id = it.id,
				override = overrides[it.manga.id],
				count = it.chapters.size,
				manga = it.manga,
				isNew = it.isNew,
			)
		}
	}

	fun mapTags(tags: Collection<MangaTag>) = tags.map {
		ChipsView.ChipModel(
			tint = getTagTint(it),
			title = it.title,
			data = it,
		)
	}

	private fun toCompactListModel(
		manga: Manga,
		override: MangaOverride?,
		badges: MangaBadges,
	) = MangaCompactListModel(
		manga = manga,
		override = override,
		subtitle = manga.tags.joinToString(", ") { it.title },
		counter = badges.counter,
	)

	private fun toDetailedListModel(
		manga: Manga,
		override: MangaOverride?,
		badges: MangaBadges,
	) = MangaDetailedListModel(
		subtitle = manga.altTitles.firstOrNull(),
		manga = manga,
		override = override,
		counter = badges.counter,
		progress = badges.progress,
		isFavorite = badges.isFavorite,
		isSaved = badges.isSaved,
		tags = mapTags(manga.tags),
	)

	private fun toGridModel(
		manga: Manga,
		override: MangaOverride?,
		badges: MangaBadges,
		isTitleHidden: Boolean = false,
	) = MangaGridModel(
		manga = manga,
		override = override,
		counter = badges.counter,
		progress = badges.progress,
		isFavorite = badges.isFavorite,
		isSaved = badges.isSaved,
		isTitleHidden = isTitleHidden,
	)

	private fun toListModelImpl(
		manga: Manga,
		mode: ListMode,
		override: MangaOverride?,
		badges: MangaBadges,
	): MangaListModel = when (mode) {
		ListMode.LIST -> toCompactListModel(manga, override, badges)
		ListMode.DETAILED_LIST -> toDetailedListModel(manga, override, badges)
		ListMode.GRID -> toGridModel(manga, override, badges)
		ListMode.COVER_ONLY -> toGridModel(manga, override, badges, isTitleHidden = true)
	}

	private suspend fun loadBadges(ids: Set<Long>, @Options options: Int): Map<Long, MangaBadges> {
		if (ids.isEmpty()) {
			return emptyMap()
		}
		return coroutineScope {
			val countersDeferred = if (settings.isTrackerEnabled) {
				async { trackingRepository.getNewChaptersCountMap(ids) }
			} else {
				null
			}
			val progressDeferred = if (options.isBadgeEnabled(PROGRESS)) {
				async { historyRepository.getProgressMap(ids, settings.progressIndicatorMode) }
			} else {
				null
			}
			val favoritesDeferred = if (options.isBadgeEnabled(FAVORITE)) {
				async { favouritesRepository.getFavoriteIds(ids) }
			} else {
				null
			}
			val savedDeferred = if (options.isBadgeEnabled(SAVED)) {
				async { localMangaIndex.getSavedIds(ids) }
			} else {
				null
			}

			val counters = countersDeferred?.await().orEmpty()
			val progress = progressDeferred?.await().orEmpty()
			val favorites = favoritesDeferred?.await().orEmpty()
			val saved = savedDeferred?.await().orEmpty()

			HashMap<Long, MangaBadges>(ids.size).apply {
				for (id in ids) {
					this[id] = MangaBadges(
						counter = counters[id] ?: 0,
						progress = progress[id],
						isFavorite = id in favorites,
						isSaved = id in saved,
					)
				}
			}
		}
	}

	private suspend fun getBadges(mangaId: Long, @Options options: Int): MangaBadges {
		val counter = if (settings.isTrackerEnabled) {
			trackingRepository.getNewChaptersCount(mangaId)
		} else {
			0
		}
		val progress = if (options.isBadgeEnabled(PROGRESS)) {
			historyRepository.getProgress(mangaId, settings.progressIndicatorMode)
		} else {
			null
		}
		val isFavorite = options.isBadgeEnabled(FAVORITE) && favouritesRepository.isFavorite(mangaId)
		val isSaved = options.isBadgeEnabled(SAVED) && mangaId in localMangaIndex
		return MangaBadges(
			counter = counter,
			progress = progress,
			isFavorite = isFavorite,
			isSaved = isSaved,
		)
	}

	@ColorRes
	private fun getTagTint(tag: MangaTag): Int {
		return 0
	}


	private fun Int.isBadgeEnabled(@Options badge: Int) = this and badge == badge

	@Options
	@SuppressLint("WrongConstant")
	private fun getOptions(@Flags flags: Int): Int {
		var options = settings.getMangaListBadges() or PROGRESS
		options = options and flags.inv()
		return options
	}

	@IntDef(DEFAULTS, NO_SAVED, NO_PROGRESS, NO_FAVORITE, flag = true)
	@Retention(AnnotationRetention.SOURCE)
	annotation class Flags

	@IntDef(NONE, SAVED, FAVORITE, PROGRESS)
	@Retention(AnnotationRetention.SOURCE)
	private annotation class Options

	private data class MangaBadges(
		val counter: Int,
		val progress: ReadingProgress?,
		val isFavorite: Boolean,
		val isSaved: Boolean,
	)

	companion object {

		private const val NONE = 0
		private const val SAVED = 1
		private const val PROGRESS = 2
		private const val FAVORITE = 4
		private val EmptyBadges = MangaBadges(
			counter = 0,
			progress = null,
			isFavorite = false,
			isSaved = false,
		)

		const val DEFAULTS = NONE
		const val NO_SAVED = SAVED
		const val NO_PROGRESS = PROGRESS
		const val NO_FAVORITE = FAVORITE
	}
}
