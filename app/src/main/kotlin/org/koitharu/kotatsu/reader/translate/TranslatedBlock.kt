package org.koitharu.kotatsu.reader.translate

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * A single text block returned by the multimodal translator.
 *
 * @param rect normalised image-space rectangle (0..1 on each axis).
 */
data class TranslatedBlock(
	val originalText: String,
	val translatedText: String,
	val rect: RectF,
)

sealed class TranslateException(message: String, cause: Throwable? = null) : Exception(message, cause) {
	class NoEndpoint : TranslateException("Translation endpoint is not configured")
	class NoKey : TranslateException("Translation API key is not configured")
	class Http(val code: Int, val responseBody: String) : TranslateException("HTTP $code: ${responseBody.take(200)}")
	class Parse(reason: String, cause: Throwable? = null) : TranslateException("Failed to parse translator response: $reason", cause)
	class Network(cause: Throwable) : TranslateException("Network error: ${cause.message}", cause)
}

sealed interface PageTranslationState {
	data object Idle : PageTranslationState
	data object Loading : PageTranslationState
	data class Done(val rendered: Bitmap, val blocks: List<TranslatedBlock>) : PageTranslationState
	data class Failed(val error: Throwable) : PageTranslationState
}
