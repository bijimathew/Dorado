package org.koitharu.kotatsu.settings

import android.os.Bundle
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment

class DebugSettingsFragment : BasePreferenceFragment(R.string.debug) {

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_debug)
	}
}
