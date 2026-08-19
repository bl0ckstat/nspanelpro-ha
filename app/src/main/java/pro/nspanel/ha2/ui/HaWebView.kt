package pro.nspanel.ha2.ui

import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import pro.nspanel.ha2.data.AppSettings

private const val WebViewBackground = "#0D1117"

@Composable
fun HaWebView(
    appSettings: AppSettings,
    modifier: Modifier = Modifier,
    onUserInteraction: () -> Unit = {},
    reloadTrigger: Int = 0,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(reloadTrigger) {
        if (reloadTrigger > 0) webView?.reload()
    }

    LaunchedEffect(appSettings.homeAssistantUrl) {
        val url = appSettings.homeAssistantUrl.trim()
        val wv = webView ?: return@LaunchedEffect
        if (url.isEmpty()) {
            wv.loadUrl("about:blank")
            return@LaunchedEffect
        }
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url
        else "http://$url"
        wv.loadUrl(normalized)
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.parseColor(WebViewBackground))
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.mediaPlaybackRequiresUserGesture = false
                applyWebViewDarkMode(settings)
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                // Notify idle monitor on every touch without consuming the event.
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onUserInteraction()
                    false
                }
                webView = this
            }
        },
        update = { wv ->
            wv.setBackgroundColor(Color.parseColor(WebViewBackground))
            applyWebViewDarkMode(wv.settings)
            wv.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onUserInteraction()
                false
            }
            webView = wv
        },
        modifier = modifier,
    )
}

/**
 * Tell Home Assistant the panel is dark, and let it theme itself.
 *
 * "Algorithmic darkening" names two different behaviours, and the distinction
 * is what matters here. On a page that declares `color-scheme` — Home
 * Assistant does — the WebView inverts nothing; it only makes
 * `prefers-color-scheme` report dark so the page can choose its own dark
 * theme. Machine-inversion is the fallback for pages that make no such
 * declaration.
 *
 * So this has to stay allowed. It is the only thing telling Home Assistant
 * that the panel wants dark, and switching it off is what left these panels
 * rendering the light theme with no other setting changed.
 *
 * The legacy path is AUTO rather than ON: FORCE_DARK_ON pre-dates the
 * web-theme check on older WebViews and will happily invert a page that was
 * already dark.
 */
@Suppress("DEPRECATION")
private fun applyWebViewDarkMode(settings: WebSettings) {
    when {
        WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) ->
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK) ->
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_AUTO)
    }
}
