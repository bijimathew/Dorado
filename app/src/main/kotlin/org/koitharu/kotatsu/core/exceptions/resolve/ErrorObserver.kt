package org.koitharu.kotatsu.core.exceptions.resolve

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.SourceSettings
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.core.util.ext.viewLifecycleScope
import kotlin.coroutines.coroutineContext

abstract class ErrorObserver(
	protected val host: View,
	protected val fragment: Fragment?,
	private val resolver: ExceptionResolver?,
	private val onResolved: Consumer<Boolean>?,
) : FlowCollector<Throwable> {

	protected open val activity = host.context.findActivity()

	private val lifecycleScope: LifecycleCoroutineScope
		get() = checkNotNull(fragment?.viewLifecycleScope ?: (activity as? LifecycleOwner)?.lifecycle?.coroutineScope)

	protected val fragmentManager: FragmentManager?
		get() = fragment?.childFragmentManager ?: (activity as? AppCompatActivity)?.supportFragmentManager

	protected fun canResolve(error: Throwable): Boolean {
		return resolver != null && ExceptionResolver.canResolve(error)
	}

	/**
	 * For CloudFlare captcha errors, awaits the silent resolve flow before falling through to the
	 * caller's error UI. Returns `true` only when auto-resolve actually succeeded (caller should skip
	 * its error UI and call [onResolved] is invoked here); returns `false` when the error isn't CF,
	 * the source has auto-solve disabled, or the auto-resolve attempt failed (caller shows the
	 * standard error UI with a manual "Solve" button).
	 */
	protected suspend fun tryAutoResolve(error: Throwable): Boolean {
		if (error !is CloudFlareProtectedException || resolver == null || !canResolve(error)) return false
		val ctx = host.context
		if (ctx != null && SourceSettings(ctx, error.source).isCaptchaAutoResolveDisabled) return false
		val resolved = resolver.resolve(error, tryAutoResolve = true)
		if (resolved && coroutineContext.isActive) {
			onResolved?.accept(true)
		}
		return resolved
	}

	protected fun router() = fragment?.router ?: (activity as? FragmentActivity)?.router

	private fun isAlive(): Boolean {
		return when {
			fragment != null -> fragment.view != null
			activity != null -> activity?.isDestroyed == false
			else -> true
		}
	}

	protected fun resolve(error: Throwable) {
		if (isAlive()) {
			lifecycleScope.launch {
				val isResolved = resolver?.resolve(error) == true
				if (isActive) {
					onResolved?.accept(isResolved)
				}
			}
		}
	}
}
