package org.koitharu.kotatsu.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource

class MangaIdentityTest {

	@Test
	fun `Manga OVH legacy and current urls are same identity`() {
		val legacy = manga(
			source = MangaParserSource.MANGA_OVH,
			id = 1L,
			url = "/manga/i-became-the-games-biggest-villain",
			publicUrl = "https://manga.ovh/manga/i-became-the-games-biggest-villain",
		)
		val current = manga(
			source = MangaParserSource.MANGA_OVH_UPDATES,
			id = 2L,
			url = "/content/i-became-the-games-biggest-villain",
			publicUrl = "https://inkstory.net/content/i-became-the-games-biggest-villain",
		)

		assertTrue(legacy.isSameEntryAs(current))
		assertTrue(legacy.identityKeys().intersect(current.identityKeys()).isNotEmpty())
	}

	@Test
	fun `different Manga OVH urls are not same identity`() {
		val first = manga(
			source = MangaParserSource.MANGA_OVH,
			id = 1L,
			url = "/content/first",
		)
		val second = manga(
			source = MangaParserSource.MANGA_OVH,
			id = 2L,
			url = "/content/second",
		)

		assertFalse(first.isSameEntryAs(second))
	}

	@Test
	fun `Remanga trailing underscore is ignored`() {
		val first = manga(
			source = MangaParserSource.REMANGA,
			id = 1L,
			url = "/manga/title_",
		)
		val second = manga(
			source = MangaParserSource.REMANGA,
			id = 2L,
			url = "/manga/title",
		)

		assertTrue(first.isSameEntryAs(second))
	}

	private fun manga(
		source: MangaSource,
		id: Long,
		url: String,
		publicUrl: String = "https://example.org$url",
	) = Manga(
		id = id,
		title = "Title",
		altTitles = emptySet(),
		url = url,
		publicUrl = publicUrl,
		rating = -1f,
		contentRating = null,
		coverUrl = "",
		tags = emptySet(),
		state = null,
		authors = emptySet(),
		source = source,
		largeCoverUrl = null,
		description = null,
		chapters = null,
	)
}
