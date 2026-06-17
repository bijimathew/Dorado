package org.koitharu.kotatsu.settings.work

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker
import org.koitharu.kotatsu.tracker.domain.TrackerUnstuckMigrationUseCase
import org.koitharu.kotatsu.tracker.work.TrackWorker
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class WorkScheduleManager @Inject constructor(
	private val settings: AppSettings,
	private val suggestionScheduler: SuggestionsWorker.Scheduler,
	private val trackerScheduler: TrackWorker.Scheduler,
	private val trackerUnstuckMigrationProvider: Provider<TrackerUnstuckMigrationUseCase>,
) : SharedPreferences.OnSharedPreferenceChangeListener {

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		// No-op
	}

	fun init() {
		processLifecycleScope.launch(Dispatchers.Default) {
			trackerScheduler.unschedule()
			suggestionScheduler.unschedule()
		}
	}
}
