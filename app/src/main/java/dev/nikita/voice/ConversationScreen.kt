package dev.nikita.voice

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * A single trusted bundled page owns the UI. Keep its original local storage origin
 * so saved phrases and preferences survive package upgrades and the brand change.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class ConversationScreen(context: Context, bridge: DeviceBridge) : WebView(context) {
    private companion object { const val AUDIO_PART_SIZE = 128 * 1024 }

    init {
        setBackgroundColor(context.getColor(R.color.voice_paper))
        fitsSystemWindows = true
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            @Deprecated("For older Android System WebView")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = true
        }
        addJavascriptInterface(bridge, "NikitaPlatform")
        val page = context.assets.open("index.html").bufferedReader().use { it.readText() }
        val adapter = context.assets.open("device.js").bufferedReader().use { it.readText() }
        val policy = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; " +
            "img-src data:; font-src data:; media-src data: blob:; frame-src 'none'; base-uri 'none'; form-action 'none'"
        val bootstrap = """<meta http-equiv="Content-Security-Policy" content="$policy"><script>$adapter</script>"""
        loadDataWithBaseURL("https://nikita.invalid/", page.replaceFirst("<head>", "<head>$bootstrap"),
            "text/html", "utf-8", null)
    }

    fun send(id: Int, type: String, value: String = "") {
        if (type == "audio" && value.length > AUDIO_PART_SIZE) {
            var start = 0
            while (value.length - start > AUDIO_PART_SIZE) {
                val end = start + AUDIO_PART_SIZE
                sendEvent(id, "audio-part", value.substring(start, end))
                start = end
            }
            sendEvent(id, type, value.substring(start))
        } else {
            sendEvent(id, type, value)
        }
    }

    private fun sendEvent(id: Int, type: String, value: String) {
        val event = JSONObject().put("id", id).put("type", type).put("value", value)
        evaluateJavascript("window.NikitaDevice && window.NikitaDevice.receive($event)", null)
    }
}
