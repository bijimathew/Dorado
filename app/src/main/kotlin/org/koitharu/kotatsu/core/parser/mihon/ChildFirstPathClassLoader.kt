package org.koitharu.kotatsu.core.parser.mihon

import dalvik.system.PathClassLoader

class ChildFirstPathClassLoader(
	dexPath: String,
	librarySearchPath: String?,
	parent: ClassLoader,
) : PathClassLoader(dexPath, librarySearchPath, parent) {

	private val parentPackages = setOf(
		"java.",
		"javax.",
		"kotlin.",
		"kotlinx.",
		"android.",
		"androidx.",
		"org.json.",
		"org.jsoup.",
		"okhttp3.",
		"okio.",
		"rx.",
		"eu.kanade.tachiyomi.source.",
		"eu.kanade.tachiyomi.network.",
		"eu.kanade.tachiyomi.util.",
		"uy.kohesive.injekt.",
	)

	override fun loadClass(name: String, resolve: Boolean): Class<*> {
		if (parentPackages.any(name::startsWith)) {
			return parent.loadClass(name)
		}
		return try {
			findLoadedClass(name) ?: findClass(name)
		} catch (_: ClassNotFoundException) {
			parent.loadClass(name)
		}
	}
}
