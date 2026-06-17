package org.koitharu.kotatsu.settings

import android.accounts.AccountManager
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import org.koitharu.kotatsu.settings.utils.SplitSwitchPreference
import org.koitharu.kotatsu.sync.domain.SyncController
import javax.inject.Inject

@AndroidEntryPoint
class ServicesSettingsFragment : BasePreferenceFragment(R.string.services),
	SharedPreferences.OnSharedPreferenceChangeListener {

	@Inject
	lateinit var syncController: SyncController



	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_services)
		findPreference<SplitSwitchPreference>(AppSettings.KEY_STATS_ENABLED)?.let {
			it.onContainerClickListener = Preference.OnPreferenceClickListener {
				router.openStatistic()
				true
			}
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		bindSuggestionsSummary()
		bindStatsSummary()
		settings.subscribe(this)
	}

	override fun onDestroyView() {
		settings.unsubscribe(this)
		super.onDestroyView()
	}


	override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_SUGGESTIONS -> bindSuggestionsSummary()
			AppSettings.KEY_STATS_ENABLED -> bindStatsSummary()
		}
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return super.onPreferenceTreeClick(preference)
	}

	private fun bindSuggestionsSummary() {
		findPreference<Preference>(AppSettings.KEY_SUGGESTIONS)?.setSummary(
			if (settings.isSuggestionsEnabled) R.string.enabled else R.string.disabled,
		)
	}

	private fun bindStatsSummary() {
		findPreference<Preference>(AppSettings.KEY_STATS_ENABLED)?.setSummary(
			if (settings.isStatsEnabled) R.string.enabled else R.string.disabled,
		)
	}

}
