package org.koitharu.kotatsu.settings.sources.repo

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.mihon.repo.MihonExtensionRepo
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.list.fastscroll.FastScroller
import org.koitharu.kotatsu.core.util.ext.getThemeDimensionPixelOffset
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.core.util.ext.textAndVisible
import org.koitharu.kotatsu.databinding.ItemEmptyHintBinding
import org.koitharu.kotatsu.databinding.ItemSourceCatalogBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.model.ListModel
import androidx.appcompat.R as appcompatR

sealed interface MihonExtensionRepoListItem : ListModel {

	data class Repo(
		val repo: MihonExtensionRepo,
	) : MihonExtensionRepoListItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Repo && other.repo.baseUrl == repo.baseUrl
		}
	}

	data class Hint(
		@DrawableRes val icon: Int,
		@StringRes val title: Int,
		@StringRes val text: Int,
	) : MihonExtensionRepoListItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Hint && other.title == title
		}
	}
}

class MihonExtensionReposAdapter(
	listener: OnListItemClickListener<MihonExtensionRepoListItem.Repo>,
) : BaseListAdapter<ListModel>(), FastScroller.SectionIndexer {

	init {
		addDelegate(ListItemType.INFO, mihonExtensionRepoAD(listener))
		addDelegate(ListItemType.HINT_EMPTY, mihonExtensionRepoHintAD())
	}

	override fun getSectionText(context: android.content.Context, position: Int): CharSequence? {
		return (items.getOrNull(position) as? MihonExtensionRepoListItem.Repo)?.repo?.name?.take(1)
	}
}

private fun mihonExtensionRepoAD(
	listener: OnListItemClickListener<MihonExtensionRepoListItem.Repo>,
) = adapterDelegateViewBinding<MihonExtensionRepoListItem.Repo, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent ->
		ItemSourceCatalogBinding.inflate(layoutInflater, parent, false)
	},
) {

	binding.imageViewAdd.setOnClickListener { v ->
		listener.onItemLongClick(item, v)
	}
	binding.root.setOnClickListener { v ->
		listener.onItemClick(item, v)
	}
	val basePadding = context.getThemeDimensionPixelOffset(
		appcompatR.attr.listPreferredItemPaddingEnd,
		binding.root.paddingStart,
	)
	binding.root.updatePaddingRelative(
		end = (basePadding - context.resources.getDimensionPixelOffset(R.dimen.margin_small)).coerceAtLeast(0),
	)

	bind {
		val repo = item.repo
		binding.textViewTitle.text = repo.name.ifBlank { repo.baseUrl }
		binding.textViewDescription.textAndVisible = buildString {
			repo.shortName
				?.takeIf { it.isNotBlank() && !it.equals(repo.name, ignoreCase = true) }
				?.let {
					append(it)
					append(" • ")
				}
			append(repo.website ?: repo.baseUrl)
		}
		binding.imageViewIcon.setImageAsync(R.drawable.ic_open_external)
		binding.imageViewAdd.setImageResource(R.drawable.ic_delete)
		binding.imageViewAdd.contentDescription = context.getString(R.string.remove)
		binding.imageViewAdd.tooltipText = binding.imageViewAdd.contentDescription
	}
}

private fun mihonExtensionRepoHintAD() =
	adapterDelegateViewBinding<MihonExtensionRepoListItem.Hint, ListModel, ItemEmptyHintBinding>(
		{ inflater, parent -> ItemEmptyHintBinding.inflate(inflater, parent, false) },
	) {

		binding.buttonRetry.isVisible = false

		bind {
			binding.icon.setImageAsync(item.icon)
			binding.textPrimary.setText(item.title)
			binding.textSecondary.setTextAndVisible(item.text)
		}
	}
