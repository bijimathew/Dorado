package org.koitharu.kotatsu.core.model

import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaSource

data class PluginMangaSource(
	val delegate: MangaSource,
	val jarName: String,
) : MangaSource {

	override val name: String
		get() = "$jarName:${delegate.name}"

	val sourceName: String
		get() = delegate.name

	val locale: String
		get() = delegate.callString("getLocale").orEmpty()

	val contentType: ContentType
		get() = delegate.call("getContentType") ?: ContentType.MANGA

	val title: String
		get() = delegate.callString("getTitle")?.takeIf { it.isNotBlank() } ?: sourceName

	val isBroken: Boolean
		get() = delegate.call("isBroken") ?: delegate.call("getIsBroken") ?: false
}

data class UnresolvedMangaSource(
	override val name: String,
) : MangaSource

private inline fun <reified T> MangaSource.call(methodName: String): T? = runCatching {
	javaClass.getMethod(methodName).invoke(this) as? T
}.getOrNull()

private fun MangaSource.callString(methodName: String): String? = call(methodName)
