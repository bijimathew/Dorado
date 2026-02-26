package org.koitharu.kotatsu.core.ui

import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.AsyncListDiffer.ListListener
import com.hannesdorfmann.adapterdelegates4.AdapterDelegate
import com.hannesdorfmann.adapterdelegates4.AsyncListDifferDelegationAdapter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.list.ui.ListModelDiffCallback
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.list.ui.model.ListModel

open class BaseListAdapter<T : ListModel> : AsyncListDifferDelegationAdapter<T>(
	AsyncDifferConfig.Builder(ListModelDiffCallback<T>())
		.setBackgroundThreadExecutor(ListDiffExecutor.instance)
		.build(),
), FlowCollector<List<T>?> {

	override suspend fun emit(value: List<T>?) {
		// AsyncListDiffer may skip commit callbacks for superseded lists.
		// Do not suspend here, or collector coroutines can hang indefinitely.
		setItems(value.orEmpty())
	}

	fun addDelegate(type: ListItemType, delegate: AdapterDelegate<List<T>>): BaseListAdapter<T> {
		delegatesManager.addDelegate(type.ordinal, delegate)
		return this
	}

	fun addListListener(listListener: ListListener<T>): BaseListAdapter<T> {
		differ.addListListener(listListener)
		return this
	}

	fun removeListListener(listListener: ListListener<T>) {
		differ.removeListListener(listListener)
	}

	fun findHeader(position: Int): ListHeader? {
		val snapshot = items
		for (i in (0..position).reversed()) {
			val item = snapshot.getOrNull(i) ?: continue
			if (item is ListHeader) {
				return item
			}
		}
		return null
	}

	fun observeItems(): Flow<List<T>> = callbackFlow {
		val listListener = ListListener<T> { _, list ->
			trySendBlocking(list)
		}
		addListListener(listListener)
		awaitClose { removeListListener(listListener) }
	}.onStart {
		emit(items)
	}
}
