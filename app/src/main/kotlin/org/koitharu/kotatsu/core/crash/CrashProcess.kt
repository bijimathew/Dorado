package org.koitharu.kotatsu.core.crash

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process

internal const val CRASH_PROCESS_SUFFIX = ":crash"

internal fun Context.isCrashProcess(): Boolean {
	return currentProcessName()?.endsWith(CRASH_PROCESS_SUFFIX) == true
}

internal fun Context.currentProcessName(): String? {
	if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
		return Application.getProcessName()
	}
	val pid = Process.myPid()
	val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
	return activityManager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
}
