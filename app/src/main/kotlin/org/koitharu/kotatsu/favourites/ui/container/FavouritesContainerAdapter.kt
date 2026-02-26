package org.koitharu.kotatsu.favourites.ui.container

import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.AdapterListUpdateCallback
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import kotlinx.coroutines.flow.FlowCollector
import org.koitharu.kotatsu.core.ui.ListDiffExecutor
import org.koitharu.kotatsu.favourites.ui.list.FavouritesListFragment
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback

class FavouritesContainerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment),
	FlowCollector<List<FavouriteTabModel>> {

	private val differ = AsyncListDiffer(
		AdapterListUpdateCallback(this),
		AsyncDifferConfig.Builder(ListModelDiffCallback<FavouriteTabModel>())
			.setBackgroundThreadExecutor(ListDiffExecutor.instance)
			.build(),
	)

	override fun getItemCount(): Int = differ.currentList.size

	override fun getItemId(position: Int): Long {
		return differ.currentList.getOrNull(position)?.id ?: RecyclerView.NO_ID
	}

	override fun containsItem(itemId: Long): Boolean {
		return differ.currentList.any { x -> x.id == itemId }
	}

	override fun createFragment(position: Int): Fragment {
		val item = differ.currentList[position]
		return FavouritesListFragment.newInstance(item.id)
	}

	override suspend fun emit(value: List<FavouriteTabModel>) {
		// Keep collection non-blocking: submitList commit callbacks are not guaranteed.
		differ.submitList(value)
	}

	fun getItem(position: Int): FavouriteTabModel = differ.currentList[position]
}
