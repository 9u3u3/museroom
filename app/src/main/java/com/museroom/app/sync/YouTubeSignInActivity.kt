package com.museroom.app.sync

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Signing in to YouTube Music, once.
 *
 * Everything else about a listening room is hidden, but this cannot be: it is
 * somebody's Google account, and it belongs on screen where they can see the
 * address bar's worth of context and decide. Signing in is optional. Without
 * it a room still plays; with it, a subscription applies and the ads that
 * would otherwise interrupt only the joiner stop.
 *
 * The cookies land in the process-wide store, which is the same one the room
 * player reads, so there is nothing to hand over afterwards.
 */
class YouTubeSignInActivity : Activity() {

    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = WebView(this)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Google will not sign anybody in to a browser that announces
            // itself as embedded, and "; wv" is that announcement.
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = WebViewClient()
        view.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=https://music.youtube.com/")

        val root = FrameLayout(this)
        root.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
        web = view
    }

    override fun onBackPressed() {
        val view = web
        if (view != null && view.canGoBack()) view.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        // Written now rather than at some point later, because the next thing
        // the person does is go back and press play.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}
