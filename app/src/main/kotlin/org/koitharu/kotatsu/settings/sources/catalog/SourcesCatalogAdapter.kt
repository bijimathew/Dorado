package org.koitharu.kotatsu.settings.sources.catalog

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.hannesdorfmann.adapterdelegates4.AdapterDelegate
import com.hannesdorfmann.adapterdelegates4.ListDelegationAdapter
import kotlinx.coroutines.flow.FlowCollector
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.image.CoilImageView
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.list.fastscroll.FastScroller
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.model.ListModel
import java.lang.ref.WeakReference

class SourcesCatalogAdapter(
	listener: OnListItemClickListener<SourceCatalogItem.Source>,
) : ListDelegationAdapter<List<ListModel>>(),
	FastScroller.SectionIndexer,
	FlowCollector<List<ListModel>?> {

	private val weakListener = WeakClickListener(listener)

	init {
		addDelegate(ListItemType.CHAPTER_LIST, sourceCatalogItemSourceAD(weakListener))
		addDelegate(ListItemType.HINT_EMPTY, sourceCatalogItemHintAD())
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
	}

	@SuppressLint("NotifyDataSetChanged")
	override suspend fun emit(value: List<ListModel>?) {
		items = value.orEmpty()
		notifyDataSetChanged()
	}

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		return (items?.getOrNull(position) as? SourceCatalogItem.Source)?.source?.getTitle(context)?.take(1)
	}

	override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
		holder.itemView.findViewById<CoilImageView?>(R.id.imageView_icon)?.disposeImage()
		super.onViewRecycled(holder)
	}

	private fun addDelegate(type: ListItemType, delegate: AdapterDelegate<List<ListModel>>) {
		delegatesManager.addDelegate(type.ordinal, delegate)
	}

	private class WeakClickListener(
		listener: OnListItemClickListener<SourceCatalogItem.Source>,
	) : OnListItemClickListener<SourceCatalogItem.Source> {

		private val listenerRef = WeakReference(listener)

		override fun onItemClick(item: SourceCatalogItem.Source, view: View) {
			listenerRef.get()?.onItemClick(item, view)
		}

		override fun onItemLongClick(item: SourceCatalogItem.Source, view: View): Boolean {
			return listenerRef.get()?.onItemLongClick(item, view) ?: false
		}
	}
}
