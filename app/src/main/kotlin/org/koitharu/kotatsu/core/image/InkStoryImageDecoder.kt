package org.koitharu.kotatsu.core.image

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.isImage
import org.koitharu.kotatsu.parsers.model.MangaSource

object InkStoryImageDecoder {

	private const val INK_STORY_FRAGMENT_PREFIX = "ik=xor:"
	private val INK_STORY_SOURCES = setOf("MANGA_OVH", "MANGA_OVH_UPDATES")
	private val DEFAULT_XOR_KEY = "UySkp0BzPhwlvP2V".toByteArray()

	data class ImagePayload(
		val bytes: ByteArray,
		val mimeType: MimeType,
		val wasDecoded: Boolean,
	)

	fun isInkStorySource(mangaSource: MangaSource?): Boolean = mangaSource?.name in INK_STORY_SOURCES

	fun extractXorKey(pageUrl: String, mangaSource: MangaSource): ByteArray? {
		if (!isInkStorySource(mangaSource)) {
			return null
		}
		val fragment = pageUrl.toHttpUrlOrNull()?.fragment ?: return null
		if (!fragment.startsWith(INK_STORY_FRAGMENT_PREFIX)) {
			return null
		}
		val key = fragment.removePrefix(INK_STORY_FRAGMENT_PREFIX)
		return key.takeIf { it.isNotBlank() }?.toByteArray()
	}

	fun shouldValidateCachedFile(pageUrl: String, mangaSource: MangaSource): Boolean {
		if (!isInkStorySource(mangaSource)) {
			return false
		}
		val url = pageUrl.toHttpUrlOrNull() ?: return true
		return url.host.endsWith("inuko.me") && url.encodedPath.contains("/chapters/")
	}

	fun resolveNetworkPayload(
		bytes: ByteArray,
		responseMimeType: MimeType?,
		pageUrl: String,
		mangaSource: MangaSource,
	): ImagePayload? {
		BitmapDecoderCompat.probeMimeType(bytes)?.let { mimeType ->
			return ImagePayload(bytes = bytes, mimeType = mimeType, wasDecoded = false)
		}
		if (!isInkStorySource(mangaSource)) {
			return responseMimeType?.takeIf { it.isImage }?.let { mimeType ->
				ImagePayload(bytes = bytes, mimeType = mimeType, wasDecoded = false)
			}
		}
		return decodeWithCandidates(
			source = bytes,
			extractedKey = extractXorKey(pageUrl, mangaSource),
		)
	}

	fun resolveLocalPayload(bytes: ByteArray): ImagePayload? {
		BitmapDecoderCompat.probeMimeType(bytes)?.let { mimeType ->
			return ImagePayload(bytes = bytes, mimeType = mimeType, wasDecoded = false)
		}
		return decodeWithCandidates(source = bytes, extractedKey = null)
	}

	private fun decodeWithCandidates(
		source: ByteArray,
		extractedKey: ByteArray?,
	): ImagePayload? {
		val candidates = ArrayList<ByteArray>(2)
		candidates.addDistinct(extractedKey)
		candidates.addDistinct(DEFAULT_XOR_KEY)
		for (key in candidates) {
			val decoded = decodeXor(source, key)
			val mimeType = BitmapDecoderCompat.probeMimeType(decoded) ?: continue
			return ImagePayload(bytes = decoded, mimeType = mimeType, wasDecoded = true)
		}
		return null
	}

	private fun decodeXor(source: ByteArray, key: ByteArray): ByteArray {
		if (key.isEmpty()) {
			return source
		}
		val result = ByteArray(source.size)
		for (i in source.indices) {
			result[i] = (source[i].toInt() xor key[i % key.size].toInt()).toByte()
		}
		return result
	}

	private fun MutableList<ByteArray>.addDistinct(key: ByteArray?) {
		if (key == null || any { it.contentEquals(key) }) {
			return
		}
		add(key)
	}
}
