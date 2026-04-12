package org.koitharu.kotatsu.core.parser.mihon.repo

import kotlinx.serialization.Serializable

@Serializable
data class MihonExtensionRepo(
	val baseUrl: String,
	val name: String,
	val shortName: String? = null,
	val website: String,
	val signingKeyFingerprint: String,
)

data class MihonAvailableExtension(
	val repo: MihonExtensionRepo,
	val name: String,
	val pkgName: String,
	val versionName: String,
	val versionCode: Long,
	val libVersion: Double,
	val lang: String,
	val isNsfw: Boolean,
	val sources: List<MihonAvailableExtensionSource>,
	val apkName: String,
	val iconUrl: String,
)

data class MihonAvailableExtensionSource(
	val id: Long,
	val lang: String,
	val name: String,
	val baseUrl: String,
)

data class MihonRepoExtensionDescriptor(
	val extension: MihonAvailableExtension,
	val installedVersionName: String? = null,
	val installedVersionCode: Long? = null,
	val installedLibVersion: Double? = null,
	val isInstalledPrivately: Boolean = false,
	val isInstalledExternally: Boolean = false,
	val hasUpdate: Boolean = false,
)

@Serializable
internal data class MihonExtensionRepoMetaResponse(
	val meta: MihonExtensionRepoMetaDto,
)

@Serializable
internal data class MihonExtensionRepoMetaDto(
	val name: String,
	val shortName: String? = null,
	val website: String,
	val signingKeyFingerprint: String,
)

@Serializable
internal data class MihonExtensionIndexEntryDto(
	val name: String,
	val pkg: String,
	val apk: String,
	val lang: String,
	val code: Long,
	val version: String,
	val nsfw: Int,
	val sources: List<MihonExtensionIndexSourceDto>? = null,
)

@Serializable
internal data class MihonExtensionIndexSourceDto(
	val id: Long,
	val lang: String,
	val name: String,
	val baseUrl: String,
)

internal fun MihonExtensionRepoMetaResponse.toRepo(baseUrl: String): MihonExtensionRepo {
	return MihonExtensionRepo(
		baseUrl = baseUrl,
		name = meta.name,
		shortName = meta.shortName,
		website = meta.website,
		signingKeyFingerprint = meta.signingKeyFingerprint,
	)
}
