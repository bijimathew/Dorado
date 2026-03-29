package eu.kanade.tachiyomi.network

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Util for evaluating JavaScript in sources.
 *
 * @since extensions-lib 1.4
 */
class JavaScriptEngine(private val context: Context) {

    /**
     * Evaluate arbitrary JavaScript code and get the result as a primitive type
     * (e.g., String, Int).
     *
     * @param script JavaScript to execute.
     * @return Result of JavaScript code as a primitive type.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> evaluate(script: String): T {
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.loadDataWithBaseURL("https://localhost/", "<html></html>", "text/html", "utf-8", null)
                webView.post {
                    try {
                        webView.evaluateJavascript(script) { rawResult ->
                            val parsedResult = runCatching { parse(rawResult) }.getOrElse { rawResult }
                            continuation.resume(parsedResult as T)
                            webView.destroy()
                        }
                    } catch (e: Throwable) {
                        continuation.resumeWithException(e)
                        webView.destroy()
                    }
                }
                continuation.invokeOnCancellation {
                    webView.destroy()
                }
            }
        }
    }

    private fun parse(result: String?): Any? {
        if (result == null || result == "null") {
            return null
        }
        return JSONArray("[$result]").get(0)
    }
}
