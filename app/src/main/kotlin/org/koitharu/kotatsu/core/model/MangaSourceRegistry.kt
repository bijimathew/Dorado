package org.koitharu.kotatsu.core.model

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.concurrent.CopyOnWriteArrayList

object MangaSourceRegistry {

	val sources: MutableList<MangaSource> = CopyOnWriteArrayList()

	@Volatile
	var version: Int = 0
		private set

	val entries: List<MangaSource>
		get() = sources

	val updates = MutableSharedFlow<Unit>(
		replay = 1,
		onBufferOverflow = BufferOverflow.DROP_OLDEST,
	)

	fun replaceAll(value: Collection<MangaSource>) {
		sources.clear()
		sources.addAll(value)
		incrementVersion()
		updates.tryEmit(Unit)
	}

	fun resolve(name: String): MangaSource? {
		sources.firstOrNull { it.name == name }?.let { return it }
		sources.firstOrNull {
			it is PluginMangaSource && it.sourceName == name
		}?.let { return it }
		if (':' in name) {
			val shortName = name.substringAfter(':')
			sources.firstOrNull {
				it.name == shortName || (it is PluginMangaSource && it.sourceName == shortName)
			}?.let { return it }
		}
		return null
	}

	private fun incrementVersion() {
		version++
	}
}
