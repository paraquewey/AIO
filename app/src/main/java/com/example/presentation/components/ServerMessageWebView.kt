package com.example.presentation.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ServerMessageWebView(
    htmlContent: String,
    modifier: Modifier = Modifier
) {
    val textArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val defaultTextHex = remember(textArgb) {
        String.format("#%06X", 0xFFFFFF and textArgb)
    }
    val wrappedHtml = remember(htmlContent, defaultTextHex) {
        wrapServerMessageHtml(htmlContent, defaultTextHex)
    }
    var contentHeightDp by remember(wrappedHtml) { mutableStateOf(64.dp) }
    var lastLoadedHtml by remember { mutableStateOf<String?>(null) }

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(contentHeightDp),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setBackgroundColor(AndroidColor.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER

                settings.apply {
                    javaScriptEnabled = true
                    blockNetworkLoads = true
                    allowFileAccess = false
                    allowContentAccess = false
                    loadsImagesAutomatically = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    loadWithOverviewMode = true
                    useWideViewPort = false
                    defaultTextEncodingName = "UTF-8"
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = true

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse {
                        return WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val wv = view ?: return
                        val measureHeight = Runnable {
                            wv.evaluateJavascript(
                                "(function(){var b=document.body,d=document.documentElement;" +
                                    "return Math.max(b?b.scrollHeight:0,d?d.scrollHeight:0);})()"
                            ) { raw ->
                                val pxOrCss = raw?.trim()?.trim('"')?.toFloatOrNull()
                                if (pxOrCss != null && pxOrCss > 0f) {
                                    contentHeightDp = (pxOrCss + 8f).coerceIn(32f, 420f).dp
                                } else if (wv.contentHeight > 0) {
                                    contentHeightDp = (wv.contentHeight.toFloat() + 8f).coerceIn(32f, 420f).dp
                                }
                            }
                        }
                        wv.post(measureHeight)
                        wv.postDelayed(measureHeight, 120L)
                    }
                }
            }
        },
        update = { webView ->
            webView.setBackgroundColor(AndroidColor.TRANSPARENT)
            if (lastLoadedHtml != wrappedHtml) {
                lastLoadedHtml = wrappedHtml
                webView.loadDataWithBaseURL(null, wrappedHtml, "text/html", "UTF-8", null)
            }
        },
        onRelease = { webView ->
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        }
    )
}

internal fun wrapServerMessageHtml(rawHtml: String, defaultTextHex: String = "#EBDBB2"): String {
    val trimmed = rawHtml.trim()
    if (trimmed.contains("<html", ignoreCase = true)) {
        val transparentInject = "<style>html,body{background:transparent!important;}</style>"
        return if (trimmed.contains("</head>", ignoreCase = true)) {
            trimmed.replace("</head>", "$transparentInject</head>", ignoreCase = true)
        } else {
            transparentInject + trimmed
        }
    }
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <style>
          * { box-sizing: border-box; }
          html, body {
            margin: 0;
            padding: 4px 2px;
            background: transparent !important;
            color: $defaultTextHex;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            font-size: 13px;
            line-height: 1.45;
            overflow-x: hidden;
            word-wrap: break-word;
          }
        </style>
        </head>
        <body>$trimmed</body>
        </html>
    """.trimIndent()
}

internal fun formatServerBannerAsHtml(banner: String): String {
    val trimmed = banner.trim()
    val looksLikeHtml = trimmed.contains('<') && trimmed.contains('>')
    if (looksLikeHtml) return trimmed
    val escaped = trimmed
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br/>")
    return """<div style="font-family:monospace;font-size:12px;opacity:0.95;">$escaped</div>"""
}
