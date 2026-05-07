package org.koitharu.kotatsu.core.db.migrations

import androidx.sqlite.db.SupportSQLiteDatabase

object MangaIdentityMerge {

	fun mergeManga(db: SupportSQLiteDatabase, oldId: Long, newId: Long) {
		if (oldId == newId) {
			return
		}
		db.execSQL("DROP TABLE IF EXISTS manga_identity_canonical")
		db.execSQL("DROP TABLE IF EXISTS manga_identity_merge")
		db.execSQL("DROP TABLE IF EXISTS manga_identity_best_history")
		db.execSQL(
			"""
			CREATE TEMP TABLE manga_identity_merge (
				old_id INTEGER NOT NULL PRIMARY KEY,
				new_id INTEGER NOT NULL
			)
			""".trimIndent(),
		)
		db.execSQL(
			"INSERT INTO manga_identity_merge(old_id, new_id) VALUES(?, ?)",
			arrayOf(oldId, newId),
		)
		mergePrepared(db)
	}

	fun mergeDuplicateMangaByIdentity(db: SupportSQLiteDatabase) {
		db.execSQL("DROP TABLE IF EXISTS manga_identity_canonical")
		db.execSQL("DROP TABLE IF EXISTS manga_identity_merge")
		db.execSQL("DROP TABLE IF EXISTS manga_identity_best_history")
		db.execSQL(
			"""
			CREATE TEMP TABLE manga_identity_canonical (
				source TEXT NOT NULL,
				identity_url TEXT NOT NULL,
				new_id INTEGER NOT NULL,
				PRIMARY KEY(source, identity_url)
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO manga_identity_canonical(source, identity_url, new_id)
			SELECT ${identitySource("m")}, ${identityUrl("m")}, COALESCE(
				(
					SELECT f.manga_id
					FROM favourites AS f
					INNER JOIN manga AS mf ON mf.manga_id = f.manga_id
					WHERE ${identitySource("mf")} = ${identitySource("m")} AND ${identityUrl("mf")} = ${identityUrl("m")} AND f.deleted_at = 0
					GROUP BY f.manga_id
					ORDER BY COUNT(*) DESC, MIN(f.created_at) ASC
					LIMIT 1
				),
				(
					SELECT h.manga_id
					FROM history AS h
					INNER JOIN manga AS mh ON mh.manga_id = h.manga_id
					WHERE ${identitySource("mh")} = ${identitySource("m")} AND ${identityUrl("mh")} = ${identityUrl("m")} AND h.deleted_at = 0
					ORDER BY h.updated_at DESC
					LIMIT 1
				),
				MIN(m.manga_id)
			)
			FROM manga AS m
			GROUP BY ${identitySource("m")}, ${identityUrl("m")}
			HAVING COUNT(*) > 1
			""".trimIndent(),
		)
		db.execSQL(
			"""
			CREATE TEMP TABLE manga_identity_merge AS
			SELECT m.manga_id AS old_id, c.new_id AS new_id
			FROM manga AS m
			INNER JOIN manga_identity_canonical AS c ON c.source = ${identitySource("m")} AND c.identity_url = ${identityUrl("m")}
			WHERE m.manga_id != c.new_id
			""".trimIndent(),
		)
		mergePrepared(db)
	}

	private fun mergePrepared(db: SupportSQLiteDatabase) {
		db.execSQL(
			"""
			CREATE TEMP TABLE manga_identity_best_history (
				manga_id INTEGER NOT NULL PRIMARY KEY,
				created_at INTEGER NOT NULL,
				updated_at INTEGER NOT NULL,
				chapter_id INTEGER NOT NULL,
				page INTEGER NOT NULL,
				scroll REAL NOT NULL,
				percent REAL NOT NULL,
				deleted_at INTEGER NOT NULL,
				chapters INTEGER NOT NULL
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO manga_identity_best_history
			SELECT a.new_id, h.created_at, h.updated_at, h.chapter_id, h.page, h.scroll, h.percent, h.deleted_at, h.chapters
			FROM history AS h
			INNER JOIN manga_identity_merge AS a ON a.old_id = h.manga_id
			ORDER BY CASE WHEN h.deleted_at = 0 THEN 0 ELSE 1 END, h.updated_at DESC
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO history(manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters)
			SELECT manga_id, created_at, updated_at, chapter_id, page, scroll, percent, deleted_at, chapters
			FROM manga_identity_best_history
			""".trimIndent(),
		)
		db.execSQL(
			"""
			UPDATE history
			SET
				created_at = (SELECT created_at FROM manga_identity_best_history WHERE manga_id = history.manga_id),
				updated_at = (SELECT updated_at FROM manga_identity_best_history WHERE manga_id = history.manga_id),
				chapter_id = (SELECT chapter_id FROM manga_identity_best_history WHERE manga_id = history.manga_id),
				page = (SELECT page FROM manga_identity_best_history WHERE manga_id = history.manga_id),
				scroll = (SELECT scroll FROM manga_identity_best_history WHERE manga_id = history.manga_id),
				percent = (SELECT percent FROM manga_identity_best_history WHERE manga_id = history.manga_id),
				deleted_at = (SELECT deleted_at FROM manga_identity_best_history WHERE manga_id = history.manga_id),
				chapters = (SELECT chapters FROM manga_identity_best_history WHERE manga_id = history.manga_id)
			WHERE manga_id IN (SELECT manga_id FROM manga_identity_best_history)
				AND (
					(
						(SELECT deleted_at FROM manga_identity_best_history WHERE manga_id = history.manga_id) = 0
						AND (
							history.deleted_at != 0
							OR (SELECT updated_at FROM manga_identity_best_history WHERE manga_id = history.manga_id) >= history.updated_at
						)
					)
					OR (
						history.deleted_at != 0
						AND (SELECT updated_at FROM manga_identity_best_history WHERE manga_id = history.manga_id) >= history.updated_at
					)
				)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO stats(manga_id, started_at, duration, pages)
			SELECT a.new_id, s.started_at, s.duration, s.pages
			FROM stats AS s
			INNER JOIN manga_identity_merge AS a ON a.old_id = s.manga_id
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO favourites(manga_id, category_id, sort_key, pinned, created_at, deleted_at)
			SELECT a.new_id, f.category_id, f.sort_key, f.pinned, f.created_at, f.deleted_at
			FROM favourites AS f
			INNER JOIN manga_identity_merge AS a ON a.old_id = f.manga_id
			""".trimIndent(),
		)
		db.execSQL(
			"""
			UPDATE favourites
			SET deleted_at = 0
			WHERE EXISTS (
				SELECT 1
				FROM favourites AS f
				INNER JOIN manga_identity_merge AS a ON a.old_id = f.manga_id
				WHERE a.new_id = favourites.manga_id
					AND f.category_id = favourites.category_id
					AND f.deleted_at = 0
			)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO bookmarks(manga_id, page_id, chapter_id, page, scroll, image, created_at, percent)
			SELECT a.new_id, b.page_id, b.chapter_id, b.page, b.scroll, b.image, b.created_at, b.percent
			FROM bookmarks AS b
			INNER JOIN manga_identity_merge AS a ON a.old_id = b.manga_id
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO chapters(chapter_id, manga_id, name, number, volume, url, scanlator, upload_date, branch, source, `index`)
			SELECT c.chapter_id, a.new_id, c.name, c.number, c.volume, c.url, c.scanlator, c.upload_date, c.branch, c.source, c.`index`
			FROM chapters AS c
			INNER JOIN manga_identity_merge AS a ON a.old_id = c.manga_id
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO manga_tags(manga_id, tag_id)
			SELECT a.new_id, mt.tag_id
			FROM manga_tags AS mt
			INNER JOIN manga_identity_merge AS a ON a.old_id = mt.manga_id
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO preferences(
				manga_id, mode, cf_brightness, cf_contrast, cf_invert, cf_grayscale,
				title_override, cover_override, content_rating_override, cf_book
			)
			SELECT
				a.new_id, p.mode, p.cf_brightness, p.cf_contrast, p.cf_invert, p.cf_grayscale,
				p.title_override, p.cover_override, p.content_rating_override, p.cf_book
			FROM preferences AS p
			INNER JOIN manga_identity_merge AS a ON a.old_id = p.manga_id
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO tracks(manga_id, last_chapter_id, chapters_new, last_check_time, last_chapter_date, last_result, last_error)
			SELECT a.new_id, t.last_chapter_id, t.chapters_new, t.last_check_time, t.last_chapter_date, t.last_result, t.last_error
			FROM tracks AS t
			INNER JOIN manga_identity_merge AS a ON a.old_id = t.manga_id
			""".trimIndent(),
		)
		db.execSQL(
			"""
			UPDATE track_logs
			SET manga_id = (SELECT new_id FROM manga_identity_merge WHERE old_id = track_logs.manga_id)
			WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO suggestions(manga_id, relevance, created_at)
			SELECT a.new_id, s.relevance, s.created_at
			FROM suggestions AS s
			INNER JOIN manga_identity_merge AS a ON a.old_id = s.manga_id
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO local_index(manga_id, path)
			SELECT a.new_id, l.path
			FROM local_index AS l
			INNER JOIN manga_identity_merge AS a ON a.old_id = l.manga_id
			""".trimIndent(),
		)
		db.execSQL(
			"""
			INSERT OR IGNORE INTO scrobblings(scrobbler, id, manga_id, target_id, status, chapter, comment, rating)
			SELECT s.scrobbler, s.id, a.new_id, s.target_id, s.status, s.chapter, s.comment, s.rating
			FROM scrobblings AS s
			INNER JOIN manga_identity_merge AS a ON a.old_id = s.manga_id
			""".trimIndent(),
		)
		db.execSQL("DELETE FROM stats WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DELETE FROM history WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DELETE FROM favourites WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DELETE FROM bookmarks WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DELETE FROM chapters WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DELETE FROM manga_tags WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DELETE FROM preferences WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DELETE FROM tracks WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DELETE FROM track_logs WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DELETE FROM suggestions WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DELETE FROM local_index WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DELETE FROM scrobblings WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DELETE FROM manga WHERE manga_id IN (SELECT old_id FROM manga_identity_merge)")
		db.execSQL("DROP TABLE IF EXISTS manga_identity_best_history")
		db.execSQL("DROP TABLE IF EXISTS manga_identity_merge")
		db.execSQL("DROP TABLE IF EXISTS manga_identity_canonical")
	}

	private fun identitySource(alias: String): String {
		return "CASE WHEN $alias.source = 'MANGA_OVH_UPDATES' THEN 'MANGA_OVH' ELSE $alias.source END"
	}

	private fun identityUrl(alias: String): String {
		val source = identitySource(alias)
		val url = pathUrl("$alias.url")
		val publicUrl = pathUrl("$alias.public_url")
		val mangaOvhUrl = "CASE WHEN $alias.public_url != '' THEN $publicUrl ELSE $url END"
		return """
			CASE
				WHEN $source = 'REMANGA' THEN rtrim($url, '_')
				WHEN $source = 'MANGA_OVH' THEN
					CASE
						WHEN $mangaOvhUrl LIKE '/manga/%' THEN '/content/' || substr($mangaOvhUrl, 8)
						ELSE $mangaOvhUrl
					END
				ELSE $url
			END
		""".trimIndent()
	}

	private fun pathUrl(column: String): String {
		val schemeIndex = "instr($column, '://')"
		val pathIndex = "instr(substr($column, $schemeIndex + 3), '/')"
		return """
			rtrim(
				CASE
					WHEN $schemeIndex = 0 THEN $column
					WHEN $pathIndex = 0 THEN ''
					ELSE substr($column, $schemeIndex + $pathIndex + 2)
				END,
				'/'
			)
		""".trimIndent()
	}
}
