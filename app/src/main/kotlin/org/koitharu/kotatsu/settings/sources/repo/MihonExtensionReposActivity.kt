package org.koitharu.kotatsu.settings.sources.repo

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.core.graphics.Insets
import androidx.core.view.MenuProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import org.koitharu.kotatsu.core.ui.dialog.setEditText
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.FadingAppbarMediator
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.ActivitySourcesCatalogBinding
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.settings.utils.validation.UrlValidator

@AndroidEntryPoint
class MihonExtensionReposActivity : BaseActivity<ActivitySourcesCatalogBinding>(),
	OnListItemClickListener<MihonExtensionRepoListItem.Repo>,
	AppBarOwner {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	private val viewModel by viewModels<MihonExtensionReposViewModel>()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivitySourcesCatalogBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		val adapter = MihonExtensionReposAdapter(this)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			this.adapter = adapter
		}
		viewBinding.scrollViewChips.isVisible = false
		FadingAppbarMediator(viewBinding.appbar, viewBinding.toolbar).bind()
		viewModel.content.observe(this, adapter)
		viewModel.onMessage.observeEvent(this, ::showMessage)
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.recyclerView, null))
		addMenuProvider(object : MenuProvider {
			override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
				menuInflater.inflate(R.menu.opt_mihon_extension_repos, menu)
			}

			override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
				return when (menuItem.itemId) {
					R.id.action_add -> {
						showAddRepoDialog()
						true
					}

					else -> false
				}
			}
		})
		if (savedInstanceState == null) {
			val handledIncomingIntent = handleIncomingIntent(intent)
			if (!handledIncomingIntent && intent.getBooleanExtra(EXTRA_SHOW_ADD_REPO_DIALOG, false)) {
				showAddRepoDialog()
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		if (!handleIncomingIntent(intent) && intent.getBooleanExtra(EXTRA_SHOW_ADD_REPO_DIALOG, false)) {
			showAddRepoDialog()
		}
	}

	override fun onItemClick(item: MihonExtensionRepoListItem.Repo, view: View) {
		router.openMihonRepoExtensions(item.repo.baseUrl, item.repo.name)
	}

	override fun onItemLongClick(item: MihonExtensionRepoListItem.Repo, view: View): Boolean {
		buildAlertDialog(this, isCentered = true) {
			setTitle(R.string.remove)
			setMessage(getString(R.string.remove_extension_repo_confirm, item.repo.name))
			setNegativeButton(android.R.string.cancel, null)
			setPositiveButton(R.string.remove) { _, _ ->
				viewModel.removeRepo(item.repo)
			}
		}.show()
		return true
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.recyclerView.updatePadding(
			left = bars.left,
			right = bars.right,
			bottom = bars.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	private fun showAddRepoDialog(initialValue: String? = null) {
		var dialog: androidx.appcompat.app.AlertDialog? = null
		var input: android.widget.EditText? = null
		val alertDialog = buildAlertDialog(this) {
			setTitle(R.string.add_extension_repo)
			input = setEditText(
				InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
				true,
			)
			input?.hint = getString(R.string.extension_repo_url)
			input?.setText(initialValue)
			input?.let { UrlValidator().attachToEditText(it) }
			setNegativeButton(android.R.string.cancel, null)
			setNeutralButton(R.string.use_suggested_repo, null)
			setPositiveButton(android.R.string.ok, null)
		}
		dialog = alertDialog
		alertDialog.setOnShowListener {
			dialog?.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener {
				input?.setText(MihonExtensionReposViewModel.SUGGESTED_REPO_URL)
				input?.setSelection(input?.text?.length ?: 0)
				input?.error = null
			}
			dialog?.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
				val editText = input ?: return@setOnClickListener
				val value = editText.text?.toString().orEmpty().trim()
				if (value.isEmpty()) {
					editText.error = getString(R.string.invalid_server_address_message)
					return@setOnClickListener
				}
				viewModel.addRepo(value)
				dialog?.dismiss()
			}
		}
		alertDialog.show()
	}

	private fun handleIncomingIntent(intent: Intent?): Boolean {
		val incomingValue = when (intent?.action) {
			Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
			Intent.ACTION_VIEW -> intent.dataString
			else -> null
		}?.takeIf { it.isNotBlank() } ?: return false
		viewModel.addRepo(incomingValue)
		return true
	}

	private fun showMessage(message: String) {
		Snackbar.make(viewBinding.recyclerView, message, Snackbar.LENGTH_SHORT).show()
	}

	companion object {
		const val EXTRA_SHOW_ADD_REPO_DIALOG = "show_add_repo_dialog"
	}
}
