package org.koitharu.kotatsu.core.network

import dagger.Lazy
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Interceptor.Chain
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.PluginMangaSource
import org.koitharu.kotatsu.core.parser.MangaLoaderContextImpl
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.core.parser.PluginMangaRepository
import org.koitharu.kotatsu.core.parser.mihon.MihonMangaSource
import org.koitharu.kotatsu.core.parser.mihon.MihonSourceRegistry
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.mergeWith
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.net.IDN
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommonHeadersInterceptor @Inject constructor(
	private val mangaRepositoryFactoryLazy: Lazy<MangaRepository.Factory>,
	private val mangaLoaderContextLazy: Lazy<MangaLoaderContextImpl>,
) : Interceptor {

	override fun intercept(chain: Chain): Response {
		val request = chain.request()
		val source = request.tag(MangaSource::class.java)
			?: request.headers[CommonHeaders.MANGA_SOURCE]?.let { MangaSource(it) }
		val repository = when (source) {
			is MangaParserSource,
			is PluginMangaSource -> mangaRepositoryFactoryLazy.get().create(source)
			else -> {
				if (BuildConfig.DEBUG && source == null) {
					IllegalArgumentException("Request without source tag: ${request.url}")
						.printStackTrace()
				}
				null
			}
		}
		val headersBuilder = request.headers.newBuilder()
			.removeAll(CommonHeaders.MANGA_SOURCE)
		when (repository) {
			is ParserMangaRepository -> repository.getRequestHeaders()
			is PluginMangaRepository -> repository.getRequestHeaders()
			else -> null
		}?.let {
			headersBuilder.mergeWith(it, replaceExisting = false)
		}
		if (source is MihonMangaSource) {
			MihonSourceRegistry.getPageHeaders(source, request.url.toString())?.let {
				headersBuilder.mergeWith(it, replaceExisting = false)
			}
		}
		if (headersBuilder[CommonHeaders.USER_AGENT] == null) {
			headersBuilder[CommonHeaders.USER_AGENT] = mangaLoaderContextLazy.get().getDefaultUserAgent()
		}
		val parserDomain = when (repository) {
			is ParserMangaRepository -> repository.domain
			is PluginMangaRepository -> repository.domain
			else -> null
		}
		if (headersBuilder[CommonHeaders.REFERER] == null && !parserDomain.isNullOrBlank()) {
			val idn = IDN.toASCII(parserDomain)
			headersBuilder.trySet(CommonHeaders.REFERER, "https://$idn/")
		} else if (headersBuilder[CommonHeaders.REFERER] == null && source is MihonMangaSource) {
			MihonSourceRegistry.getDefaultReferer(source)?.let {
				headersBuilder.trySet(CommonHeaders.REFERER, it)
			}
		}
		val newRequest = request.newBuilder().headers(headersBuilder.build()).build()
		return (repository as? Interceptor)?.interceptSafe(ProxyChain(chain, newRequest)) ?: chain.proceed(newRequest)
	}

	private fun Headers.Builder.trySet(name: String, value: String) = try {
		set(name, value)
	} catch (e: IllegalArgumentException) {
		e.printStackTraceDebug()
	}

	private fun Interceptor.interceptSafe(chain: Chain): Response = runCatchingCancellable {
		intercept(chain)
	}.getOrElse { e ->
		if (e is IOException || e is Error) {
			throw e
		} else {
			// only IOException can be safely thrown from an Interceptor
			throw IOException("Error in interceptor: ${e.message}", e)
		}
	}

	private class ProxyChain(
		private val delegate: Chain,
		private val request: Request,
	) : Chain by delegate {

		override fun request(): Request = request
	}
}
