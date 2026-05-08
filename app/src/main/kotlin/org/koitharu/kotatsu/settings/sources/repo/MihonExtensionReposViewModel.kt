package org.koitharu.kotatsu.settings.sources.repo

import android.content.Context
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.mihon.repo.MihonExtensionRepo
import org.koitharu.kotatsu.core.parser.mihon.repo.MihonExtensionRepoRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.list.ui.model.ListModel
import javax.inject.Inject

@HiltViewModel
class MihonExtensionReposViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val repoRepository: MihonExtensionRepoRepository,
) : BaseViewModel() {

	val onMessage = MutableEventFlow<String>()

	val content: StateFlow<List<ListModel>> = repoRepository.observeRepos()
		.map { repos ->
			repos.sortedBy { it.name.lowercase() }
				.mapTo(ArrayList<ListModel>(repos.size.coerceAtLeast(1))) { repo ->
					MihonExtensionRepoListItem.Repo(repo)
				}
				.ifEmpty {
					listOf(
						MihonExtensionRepoListItem.Hint(
							icon = R.drawable.ic_sync,
							title = R.string.no_extension_repositories,
							text = R.string.no_extension_repositories_text,
						),
					)
				}
		}.stateIn(
			viewModelScope + Dispatchers.Default,
			SharingStarted.WhileSubscribed(CONTENT_STOP_TIMEOUT_MS),
			emptyList(),
		)

	fun addRepo(rawUrl: String) {
		launchLoadingJob(Dispatchers.IO) {
			when (repoRepository.addRepo(normalizeIncomingUrl(rawUrl))) {
				is MihonExtensionRepoRepository.AddRepoResult.Success -> {
					onMessage.call(context.getString(R.string.extension_repo_added))
				}

				MihonExtensionRepoRepository.AddRepoResult.InvalidRepo,
				MihonExtensionRepoRepository.AddRepoResult.InvalidUrl -> {
					onMessage.call(context.getString(R.string.invalid_extension_repo))
				}

				MihonExtensionRepoRepository.AddRepoResult.RepoAlreadyExists -> {
					onMessage.call(context.getString(R.string.duplicate_extension_repo))
				}

				is MihonExtensionRepoRepository.AddRepoResult.DuplicateFingerprint -> {
					onMessage.call(context.getString(R.string.duplicate_extension_repo_signature))
				}
			}
		}
	}

	fun removeRepo(repo: MihonExtensionRepo) {
		if (repoRepository.removeRepo(repo.baseUrl)) {
			onMessage.call(context.getString(R.string.extension_repo_removed))
		}
	}

	private fun normalizeIncomingUrl(value: String): String {
		val candidate = HTTP_URL_REGEX.find(value)?.value ?: value.trim()
		val normalized = candidate.trim().trimEnd('/', '.', ',', ';', ':', ')', ']')
		return when (normalized.substringBefore('?').removeSuffix("/")) {
			KEIYOUSHI_ADD_REPO_URL -> SUGGESTED_REPO_URL
			else -> normalized
		}
	}

	companion object {
		const val SUGGESTED_REPO_URL =
			"https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
		private const val CONTENT_STOP_TIMEOUT_MS = 5000L
		private const val KEIYOUSHI_ADD_REPO_URL = "https://keiyoushi.github.io/add-repo"
		private val HTTP_URL_REGEX = """https?://\S+""".toRegex()
	}
}
