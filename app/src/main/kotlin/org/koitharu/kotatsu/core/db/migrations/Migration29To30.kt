package org.koitharu.kotatsu.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration29To30 : Migration(29, 30) {
	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("DROP TABLE IF EXISTS `scrobblings`")
	}
}
