package org.koitharu.kotatsu.remotelist.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.distinctById
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.getCauseUrl
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.explore.domain.ExploreRepository
import org.koitharu.kotatsu.filter.ui.FilterCoordinator
import org.koitharu.kotatsu.list.domain.MangaListMapper
import org.koitharu.kotatsu.list.ui.MangaListViewModel
import org.koitharu.kotatsu.list.ui.model.ButtonFooter
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingFooter
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.toErrorFooter
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.util.sizeOrZero
import javax.inject.Inject

private const val FILTER_MIN_INTERVAL = 250L

@HiltViewModel
open class RemoteListViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	mangaRepositoryFactory: MangaRepository.Factory,
	final override val filterCoordinator: FilterCoordinator,
	settings: AppSettings,
	protected val mangaListMapper: MangaListMapper,
	private val exploreRepository: ExploreRepository,
	sourcesRepository: MangaSourcesRepository,
	mangaDataRepository: MangaDataRepository,
	@LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>
) : MangaListViewModel(settings, mangaDataRepository, localStorageChanges), FilterCoordinator.Owner {

	val source = MangaSource(savedStateHandle[RemoteListFragment.ARG_SOURCE])
	val isRandomLoading = MutableStateFlow(false)
	val onOpenManga = MutableEventFlow<Manga>()
    val onSourceBroken = MutableEventFlow<Unit>()

	protected val repository = mangaRepositoryFactory.create(source)
	private val mangaList = MutableStateFlow<List<Manga>?>(null)
	private val hasNextPage = MutableStateFlow(false)
	private val listError = MutableStateFlow<Throwable?>(null)
	private val mutableContent = MutableStateFlow<List<ListModel>>(listOf(LoadingState))
	private var loadingJob: Job? = null
	private var randomJob: Job? = null
	private var contentJob: Job? = null

	override val content = mutableContent

	init {
		contentJob = combine(
			mangaList.map { it?.skipNsfwIfNeeded() },
			observeListModeWithTriggers(),
			listError,
			hasNextPage,
		) { list, mode, error, hasNext ->
			buildRemoteListContent(
				list = list,
				mode = mode,
				error = error,
				hasNext = hasNext,
				canResetFilter = filterCoordinator.isFilterApplied,
				createEmptyState = ::createEmptyState,
				mapMangaList = ::mapMangaList,
				getFooter = ::getFooter,
				onBuildList = ::onBuildList,
			)
		}.onEach { list ->
			mutableContent.value = list
		}.launchIn(viewModelScope + Dispatchers.Default)

		filterCoordinator.observe()
			.debounce(FILTER_MIN_INTERVAL)
			.onEach { filterState ->
				loadingJob?.cancelAndJoin()
				mangaList.value = null
				loadList(filterState, false)
			}.catch { error ->
				listError.value = error
			}.launchIn(viewModelScope)

		val currentSource = source
		launchJob(Dispatchers.Default) {
			trackSourceUsage(sourcesRepository, currentSource)
		}

		if (source is MangaParserSource && source.isBroken) {
			// Just notify one. Will show reason in future
			onSourceBroken.call(Unit)
		}
	}

	override fun onCleared() {
		contentJob?.cancel()
		loadingJob?.cancel()
		randomJob?.cancel()
		contentJob = null
		loadingJob = null
		randomJob = null
		super.onCleared()
	}

	override fun onRefresh() {
		loadList(filterCoordinator.snapshot(), append = false)
	}

	override fun onRetry() {
		loadList(filterCoordinator.snapshot(), append = !mangaList.value.isNullOrEmpty())
	}

	fun loadNextPage() {
		if (hasNextPage.value && listError.value == null) {
			loadList(filterCoordinator.snapshot(), append = true)
		}
	}

	protected fun loadList(filterState: FilterCoordinator.Snapshot, append: Boolean): Job {
		loadingJob?.let {
			if (it.isActive) return it
		}
		val currentLoadingCounter = loadingCounter
		val currentRepository = repository
		val currentMangaList = mangaList
		val currentHasNextPage = hasNextPage
		val currentListError = listError
		val currentErrorEvent = errorEvent
		return viewModelScope.launch(Dispatchers.Default) {
			currentLoadingCounter.update { it + 1 }
			try {
				loadRemoteList(
					repository = currentRepository,
					mangaList = currentMangaList,
					hasNextPage = currentHasNextPage,
					listError = currentListError,
					errorEvent = currentErrorEvent,
					filterState = filterState,
					append = append,
				)
			} finally {
				currentLoadingCounter.update { it - 1 }
			}
		}.also { loadingJob = it }
	}

	protected open fun createEmptyState(canResetFilter: Boolean) = EmptyState(
		icon = R.drawable.ic_empty_common,
		textPrimary = R.string.nothing_found,
		textSecondary = 0,
		actionStringRes = if (canResetFilter) R.string.reset_filter else 0,
	)

	protected open suspend fun onBuildList(list: MutableList<ListModel>) = Unit

	protected open suspend fun mapMangaList(
		destination: MutableCollection<in ListModel>,
		manga: Collection<Manga>,
		mode: ListMode
	) = mangaListMapper.toListModelList(destination, manga, mode)

	protected open fun getFooter(): ButtonFooter? {
		val filter = filterCoordinator.snapshot().listFilter
		val hasQuery = !filter.query.isNullOrEmpty()
		val hasAuthor = !filter.author.isNullOrEmpty()
		val isOneTag = filter.tags.size == 1
		return if ((hasQuery xor isOneTag xor hasAuthor) && !(hasQuery && isOneTag && hasAuthor)) {
			ButtonFooter(R.string.global_search)
		} else {
			null
		}
	}

	fun openRandom() {
		if (randomJob?.isActive == true) {
			return
		}
		val currentLoadingCounter = loadingCounter
		val currentExploreRepository = exploreRepository
		val currentSource = source
		val currentRandomLoading = isRandomLoading
		val currentOnOpenManga = onOpenManga
		val currentErrorEvent = errorEvent
		randomJob = viewModelScope.launch(Dispatchers.Default) {
			currentLoadingCounter.update { it + 1 }
			try {
				openRandomManga(
					exploreRepository = currentExploreRepository,
					source = currentSource,
					isRandomLoading = currentRandomLoading,
					onOpenManga = currentOnOpenManga,
					onError = currentErrorEvent,
				)
			} finally {
				currentLoadingCounter.update { it - 1 }
			}
		}
	}
}

private suspend fun trackSourceUsage(
	sourcesRepository: MangaSourcesRepository,
	source: org.koitharu.kotatsu.parsers.model.MangaSource,
) {
	sourcesRepository.trackUsage(source)
}

private suspend fun buildRemoteListContent(
	list: List<Manga>?,
	mode: ListMode,
	error: Throwable?,
	hasNext: Boolean,
	canResetFilter: Boolean,
	createEmptyState: (Boolean) -> EmptyState,
	mapMangaList: suspend (MutableCollection<in ListModel>, Collection<Manga>, ListMode) -> Unit,
	getFooter: () -> ButtonFooter?,
	onBuildList: suspend (MutableList<ListModel>) -> Unit,
): List<ListModel> = buildList(list?.size?.plus(2) ?: 2) {
	when {
		list.isNullOrEmpty() && error != null -> add(
			error.toErrorState(
				canRetry = true,
				secondaryAction = if (error.getCauseUrl().isNullOrEmpty()) 0 else R.string.open_in_browser,
			),
		)

		list == null -> add(LoadingState)
		list.isEmpty() -> add(createEmptyState(canResetFilter))
		else -> {
			mapMangaList(this, list, mode)
			when {
				error != null -> add(error.toErrorFooter())
				hasNext -> add(LoadingFooter())
				else -> getFooter()?.let(::add)
			}
		}
	}
	onBuildList(this)
}

private suspend fun loadRemoteList(
	repository: MangaRepository,
	mangaList: MutableStateFlow<List<Manga>?>,
	hasNextPage: MutableStateFlow<Boolean>,
	listError: MutableStateFlow<Throwable?>,
	errorEvent: MutableEventFlow<Throwable>,
	filterState: FilterCoordinator.Snapshot,
	append: Boolean,
) {
	try {
		listError.value = null
		val list = repository.getList(
			offset = if (append) mangaList.value.sizeOrZero() else 0,
			order = filterState.sortOrder,
			filter = filterState.listFilter,
		)
		val prevList = mangaList.value.orEmpty()
		if (!append) {
			mangaList.value = list.distinctById()
		} else if (list.isNotEmpty()) {
			mangaList.value = (prevList + list).distinctById()
		}
		hasNextPage.value = if (append) {
			prevList != mangaList.value
		} else {
			list.size > prevList.size || hasNextPage.value
		}
	} catch (e: CancellationException) {
		throw e
	} catch (e: Throwable) {
		e.printStackTraceDebug()
		listError.value = e
		if (!mangaList.value.isNullOrEmpty()) {
			errorEvent.call(e)
		}
		hasNextPage.value = false
	}
}

private suspend fun openRandomManga(
	exploreRepository: ExploreRepository,
	source: org.koitharu.kotatsu.parsers.model.MangaSource,
	isRandomLoading: MutableStateFlow<Boolean>,
	onOpenManga: MutableEventFlow<Manga>,
	onError: MutableEventFlow<Throwable>,
) {
	isRandomLoading.value = true
	try {
		val manga = exploreRepository.findRandomManga(source, 16)
		onOpenManga.call(manga)
	} catch (e: CancellationException) {
		throw e
	} catch (e: Throwable) {
		e.printStackTraceDebug()
		onError.call(e)
	} finally {
		isRandomLoading.value = false
	}
}
