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

/** How far down the screen a drag may start and still mean "open settings". */
private const val TopZoneDp = 72f

/** How far it must travel before it does. */
private const val TriggerDp = 48f

/**
 * Watch for a drag down from the top edge without taking the touch away
 * from the page.
 *
 * This used to be a Compose Box overlaid on the WebView, which meant the top
 * 72dp of the panel was deaf to taps: anything the dashboard drew up there —
 * the climate sheet's close button, most obviously — could be seen but not
 * pressed. The listener returns false throughout, so the page still receives
 * every event and the gesture costs no screen area at all.
 */
private fun WebView.trackTouches(
    onUserInteraction: () -> Unit,
    onSwipeDownFromTop: () -> Unit,
) {
    val density = resources.displayMetrics.density
    val topZone = TopZoneDp * density
    val trigger = TriggerDp * density
    var startY = 0f
    var armed = false
    var fired = false
    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onUserInteraction()
                startY = event.y
                armed = event.y <= topZone
                fired = false
            }

            MotionEvent.ACTION_MOVE ->
                if (armed && !fired && event.y - startY > trigger) {
                    fired = true
                    onSwipeDownFromTop()
                }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> armed = false
        }
        false
    }
}

@Composable
fun HaWebView(
    appSettings: AppSettings,
    modifier: Modifier = Modifier,
    onUserInteraction: () -> Unit = {},
    onSwipeDownFromTop: () -> Unit = {},
    reloadTrigger: Int = 0,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    // One navigation effect, keyed on the configured URL, the refresh
    // counter, AND the WebView instance itself. The last key is the
    // difference between a panel that boots and a panel that boots black:
    // if the stored settings emit before AndroidView's factory has run, the
    // effect fires, finds webView null, returns — and with no further key
    // change, no page ever loads. The Gen2 panel loses that race routinely;
    // keying on the instance re-runs the effect the moment the view exists.
    LaunchedEffect(appSettings.homeAssistantUrl, reloadTrigger, webView) {
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
                trackTouches(onUserInteraction, onSwipeDownFromTop)
                webView = this
            }
        },
        update = { wv ->
            wv.setBackgroundColor(Color.parseColor(WebViewBackground))
            applyWebViewDarkMode(wv.settings)
            wv.trackTouches(onUserInteraction, onSwipeDownFromTop)
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
