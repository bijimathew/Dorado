package org.koitharu.kotatsu.core.crash

import android.app.Application
import android.content.Intent
import android.os.Process
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

internal class GlobalCrashHandler private constructor(
	private val application: Application,
) : Thread.UncaughtExceptionHandler {

	override fun uncaughtException(thread: Thread, throwable: Throwable) {
		if (isHandling.compareAndSet(false, true)) {
			val report = runCatching {
				CrashReportFormatter.build(application, throwable, thread.name)
			}.onFailure {
				it.printStackTraceDebug()
			}.getOrNull()
			if (report != null) {
				runCatching {
					CrashService.start(application, report)
				}.onFailure {
					it.printStackTraceDebug()
					startCrashDialog(report)
				}
				runCatching {
					Thread.sleep(CRASH_SERVICE_START_DELAY_MS)
				}
			}
		}
		Process.killProcess(Process.myPid())
		exitProcess(10)
	}

	private fun startCrashDialog(report: CrashReportData) {
		runCatching {
			application.startActivity(
				CrashDialogActivity.newIntent(application, report)
					.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
			)
		}.onFailure {
			it.printStackTraceDebug()
		}
	}

	companion object {

		private const val CRASH_SERVICE_START_DELAY_MS = 350L
		private val isInstalled = AtomicBoolean(false)
		private val isHandling = AtomicBoolean(false)

		fun install(application: Application) {
			if (!isInstalled.compareAndSet(false, true)) {
				return
			}
			Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(application))
		}
	}
}
