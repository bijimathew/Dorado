package org.koitharu.kotatsu.core.network

import android.os.Build
import okhttp3.Interceptor
import okhttp3.Response
import okio.IOException
import org.koitharu.kotatsu.core.exceptions.CloudFlareBlockedException
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

class CloudFlareInterceptor : Interceptor {

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val response = chain.proceed(request)
		return when (detectProtection(response)) {
			CloudFlareHelper.PROTECTION_BLOCKED -> response.closeThrowing(
				CloudFlareBlockedException(
					url = request.url.toString(),
					source = request.tag(MangaSource::class.java),
				),
			)

			CloudFlareHelper.PROTECTION_CAPTCHA -> response.closeThrowing(
				CloudFlareProtectedException(
					url = request.url.toString(),
					source = request.tag(MangaSource::class.java),
					headers = request.headers,
				),
			)

			else -> response
		}
	}

	private fun detectProtection(response: Response): Int {
		// Android 6 (API 23) ICU charset decoder can throw "Bad position" when
		// CloudFlareHelper reads the response body. Sniff bytes manually instead.
		if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M) {
			return try {
				val body = response.peekBody(BODY_PEEK_BYTES).bytes()
				val text = String(body, Charsets.UTF_8)
				when {
					text.contains("cf-challenge") ||
						text.contains("ray_id") ||
						text.contains("jschl_vc") -> CloudFlareHelper.PROTECTION_CAPTCHA

					text.contains("cf-error-details") -> CloudFlareHelper.PROTECTION_BLOCKED
					else -> CloudFlareHelper.PROTECTION_NOT_DETECTED
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
				CloudFlareHelper.PROTECTION_NOT_DETECTED
			}
		}
		return try {
			CloudFlareHelper.checkResponseForProtection(response)
		} catch (e: IllegalArgumentException) {
			if (e.message?.contains("Bad position") == true) {
				CloudFlareHelper.PROTECTION_NOT_DETECTED
			} else {
				e.printStackTraceDebug()
				CloudFlareHelper.PROTECTION_NOT_DETECTED
			}
		}
	}

	private fun Response.closeThrowing(error: IOException): Nothing {
		try {
			close()
		} catch (e: Exception) {
			error.addSuppressed(e)
		}
		throw error
	}

	private companion object {
		const val BODY_PEEK_BYTES = 512L
	}
}
