package org.koitharu.kotatsu.settings.work

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkScheduleManager @Inject constructor() : SharedPreferences.OnSharedPreferenceChangeListener {

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		// No-op
	}

	fun init() {
		// No-op
	}
}
