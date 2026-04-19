package org.koitharu.kotatsu.reader.ui.pager.doublepage

import android.graphics.PointF
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.databinding.ItemPageBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.standard.PageHolder

class DoublePageHolder(
	owner: LifecycleOwner,
	binding: ItemPageBinding,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
) : PageHolder(
	owner = owner,
	binding = binding,
	loader = loader,
	readerSettingsProducer = readerSettingsProducer,
	networkState = networkState,
	exceptionResolver = exceptionResolver,
) {

	private val isEven: Boolean
		get() = bindingAdapterPosition and 1 == 0

	init {
		binding.ssiv.panLimit = SubsamplingScaleImageView.PAN_LIMIT_INSIDE
	}

	override fun onBind(data: ReaderPage) {
		super.onBind(data)
		binding.ssiv.isVisible = true
		binding.ssiv.scaleX = 1f
		binding.ssiv.scaleY = 1f
		binding.ssiv.translationX = 0f
		binding.ssiv.background = null
		itemView.translationZ = 0f
		binding.textViewNumber.isVisible = true
		(binding.textViewNumber.layoutParams as FrameLayout.LayoutParams)
			.gravity = (if (isEven) Gravity.START else Gravity.END) or Gravity.BOTTOM
	}

	fun bindSpacer() {
		viewModel.onRecycle()
		binding.ssiv.recycle()
		binding.ssiv.isVisible = false
		animatedView?.isVisible = false
		animatedView?.disposeImage()
		binding.ssiv.scaleX = 1f
		binding.ssiv.scaleY = 1f
		binding.ssiv.translationX = 0f
		binding.ssiv.background = null
		itemView.translationZ = 0f
		binding.textViewNumber.isVisible = false
		bindingInfo.layoutProgress.isGone = true
		bindingInfo.layoutError.isVisible = false
		settings.applyBackground(itemView)
	}

	override fun onReady() {
		with(binding.ssiv) {
			colorFilter = settings.colorFilter?.toColorFilter()
			minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
			maxScale = minScale
			setScaleAndCenter(
				minScale,
				PointF(sWidth / 2f, sHeight / 2f),
			)
			if (sWidth.toFloat() / sHeight > WIDE_IMAGE_RATIO) {
				val containerWidth = width.toFloat()
				val parent = itemView.parent as? ViewGroup
				val fullWidth = parent?.width?.toFloat() ?: (containerWidth * 2f)
				val fullHeight = parent?.height?.toFloat() ?: height.toFloat()
				val fitScale = minOf(fullWidth / sWidth, fullHeight / sHeight)
				val ratio = (fitScale / minScale).coerceAtLeast(1f)
				scaleX = ratio
				scaleY = ratio
				translationX = if (isEven) containerWidth / 2f else -containerWidth / 2f
				settings.applyBackground(this)
				itemView.translationZ = 1f
				(itemView as? ViewGroup)?.clipChildren = false
			} else {
				scaleX = 1f
				scaleY = 1f
				background = null
				itemView.translationZ = 0f
				val imageDisplayWidth = sWidth * minScale
				val offset = (width - imageDisplayWidth) / 2f
				translationX = if (offset > 0f) {
					if (isEven) offset else -offset
				} else {
					0f
				}
			}
		}
	}

	companion object {
		private const val WIDE_IMAGE_RATIO = 1.3f
	}
}
