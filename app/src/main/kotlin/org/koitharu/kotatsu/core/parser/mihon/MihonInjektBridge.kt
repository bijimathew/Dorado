package org.koitharu.kotatsu.core.parser.mihon

import android.app.Application
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.parser.MangaLoaderContextImpl
import javax.inject.Inject
import javax.inject.Singleton
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

@Singleton
class MihonInjektBridge @Inject constructor(
	@ApplicationContext private val context: Context,
	@MangaHttpClient private val httpClient: OkHttpClient,
	private val cookieJar: CookieJar,
	private val mangaLoaderContextLazy: dagger.Lazy<MangaLoaderContextImpl>,
) {

	@Volatile
	private var initialized = false

	@Synchronized
	fun initialize() {
		if (initialized) {
			return
		}
		val json = Json {
			ignoreUnknownKeys = true
			explicitNulls = false
		}
		val networkHelper = MihonNetworkHelper(httpClient) {
			mangaLoaderContextLazy.get().getDefaultUserAgent()
		}
		Injekt.importModule(object : InjektModule {
			override fun InjektRegistrar.registerInjectables() {
				addSingleton(context.applicationContext as Application)
				addSingletonFactory<Context> { context.applicationContext }
				addSingletonFactory<NetworkHelper> { networkHelper }
				addSingletonFactory<OkHttpClient> { httpClient }
				addSingletonFactory<CookieJar> { cookieJar }
				addSingleton(json)
				addSingletonFactory<StringFormat> { json }
				addSingletonFactory<SerialFormat> { json }
			}
		})
		initialized = true
	}
}
