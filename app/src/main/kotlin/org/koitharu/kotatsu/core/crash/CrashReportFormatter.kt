package org.koitharu.kotatsu.core.crash

import android.content.Context
import android.net.Uri
import android.os.Build
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object CrashReportFormatter {

	private const val ISSUE_TEMPLATE = "report_bug.yml"
	private const val MAX_TITLE_LENGTH = 120
	private const val MAX_FIELD_LENGTH = 3_000
	private const val MAX_REPORT_LENGTH = 64 * 1024
	private const val MAX_ISSUE_BODY_LENGTH = 6_000

	fun build(context: Context, throwable: Throwable, threadName: String?): CrashReportData {
		val summary = buildSummary(throwable)
		val title = "Crash: ${summary}".limit(MAX_TITLE_LENGTH)
		val stackTrace = throwable.stackTraceToString().limit(MAX_REPORT_LENGTH / 2)
		val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
		val androidVersion = buildAndroidVersion()
		val device = buildDevice()
		val steps = buildReproduceSteps(stackTrace)
		val body = buildBody(
			summary = summary,
			steps = steps,
			appVersion = appVersion,
			androidVersion = androidVersion,
			device = device,
			threadName = threadName,
			processName = context.currentProcessName(),
			stackTrace = stackTrace,
		).limit(MAX_REPORT_LENGTH)
		val issueUrl = buildIssueUrl(
			context = context,
			title = title,
			summary = summary,
			steps = steps,
			appVersion = appVersion,
			androidVersion = androidVersion,
			device = device,
			body = body,
		)
		return CrashReportData(
			title = title,
			body = body,
			issueUrl = issueUrl,
		)
	}

	private fun buildIssueUrl(
		context: Context,
		title: String,
		summary: String,
		steps: String,
		appVersion: String,
		androidVersion: String,
		device: String,
		body: String,
	): String {
		val baseUrl = context.getString(R.string.url_error_report)
			.trim()
			.removeSuffix("/choose")
			.ifEmpty { "https://github.com/glitch-228/Kaisoku/issues/new" }
		return Uri.parse(baseUrl).buildUpon()
			.appendQueryParameter("template", ISSUE_TEMPLATE)
			.appendQueryParameter("title", title)
			.appendQueryParameter("summary", summary.limit(MAX_FIELD_LENGTH))
			.appendQueryParameter("reproduce-steps", steps.limit(MAX_FIELD_LENGTH))
			.appendQueryParameter("kaisoku-version", appVersion)
			.appendQueryParameter("android-version", androidVersion)
			.appendQueryParameter("device", device)
			.appendQueryParameter("body", body.limit(MAX_ISSUE_BODY_LENGTH))
			.build()
			.toString()
	}

	private fun buildSummary(throwable: Throwable): String {
		val type = throwable.javaClass.simpleName.ifBlank { throwable.javaClass.name }
		val message = throwable.message?.oneLine()
		return if (message.isNullOrBlank()) {
			type
		} else {
			"$type: $message"
		}
	}

	private fun buildReproduceSteps(stackTrace: String): String {
		return """
			The app crashed unexpectedly.

			Please describe what you were doing before the crash.

			Stack trace:

			```text
			$stackTrace
			```
		""".trimIndent()
	}

	private fun buildBody(
		summary: String,
		steps: String,
		appVersion: String,
		androidVersion: String,
		device: String,
		threadName: String?,
		processName: String?,
		stackTrace: String,
	): String {
		return """
			### Brief summary
			$summary

			### Steps to reproduce
			$steps

			### Kaisoku version
			$appVersion

			### Android version
			$androidVersion

			### Device
			$device

			### Runtime
			Time: ${formatNow()}
			Process: ${processName ?: "unknown"}
			Thread: ${threadName ?: "unknown"}
			Build type: ${BuildConfig.BUILD_TYPE}

			### Stack trace
			```text
			$stackTrace
			```
		""".trimIndent()
	}

	private fun buildAndroidVersion(): String {
		return "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}, ${Build.VERSION.CODENAME})"
	}

	private fun buildDevice(): String {
		return listOf(
			Build.MANUFACTURER,
			Build.BRAND,
			Build.MODEL,
			"device=${Build.DEVICE}",
			"product=${Build.PRODUCT}",
		).joinToString(separator = " ") { it.ifBlank { "unknown" } }
	}

	private fun formatNow(): String {
		return SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
	}

	private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

	private fun String.limit(maxLength: Int): String {
		return if (length <= maxLength) this else take(maxLength - 16).trimEnd() + "\n...[truncated]"
	}
}

internal data class CrashReportData(
	val title: String,
	val body: String,
	val issueUrl: String,
)
