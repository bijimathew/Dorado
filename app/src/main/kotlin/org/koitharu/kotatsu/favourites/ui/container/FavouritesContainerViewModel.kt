package org.koitharu.kotatsu.favourites.ui.container

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment.Companion.NO_ID
import javax.inject.Inject

@HiltViewModel
class FavouritesContainerViewModel @Inject constructor(
	private val settings: AppSettings,
	private val favouritesRepository: FavouritesRepository,
) : BaseViewModel() {

	val onActionDone = MutableEventFlow<ReversibleAction>()

	private val categoriesStateFlow = favouritesRepository.observeCategoriesForLibrary()
		.withErrorReporting(errorEvent)
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	private val allFavouritesVisibility = settings.observeAsFlow(
		key = AppSettings.KEY_ALL_FAVOURITES_VISIBLE,
		valueProducer = { isAllFavouritesVisible },
	)

	val categories = combine(
		categoriesStateFlow.filterNotNull(),
		allFavouritesVisibility,
		::createFavouriteTabs,
	).stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val isEmpty = categoriesStateFlow.map {
		it?.isEmpty() == true
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

	fun getCategoryPosition(categories: List<FavouriteTabModel>): Int {
		if (categories.isEmpty()) {
			return 0
		}
		val position = categories.indexOfFirst { it.id == settings.lastFavoritesCategoryId }
		return position.takeIf { it >= 0 } ?: 0
	}

	fun onCategorySelected(categoryId: Long) {
		settings.lastFavoritesCategoryId = categoryId
	}

	fun hide(categoryId: Long) {
		launchJob(Dispatchers.Default) {
			if (categoryId == NO_ID) {
				settings.isAllFavouritesVisible = false
			} else {
				favouritesRepository.updateCategory(categoryId, isVisibleInLibrary = false)
				val reverse = ReversibleHandle {
					favouritesRepository.updateCategory(categoryId, isVisibleInLibrary = true)
				}
				onActionDone.call(ReversibleAction(R.string.category_hidden_done, reverse))
			}
		}
	}

	fun deleteCategory(categoryId: Long) {
		launchJob(Dispatchers.Default) {
			favouritesRepository.removeCategories(setOf(categoryId))
		}
	}
}

private fun createFavouriteTabs(list: List<FavouriteCategory>, showAll: Boolean): List<FavouriteTabModel> {
	if (list.isEmpty()) {
		return emptyList()
	}
	val result = ArrayList<FavouriteTabModel>(if (showAll) list.size + 1 else list.size)
	if (showAll) {
		result.add(FavouriteTabModel(NO_ID, null))
	}
	list.mapTo(result) { FavouriteTabModel(it.id, it.title) }
	return result
}

private fun <T> Flow<T>.withErrorReporting(errorEvent: MutableEventFlow<Throwable>) = catch { error ->
	error.printStackTraceDebug()
	errorEvent.call(error)
}
