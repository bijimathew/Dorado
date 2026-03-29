package org.koitharu.kotatsu.core.parser.mihon

import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.OkHttpClient

class MihonNetworkHelper(
	private val httpClient: OkHttpClient,
	private val defaultUserAgent: () -> String,
) : NetworkHelper() {

	override val client: OkHttpClient
		get() = httpClient

	override fun defaultUserAgentProvider(): String = defaultUserAgent()
}
