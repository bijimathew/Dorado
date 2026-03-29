package org.koitharu.kotatsu.core.parser.mihon

import android.content.Context
import org.koitharu.kotatsu.parsers.model.MangaSource

class MihonMangaSource(
	val packageName: String,
	val sourceId: Long,
	val displayName: String? = null,
	val locale: String? = null,
	val isNsfwSource: Boolean = false,
) : MangaSource {

	override val name: String
		get() = "mihon:$packageName/$sourceId"

	fun resolved(): MihonMangaSource = MihonSourceRegistry.resolveSource(this) ?: this

	fun matches(other: MihonMangaSource): Boolean {
		return packageName == other.packageName && sourceId == other.sourceId
	}

	fun resolveName(context: Context): String {
		resolved().displayName?.takeIf { it.isNotBlank() }?.let { return it }
		return runCatching {
			val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
			context.packageManager.getApplicationLabel(appInfo).toString()
		}.getOrElse {
			packageName.substringAfterLast('.')
		}
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is MangaSource) return false
		return name == other.name
	}

	override fun hashCode(): Int = name.hashCode()

	override fun toString(): String = name
}
