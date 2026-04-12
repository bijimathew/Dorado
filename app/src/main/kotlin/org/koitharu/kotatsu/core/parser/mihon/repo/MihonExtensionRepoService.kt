package org.koitharu.kotatsu.core.parser.mihon.repo

import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.parser.mihon.MihonExtensionPackageUtil
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonExtensionRepoService @Inject constructor(
	@MangaHttpClient private val httpClient: OkHttpClient,
) {

	private val json = Json {
		ignoreUnknownKeys = true
		explicitNulls = false
	}

	suspend fun resolveRepo(indexUrl: String): ResolveResult {
		val normalizedIndexUrl = normalizeIndexUrl(indexUrl) ?: return ResolveResult.InvalidUrl
		val baseUrl = normalizedIndexUrl.removeSuffix("/index.min.json")
		val repo = runCatching { fetchRepoDetails(baseUrl) }.getOrNull() ?: return ResolveResult.InvalidRepo
		return ResolveResult.Success(repo)
	}

	suspend fun fetchExtensions(repo: MihonExtensionRepo): List<MihonAvailableExtension> {
		val body = httpClient.newCall(
			Request.Builder()
				.url("${repo.baseUrl}/index.min.json")
				.build(),
		).awaitSuccess().use { response ->
			response.body.string()
		}
		return json.decodeFromString<List<MihonExtensionIndexEntryDto>>(body)
			.mapNotNull { dto ->
				val libVersion = MihonExtensionPackageUtil.parseLibVersion(dto.version) ?: return@mapNotNull null
				if (libVersion !in MihonExtensionPackageUtil.LIB_VERSION_MIN..MihonExtensionPackageUtil.LIB_VERSION_MAX) {
					return@mapNotNull null
				}
				MihonAvailableExtension(
					repo = repo,
					name = dto.name.removePrefix("Tachiyomi: ").trim(),
					pkgName = dto.pkg,
					versionName = dto.version,
					versionCode = dto.code,
					libVersion = libVersion,
					lang = dto.lang,
					isNsfw = dto.nsfw == 1,
					sources = dto.sources.orEmpty().map { source ->
						MihonAvailableExtensionSource(
							id = source.id,
							lang = source.lang,
							name = source.name,
							baseUrl = source.baseUrl,
						)
					},
					apkName = dto.apk,
					iconUrl = "${repo.baseUrl}/icon/${dto.pkg}.png",
				)
			}
			.sortedBy { it.name.lowercase() }
	}

	fun getApkUrl(extension: MihonAvailableExtension): String {
		return "${extension.repo.baseUrl}/apk/${extension.apkName}"
	}

	private suspend fun fetchRepoDetails(baseUrl: String): MihonExtensionRepo {
		val body = httpClient.newCall(
			Request.Builder()
				.url("$baseUrl/repo.json")
				.build(),
		).awaitSuccess().use { response ->
			response.body.string()
		}
		return json.decodeFromString<MihonExtensionRepoMetaResponse>(body).toRepo(baseUrl)
	}

	private fun normalizeIndexUrl(value: String): String? {
		return value.trim()
			.toHttpUrlOrNull()
			?.toString()
			?.takeIf { it.matches(REPO_URL_REGEX) }
	}

	sealed interface ResolveResult {
		data class Success(val repo: MihonExtensionRepo) : ResolveResult
		data object InvalidUrl : ResolveResult
		data object InvalidRepo : ResolveResult
	}

	private companion object {
		val REPO_URL_REGEX = """^https://.*/index\.min\.json$""".toRegex()
	}
}
