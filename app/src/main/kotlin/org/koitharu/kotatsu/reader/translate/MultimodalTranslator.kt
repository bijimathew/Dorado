package org.koitharu.kotatsu.reader.translate

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.util.await
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MultimodalTranslator @Inject constructor(
	@MangaHttpClient private val okHttpClient: OkHttpClient,
	private val settings: AppSettings,
) {

	/**
	 * Send the page bitmap to the configured multimodal LLM and parse the JSON response into [TranslatedBlock]s.
	 * Returned rectangles are normalised image-space (0..1 on each axis).
	 *
	 * @throws TranslateException with a categorised reason on any failure
	 */
	suspend fun translatePage(
		bitmap: Bitmap,
		sourceLang: String,
		targetLang: String,
	): List<TranslatedBlock> = withContext(Dispatchers.IO) {
		val endpoint = settings.translateEndpoint.trim()
		val apiKey = settings.translateApiKey.trim()
		val model = settings.translateModel.trim().ifBlank {
			when (settings.translateProvider) {
				TranslateProvider.GEMINI -> "gemini-2.5-flash"
				TranslateProvider.OPENAI_COMPATIBLE -> "gpt-4o-mini"
			}
		}

		if (endpoint.isEmpty()) throw TranslateException.NoEndpoint()
		if (apiKey.isEmpty()) throw TranslateException.NoKey()

		val base64Image = runInterruptible { encodeBitmapToBase64(bitmap) }
		if (base64Image.isEmpty()) {
			throw TranslateException.Parse("Failed to encode bitmap")
		}

		val isNativeGoogleFormat = settings.translateProvider == TranslateProvider.GEMINI ||
			endpoint.contains("generateContent") ||
			endpoint.contains("googleapis.com/v1beta/models/")

		val payload = if (isNativeGoogleFormat) {
			buildGeminiPayload(base64Image, sourceLang, targetLang, model)
		} else {
			buildOpenAiPayload(base64Image, sourceLang, targetLang, model)
		}

		val finalUrl = resolveUrl(endpoint, apiKey, isNativeGoogleFormat)
		// org.json escapes '/' to '\/' which trips some restrictive proxies — undo that.
		val payloadStr = payload.toString().replace("\\/", "/")

		val request = Request.Builder()
			.url(finalUrl)
			.post(payloadStr.toRequestBody(JSON_MEDIA_TYPE))
			.apply {
				if (!isNativeGoogleFormat) {
					header("Authorization", "Bearer $apiKey")
				}
				applyCustomHeaders(this)
			}
			.build()

		val response = try {
			okHttpClient.newCall(request).await()
		} catch (e: IOException) {
			throw TranslateException.Network(e)
		}

		response.use {
			val body = it.body?.string().orEmpty()
			if (!it.isSuccessful) {
				throw TranslateException.Http(it.code, body)
			}
			parseResponse(body, bitmap.width, bitmap.height)
		}
	}

	private fun buildOpenAiPayload(base64Image: String, sourceLang: String, targetLang: String, model: String): JSONObject {
		val combinedPrompt = "$SYSTEM_PROMPT\n\n${buildUserPrompt(sourceLang, targetLang)}"
		return JSONObject().apply {
			put("model", model)
			put("temperature", 0.1)
			put("max_tokens", 4096)
			put(
				"messages",
				JSONArray().put(
					JSONObject().put("role", "user").put(
						"content",
						JSONArray()
							.put(JSONObject().put("type", "text").put("text", combinedPrompt))
							.put(
								JSONObject()
									.put("type", "image_url")
									.put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64Image")),
							),
					),
				),
			)
		}
	}

	private fun buildGeminiPayload(base64Image: String, sourceLang: String, targetLang: String, model: String): JSONObject {
		// model is encoded in the endpoint URL for native Gemini — model arg unused.
		return JSONObject().apply {
			put(
				"system_instruction",
				JSONObject().put("parts", JSONObject().put("text", SYSTEM_PROMPT)),
			)
			put(
				"contents",
				JSONArray().put(
					JSONObject()
						.put("role", "user")
						.put(
							"parts",
							JSONArray()
								.put(JSONObject().put("text", buildUserPrompt(sourceLang, targetLang)))
								.put(
									JSONObject().put(
										"inline_data",
										JSONObject()
											.put("mime_type", "image/jpeg")
											.put("data", base64Image),
									),
								),
						),
				),
			)
			put(
				"generationConfig",
				JSONObject()
					.put("temperature", 0)
					.put("responseMimeType", "application/json"),
			)
		}
	}

	private fun resolveUrl(endpoint: String, apiKey: String, isNativeGoogleFormat: Boolean): String {
		val trimmed = endpoint.trimEnd('/')
		if (!isNativeGoogleFormat) {
			return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
		}
		if (!endpoint.contains("key=")) {
			val sep = if (endpoint.contains("?")) "&" else "?"
			return "$endpoint${sep}key=$apiKey"
		}
		return endpoint
	}

	private fun applyCustomHeaders(builder: Request.Builder) {
		val headers = settings.translateCustomHeaders.trim()
		if (headers.isBlank() || !headers.startsWith("{")) return
		runCatching {
			val json = JSONObject(headers)
			for (key in json.keys()) {
				val value = json.optString(key)
				if (value.isNotBlank()) builder.header(key, value)
			}
		}
	}

	private fun buildUserPrompt(sourceLang: String, targetLang: String): String = buildString {
		appendLine("Please identify all the text in the image.")
		appendLine("This is a manga page. The text is in $sourceLang. Please translate it into $targetLang.")
		appendLine("Please output the information of each text block in a JSON array format. Do not use markdown blocks, output raw JSON only.")
		appendLine("Group text by speech bubble or caption: return ONE entry per bubble/caption/SFX, not one per line. If a bubble contains multiple lines, concatenate them into a single `original_text` separated by spaces, and return a single coordinates rectangle that covers the entire bubble.")
		appendLine("The JSON format MUST be an array of objects, where each object contains:")
		appendLine("- `coordinates`: an array of exactly 4 numbers [ymin, xmin, ymax, xmax], representing normalized coordinates from 0 to 1000 that tightly enclose the entire bubble/caption. If you are unsure about the coordinates, strictly output [0, 0, 0, 0] instead of leaving it empty.")
		appendLine("- `original_text`: the original text from the bubble (joined with spaces if multi-line).")
		appendLine("- `translated_text`: the $targetLang translation.")
		appendLine("- IMPORTANT: If the detected text is explicitly a pirate manga website URL, watermark, or completely meaningless background texture rather than human dialogue/story structure, set `translated_text` exactly to '$IGNORE_BLOCK_MARKER'.")
	}

	private fun parseResponse(rawBody: String, imageWidth: Int, imageHeight: Int): List<TranslatedBlock> {
		if (rawBody.isBlank()) throw TranslateException.Parse("empty body")
		val content = extractMessageContent(rawBody)
		if (content.isBlank()) throw TranslateException.Parse("no message content")
		val jsonArray = parseJsonArray(content)
			?: throw TranslateException.Parse("not a JSON array: ${content.take(120)}")

		val blocks = mutableListOf<TranslatedBlock>()
		for (i in 0 until jsonArray.length()) {
			val obj = jsonArray.optJSONObject(i) ?: continue
			val coords = obj.optJSONArray("coordinates") ?: continue
			if (coords.length() < 4) continue

			val yminNorm = coords.optDouble(0, 0.0)
			val xminNorm = coords.optDouble(1, 0.0)
			val ymaxNorm = coords.optDouble(2, 0.0)
			val xmaxNorm = coords.optDouble(3, 0.0)

			val left = ((xminNorm / 1000.0) * imageWidth).coerceIn(0.0, imageWidth.toDouble())
			val top = ((yminNorm / 1000.0) * imageHeight).coerceIn(0.0, imageHeight.toDouble())
			val right = ((xmaxNorm / 1000.0) * imageWidth).coerceIn(0.0, imageWidth.toDouble())
			val bottom = ((ymaxNorm / 1000.0) * imageHeight).coerceIn(0.0, imageHeight.toDouble())
			if (left >= right || top >= bottom) continue

			val originalText = obj.optString("original_text", "")
			val translatedText = obj.optString("translated_text", "").ifBlank {
				obj.optString("translation", "")
			}
			if (translatedText.isBlank() || translatedText.contains(IGNORE_BLOCK_MARKER)) continue

			blocks += TranslatedBlock(
				originalText = originalText,
				translatedText = translatedText,
				rect = RectF(
					(left / imageWidth).toFloat(),
					(top / imageHeight).toFloat(),
					(right / imageWidth).toFloat(),
					(bottom / imageHeight).toFloat(),
				),
			)
		}
		return blocks
	}

	private fun extractMessageContent(rawBody: String): String = runCatching {
		val json = JSONObject(rawBody)
		val choices = json.optJSONArray("choices")
		if (choices != null && choices.length() > 0) {
			val message = choices.optJSONObject(0)?.optJSONObject("message")
			if (message != null) return@runCatching message.optString("content", "")
		}
		val candidates = json.optJSONArray("candidates")
		if (candidates != null && candidates.length() > 0) {
			val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
			if (parts != null && parts.length() > 0) {
				return@runCatching parts.optJSONObject(0)?.optString("text", "").orEmpty()
			}
		}
		""
	}.getOrDefault("")

	private fun parseJsonArray(content: String): JSONArray? {
		var clean = content.replace("```json", "").replace("```", "").trim()
		// Tolerate the occasional malformed `"key":,` shape from Gemini.
		clean = clean.replace(Regex("\"\\s*:\\s*,"), "\": null,")
		return runCatching { JSONArray(clean) }.getOrNull()
	}

	private fun encodeBitmapToBase64(bitmap: Bitmap): String {
		val maxDim = 1024
		val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
			val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
			Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
		} else {
			bitmap
		}
		try {
			val out = ByteArrayOutputStream()
			scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
			return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
		} finally {
			if (scaled !== bitmap) scaled.recycle()
		}
	}

	companion object {
		private const val SYSTEM_PROMPT = "You are a manga translation assistant with precise vision capabilities."
		private const val IGNORE_BLOCK_MARKER = "KAISOKU_IGNORE_BLOCK"
		private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
	}
}
