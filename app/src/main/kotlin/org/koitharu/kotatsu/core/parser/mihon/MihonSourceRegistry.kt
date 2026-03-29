package org.koitharu.kotatsu.core.parser.mihon

import okhttp3.Headers
import eu.kanade.tachiyomi.source.model.Page
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object MihonSourceRegistry {

	private val sources = Collections.synchronizedMap(WeakHashMap<Any, MangaSource>())
	private val definitions = ConcurrentHashMap<String, MihonMangaSource>()
	private val pageHeaders = ConcurrentHashMap<String, Headers>()
	private val pageDefinitions = ConcurrentHashMap<String, Page>()
	private val defaultReferers = ConcurrentHashMap<String, String>()

	fun register(sourceInstance: Any, source: MangaSource, defaultReferer: String?) {
		sources[sourceInstance] = source
		(source as? MihonMangaSource)?.let {
			definitions[it.name] = it
		}
		if (!defaultReferer.isNullOrBlank()) {
			defaultReferers[source.name] = defaultReferer
		}
	}

	fun findSource(sourceInstance: Any): MangaSource? = sources[sourceInstance]

	fun resolveSource(source: MihonMangaSource): MihonMangaSource? = definitions[source.name]

	fun rememberPageHeaders(source: MangaSource, pageUrl: String, headers: Headers) {
		if (headers.size == 0) {
			return
		}
		pageHeaders[key(source, pageUrl)] = headers
	}

	fun getPageHeaders(source: MangaSource, pageUrl: String): Headers? {
		return pageHeaders[key(source, pageUrl)]
	}

	fun rememberPage(source: MangaSource, pageUrl: String, page: Page) {
		pageDefinitions[key(source, pageUrl)] = page
	}

	fun getPage(source: MangaSource, pageUrl: String): Page? {
		return pageDefinitions[key(source, pageUrl)]
	}

	fun getDefaultReferer(source: MangaSource): String? = defaultReferers[source.name]

	private fun key(source: MangaSource, pageUrl: String): String = source.name + '\u0000' + pageUrl
}
