package org.koitharu.kotatsu.reader.ui.pager.webtoon

import android.util.Log
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.databinding.ItemPageWebtoonBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.BasePageHolder

class WebtoonHolder(
	owner: LifecycleOwner,
	binding: ItemPageWebtoonBinding,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
) : BasePageHolder<ItemPageWebtoonBinding>(
	binding = binding,
	loader = loader,
	readerSettingsProducer = readerSettingsProducer,
	networkState = networkState,
	exceptionResolver = exceptionResolver,
	lifecycleOwner = owner,
) {

	override val ssiv = binding.ssiv

	private var scrollToRestore = 0
	private var scrollPercentToRestore = -1 // percentage * 10000, or -1 if none
	private var isInitialScrollApplied = false

	init {
		bindingInfo.progressBar.setVisibilityAfterHide(View.GONE)
	}

	override fun onBind(data: org.koitharu.kotatsu.reader.ui.pager.ReaderPage) {
		super.onBind(data)
		scrollPercentToRestore = -1
		scrollToRestore = 0
		isInitialScrollApplied = false
		Log.d("WS", "onBind pos=$bindingAdapterPosition chapterId=${data.chapterId} page=${data.index}")
	}

	override fun onReady() {
		binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
		Log.d("WS", "onReady pos=$bindingAdapterPosition scrollPercent=$scrollPercentToRestore scrollRestore=$scrollToRestore initialApplied=$isInitialScrollApplied isReady=${binding.ssiv.isReady} scrollRange=${binding.ssiv.getScrollRange()}")
		when {
			scrollPercentToRestore >= 0 -> {
				val percent = scrollPercentToRestore
				scrollPercentToRestore = -1
				scrollToRestore = 0
				isInitialScrollApplied = true
				Log.d("WS", "onReady: DEFERRED percent restore=$percent")
				binding.ssiv.post {
					Log.d("WS", "onReady: POST executing percent=$percent scrollRange=${binding.ssiv.getScrollRange()}")
					applyScrollPercent(percent)
				}
			}

			scrollToRestore != 0 -> {
				val scroll = scrollToRestore
				scrollToRestore = 0
				isInitialScrollApplied = true
				Log.d("WS", "onReady: DEFERRED scrollRestore=$scroll")
				binding.ssiv.post {
					binding.ssiv.scrollTo(scroll)
				}
			}

			!isInitialScrollApplied -> {
				isInitialScrollApplied = true
				Log.d("WS", "onReady: INITIAL scroll, itemTop=${itemView.top}")
				binding.ssiv.post {
					binding.ssiv.scrollTo(
						if (itemView.top < 0) {
							binding.ssiv.getScrollRange()
						} else {
							0
						},
					)
				}
			}
			else -> {
				Log.d("WS", "onReady: SKIP (re-config/resume)")
			}
		}
	}

	fun getScrollY() = binding.ssiv.getScroll()

	fun restoreScroll(scroll: Int) {
		if (binding.ssiv.isReady) {
			binding.ssiv.scrollTo(scroll)
		} else {
			scrollToRestore = scroll
		}
	}

	/**
	 * Combined offset: total content pixels above viewport top.
	 * = SSIV internal scroll + how far the item has scrolled above the RV top.
	 */
	fun getFullScrollOffset(): Int {
		val rvScrollAbove = (-itemView.top).coerceAtLeast(0)
		return binding.ssiv.getScroll() + rvScrollAbove
	}

	/**
	 * Scroll progress as fraction 0.0-1.0 of total page content height.
	 */
	fun getScrollProgress(): Float {
		val scrollRange = binding.ssiv.getScrollRange()
		val totalHeight = scrollRange + itemView.height
		if (totalHeight <= 0) return 0f
		return (getFullScrollOffset().toFloat() / totalHeight).coerceIn(0f, 1f)
	}

	/**
	 * Restore scroll from percentage (encoded as percentage * 10000).
	 * If SSIV is ready, applies immediately. Otherwise defers to onReady()
	 * when scrollRange is known and layout has settled.
	 */
	fun restoreScrollPercent(percentTimes10000: Int) {
		val normalized = percentTimes10000.coerceAtLeast(0)
		Log.d("WS", "restoreScrollPercent percent=$normalized isReady=${binding.ssiv.isReady} pos=$bindingAdapterPosition")
		if (binding.ssiv.isReady) {
			applyScrollPercent(normalized)
			scrollPercentToRestore = -1
		} else {
			scrollPercentToRestore = normalized
			Log.d("WS", "restoreScrollPercent: DEFERRED (SSIV not ready)")
		}
	}

	private fun applyScrollPercent(percentTimes10000: Int) {
		val fraction = percentTimes10000 / 10000f
		val scrollRange = binding.ssiv.getScrollRange()
		val totalHeight = scrollRange + itemView.height
		val targetOffset = (fraction * totalHeight).toInt()

		val ssivScroll = targetOffset.coerceAtMost(scrollRange)
		val rvOffset = ssivScroll - targetOffset

		val adapterPosition = bindingAdapterPosition
		Log.d("WS", "applyScrollPercent pct=${percentTimes10000} frac=$fraction scrollRange=$scrollRange totalH=$totalHeight target=$targetOffset ssivScroll=$ssivScroll rvOff=$rvOffset pos=$adapterPosition")
		if (adapterPosition != RecyclerView.NO_POSITION) {
			val layoutManager = (itemView.parent as? RecyclerView)?.layoutManager as? LinearLayoutManager
			layoutManager?.scrollToPositionWithOffset(adapterPosition, rvOffset)
		}
		binding.ssiv.scrollTo(ssivScroll)
		isInitialScrollApplied = true
	}
}
