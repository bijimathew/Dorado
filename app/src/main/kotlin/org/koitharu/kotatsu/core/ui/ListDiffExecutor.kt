package org.koitharu.kotatsu.core.ui

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

internal object ListDiffExecutor {

	val instance: Executor = Executors.newFixedThreadPool(2, DiffThreadFactory())

	private class DiffThreadFactory : ThreadFactory {
		private val counter = AtomicInteger(1)

		override fun newThread(runnable: Runnable): Thread {
			return Thread(runnable, "ListDiff-${counter.getAndIncrement()}").apply {
				priority = Thread.NORM_PRIORITY - 1
				isDaemon = true
			}
		}
	}
}
