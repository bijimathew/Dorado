package org.koitharu.kotatsu.core.exceptions.resolve

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.browser.cloudflare.CloudFlareActivity
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.model.UnknownMangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.util.ForegroundActivityHolder
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-threads CloudFlare auto-resolve attempts across the whole app so multiple screens
 * (catalog, details, reader, …) all hitting CAPTCHA for the same source at the same time don't
 * stomp on each other's WebView state. The coordinator OWNS the activity lifecycle —
 * [CloudFlareActivity] reports its result back via [notifyResolveResult], so the result is
 * delivered even if the screen that originally requested the resolve has since been destroyed.
 *
 * - **Same source already in-flight** → subsequent callers don't launch a new resolve, they await
 *   the existing one's result. The first session stays alive and duplicates piggy-back on it.
 * - **Different sources** → queue on the global mutex so only one resolve runs at any moment.
 */
@Singleton
class CaptchaAutoResolveCoordinator @Inject constructor(
	@ApplicationContext private val context: Context,
	private val foregroundActivityHolder: ForegroundActivityHolder,
) {

	private val mutex = Mutex()
	private val inFlight = ConcurrentHashMap<MangaSource, CompletableDeferred<Boolean>>()
	private val pendingActivityResult = ConcurrentHashMap<MangaSource, CompletableDeferred<Boolean>>()
	// Wall-clock timestamps of the most recent successful resolve per source. Used to break tight
	// loops where the WebView passes CloudFlare (so we report success) but the parser's next OkHttp
	// request immediately hits CF again (different fingerprint) → new captcha event → new
	// auto-resolve → toast retriggers every second.
	private val recentSuccessAt = ConcurrentHashMap<MangaSource, Long>()

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	/** Called by [CloudFlareActivity] when a resolve session it was running finishes. */
	fun notifyResolveResult(source: MangaSource, success: Boolean) {
		pendingActivityResult.remove(source)?.complete(success)
	}

	suspend fun resolve(source: MangaSource, exception: CloudFlareProtectedException): Boolean {
		inFlight[source]?.let { return it.await() }
		val lastSuccess = recentSuccessAt[source]
		if (lastSuccess != null && System.currentTimeMillis() - lastSuccess < RECENT_SUCCESS_COOLDOWN_MS) {
			return false
		}
		val deferred: CompletableDeferred<Boolean> = mutex.withLock {
			inFlight[source]?.let { return@withLock it }
			val recheck = recentSuccessAt[source]
			if (recheck != null && System.currentTimeMillis() - recheck < RECENT_SUCCESS_COOLDOWN_MS) {
				return@withLock CompletableDeferred(false)
			}
			val fresh = CompletableDeferred<Boolean>()
			inFlight[source] = fresh
			// Toast only on the slow path: piggy-back awaiters via the fast path get no toast, so
			// rapid captcha events stop stacking new toasts on top of the loading state.
			showSolvingToast()
			scope.launch { runOrchestration(source, exception, fresh) }
			fresh
		}
		return deferred.await()
	}

	private suspend fun runOrchestration(
		source: MangaSource,
		exception: CloudFlareProtectedException,
		deferred: CompletableDeferred<Boolean>,
	) {
		try {
			val hiddenPassed = launchAndAwait(source, exception, hidden = true)
			val finalResult = if (hiddenPassed) {
				true
			} else {
				launchAndAwait(source, exception, hidden = false)
			}
			if (finalResult) {
				recentSuccessAt[source] = System.currentTimeMillis()
			}
			deferred.complete(finalResult)
		} catch (e: Throwable) {
			e.printStackTraceDebug()
			deferred.complete(false)
		} finally {
			inFlight.remove(source)
			pendingActivityResult.remove(source)
		}
	}

	private fun showSolvingToast() {
		Handler(Looper.getMainLooper()).post {
			Toast.makeText(context, R.string.captcha_solving, Toast.LENGTH_LONG).show()
		}
	}

	private suspend fun launchAndAwait(
		source: MangaSource,
		exception: CloudFlareProtectedException,
		hidden: Boolean,
	): Boolean {
		if (source == UnknownMangaSource) return false
		val resultDeferred = CompletableDeferred<Boolean>()
		pendingActivityResult[source] = resultDeferred
		val intent = AppRouter.cloudFlareResolveIntent(context, exception, hidden = hidden).apply {
			putExtra(CloudFlareActivity.EXTRA_AUTO_RESOLVE, true)
		}
		val launcher = foregroundActivityHolder.current
		if (launcher != null) {
			launcher.startActivity(intent)
		} else {
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			context.startActivity(intent)
		}
		return resultDeferred.await()
	}

	private companion object {
		// How long to refuse a fresh auto-resolve for the same source after a successful one. Long
		// enough to break the "WebView passes, parser still fails, captcha event re-fires" loop;
		// short enough that a legitimate retry minutes later goes through normally.
		const val RECENT_SUCCESS_COOLDOWN_MS = 30_000L
	}
}
