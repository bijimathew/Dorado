package org.koitharu.kotatsu.core.nav

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.parsers.model.Manga

class PickMangaContract : ActivityResultContract<String, Manga?>() {

	override fun createIntent(context: Context, input: String): Intent =
		AppRouter.pickMangaIntent(context, input)

	override fun parseResult(resultCode: Int, intent: Intent?): Manga? {
		if (resultCode != Activity.RESULT_OK || intent == null) return null
		return intent.getParcelableExtraCompat<ParcelableManga>(AppRouter.KEY_MANGA)?.manga
	}
}
