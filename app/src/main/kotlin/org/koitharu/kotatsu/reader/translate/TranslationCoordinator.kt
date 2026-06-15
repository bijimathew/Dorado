package org.koitharu.kotatsu.reader.translate

import androidx.core.net.toFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.parsers.model.MangaPage
import dagger.hilt.android.scopes.ActivityRetainedScoped
import org.koitharu.kotatsu.reader.domain.PageLoader
import javax.inject.Inject

/**
 * Orchestrates: ensure source bitmap → translate → render → cache.
 * Owns a per-page [StateFlow] so any [BasePageHolder] currently bound to that
 * page can observe and swap the SSIV source.
 */
@ActivityRetainedScoped
class TranslationCoordinator @Inject constructor(
	private val translator: MultimodalTranslator,
	private val renderer: TranslationRenderer,
	private val cache: RenderedPageCache,
	private val pageLoader: PageLoader,
	private val settings: AppSettings,
) {

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val states = mutableMapOf<Long, MutableStateFlow<PageTranslationState>>()
	private val jobs = mutableMapOf<Long, Job>()
	private val statesLock = Any()

	private val semaphore = Semaphore(settings.translateConcurrency.coerceIn(1, 4))

	private val activeJobs = AtomicInteger(0)
	private val _isBusy = MutableStateFlow(false)
	val isBusy: StateFlow<Boolean> = _isBusy
	private val _errors = MutableSharedFlow<Throwable>(
		extraBufferCapacity = 16,
		onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
	)
	val errors: SharedFlow<Throwable> = _errors

	fun stateFor(pageId: Long): StateFlow<PageTranslationState> = synchronized(statesLock) {
		states.getOrPut(pageId) { MutableStateFlow(PageTranslationState.Idle) }
	}

	/** Render-overlay variant. Result eventually appears on [stateFor]. */
	fun requestTranslate(page: MangaPage) {
		val state = stateFor(page.id) as MutableStateFlow<PageTranslationState>
		if (state.value is PageTranslationState.Loading || state.value is PageTranslationState.Done) return
		state.value = PageTranslationState.Loading
		if (activeJobs.getAndIncrement() == 0) _isBusy.value = true
		val newJob = scope.launch {
			try {
				semaphore.withPermit {
					val key = cache.keyFor(page.id, settings)
					cache.get(key)?.let { hit ->
						state.value = PageTranslationState.Done(hit.bitmap, hit.blocks)
						return@withPermit
					}
					val bitmap = loadSourceBitmap(page)
					val rawBlocks = try {
						translator.translatePage(
							bitmap = bitmap,
							sourceLang = settings.translateSourceLanguage,
							targetLang = settings.translateTargetLanguage,
						)
					} catch (e: CancellationException) {
						throw e
					} catch (e: Throwable) {
						bitmap.recycle()
						state.value = PageTranslationState.Failed(e)
						_errors.tryEmit(e)
						return@withPermit
					}
					val blocks = mergeOverlappingBlocks(rawBlocks)
					val rendered = try {
						runInterruptible { renderer.render(bitmap, blocks, settings.translateOverlayBackground) }
					} catch (e: Throwable) {
						bitmap.recycle()
						throw e
					}
					if (rendered !== bitmap) bitmap.recycle()
					cache.put(key, rendered, blocks)
					state.value = PageTranslationState.Done(rendered, blocks)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				state.value = PageTranslationState.Failed(e)
				_errors.tryEmit(e)
			} finally {
				if (activeJobs.decrementAndGet() == 0) _isBusy.value = false
			}
		}
		synchronized(statesLock) {
			jobs[page.id]?.cancel()
			jobs[page.id] = newJob
		}
	}

	/** OCR-only — used by the bottom sheet. Bypasses render + state map. */
	suspend fun requestOcr(page: MangaPage): List<TranslatedBlock> {
		cache.get(cache.keyFor(page.id, settings))?.let { return it.blocks }
		if (activeJobs.getAndIncrement() == 0) _isBusy.value = true
		return try {
			val bitmap = loadSourceBitmap(page)
			try {
				mergeOverlappingBlocks(
					translator.translatePage(
						bitmap = bitmap,
						sourceLang = settings.translateSourceLanguage,
						targetLang = settings.translateTargetLanguage,
					),
				)
			} finally {
				bitmap.recycle()
			}
		} finally {
			if (activeJobs.decrementAndGet() == 0) _isBusy.value = false
		}
	}

	/**
	 * Merge text blocks whose bounding rectangles overlap heavily.
	 * The LLM often returns one box per line of dialog plus a larger bubble-level
	 * box around them — painting all of them stacks translations on top of each
	 * other. We greedily merge by IoU / containment.
	 */
	private fun mergeOverlappingBlocks(blocks: List<TranslatedBlock>): List<TranslatedBlock> {
		if (blocks.size <= 1) return blocks
		val remaining = blocks.sortedByDescending { it.rect.areaNorm() }.toMutableList()
		val out = mutableListOf<TranslatedBlock>()
		while (remaining.isNotEmpty()) {
			val pivot = remaining.removeAt(0)
			var merged = pivot
			val it = remaining.iterator()
			while (it.hasNext()) {
				val other = it.next()
				if (shouldMerge(merged.rect, other.rect)) {
					merged = TranslatedBlock(
						originalText = if (other.originalText.isBlank()) merged.originalText
							else (merged.originalText + " " + other.originalText).trim(),
						translatedText = if (other.translatedText.isBlank()) merged.translatedText
							else (merged.translatedText + " " + other.translatedText).trim(),
						rect = android.graphics.RectF(
							minOf(merged.rect.left, other.rect.left),
							minOf(merged.rect.top, other.rect.top),
							maxOf(merged.rect.right, other.rect.right),
							maxOf(merged.rect.bottom, other.rect.bottom),
						),
					)
					it.remove()
				}
			}
			out += merged
		}
		return out
	}

	private fun shouldMerge(a: android.graphics.RectF, b: android.graphics.RectF): Boolean {
		val interLeft = maxOf(a.left, b.left)
		val interTop = maxOf(a.top, b.top)
		val interRight = minOf(a.right, b.right)
		val interBottom = minOf(a.bottom, b.bottom)
		if (interLeft >= interRight || interTop >= interBottom) return false
		val interArea = (interRight - interLeft) * (interBottom - interTop)
		val aArea = a.areaNorm()
		val bArea = b.areaNorm()
		val smaller = minOf(aArea, bArea)
		val union = aArea + bArea - interArea
		val iou = if (union > 0f) interArea / union else 0f
		val containment = if (smaller > 0f) interArea / smaller else 0f
		return iou > 0.25f || containment > 0.55f
	}

	private fun android.graphics.RectF.areaNorm(): Float =
		(right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)

	/** Hide the overlay for a page; reverts state to Idle so the holder reloads the original. */
	fun hideTranslation(pageId: Long) {
		synchronized(statesLock) { jobs[pageId]?.cancel() }
		(stateFor(pageId) as MutableStateFlow<PageTranslationState>).value = PageTranslationState.Idle
	}

	fun forgetPage(pageId: Long) {
		synchronized(statesLock) {
			jobs.remove(pageId)?.cancel()
			states.remove(pageId)
		}
	}

	suspend fun clearAll() {
		synchronized(statesLock) {
			for (job in jobs.values) job.cancel()
			jobs.clear()
			for (state in states.values) state.value = PageTranslationState.Idle
		}
		cache.clearAll()
	}

	private suspend fun loadSourceBitmap(page: MangaPage): android.graphics.Bitmap {
		val uri = pageLoader.loadPage(page, force = false)
		val ready = if (uri.isZipUri()) pageLoader.convertBimap(uri) else uri
		return runInterruptible(Dispatchers.IO) {
			BitmapDecoderCompat.decode(ready.toFile())
		}
	}
}
