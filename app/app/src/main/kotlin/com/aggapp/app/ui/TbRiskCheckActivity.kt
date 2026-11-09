package com.aggapp.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.aggapp.platform.api.AppLog

/**
 * 淘宝 havana iv 风控验证原生 Activity（2026-11-09）。
 * 背景：短信登录收尾命中 normal_validate_tpl（window.ivModel）需人工过验证。
 * 此前用 Compose AndroidView WebView 弹窗承载，本设备上该容器 WebView 渲染进程
 * 不出帧（见 CaptchaActivity 头注释），NC 风控 SDK 采不到渲染指纹 → 永远停在
 * 「验证环境」；且该弹窗硬编码 Pixel6/Chrome120 UA 与协议端 LOGIN_UA
 * （Nexus5/Chrome128）不一致，htoken 会话环境指纹矛盾 → 验证循环。
 * 改用与 CaptchaActivity 一致的纯 View 体系 Activity：UA 与协议端统一、
 * 开启三方 Cookie（阿里风控 iframe 跨域需要）、导航/控制台/渲染进程日志全量入 AppLog。
 * 结果经静态回调 [onResult] 回传：非 null=www.taobao.com 下已出现 cookie2/unb
 * （登录态 Cookie 头），null=用户关闭/超时——与 JD 滑块回调约定一致。
 */
class TbRiskCheckActivity : Activity() {

    private var webViewRef: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var done = false
    private var resultHeader: String? = null
    private var pollDeadline = 0L

    companion object {
        const val EXTRA_URL = "url"
        /** 每条形如 "https://www.taobao.com/\ncookie 串"，由 tbWebviewCookies 序列化而来 */
        const val EXTRA_COOKIES = "cookies"
        /** 协议端登录 UA（TaobaoAdapter.loginWebviewUa 传入），必须与会话建链时一致 */
        const val EXTRA_UA = "ua"
        /** 登录态 Cookie 出现前的最长等待；超时按取消回传，避免对话框永久空转 */
        private const val POLL_TIMEOUT_MS = 4 * 60_000L
        private const val POLL_INTERVAL_MS = 1_200L
        private const val TAOBAO_HOME = "https://www.taobao.com/"
        var onResult: ((String?) -> Unit)? = null
    }

    private fun hasLoginCookie(): String? {
        val ck = CookieManager.getInstance().getCookie(TAOBAO_HOME) ?: return null
        return ck.takeIf { it.contains("cookie2=") && it.contains("unb=") }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (done || isFinishing || isDestroyed) return
            hasLoginCookie()?.let {
                AppLog.d("TB", "risk check: login cookie detected")
                done = true
                resultHeader = it
                finish()
                return
            }
            if (System.currentTimeMillis() > pollDeadline) {
                AppLog.w("TB", "risk check: poll timeout")
                finish()
                return
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val ua = intent.getStringExtra(EXTRA_UA) ?: return finish()
        val dm = resources.displayMetrics
        val dp = dm.density

        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#88000000"))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 16 * dp
            }
        }
        val header = TextView(this).apply {
            text = "安全验证"
            setTextColor(Color.parseColor("#1C1B1F"))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (4 * dp).toInt())
        }
        val subtitle = TextView(this).apply {
            text = "请在下方完成淘宝安全验证，通过后自动完成登录"
            setTextColor(Color.parseColor("#5A5A5F"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((24 * dp).toInt(), 0, (24 * dp).toInt(), (8 * dp).toInt())
        }
        val closeBtn = TextView(this).apply {
            text = "关闭"
            setTextColor(Color.parseColor("#5A5A5F"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * dp).toInt())
            setOnClickListener { finish() }
        }

        val preset = intent.getStringArrayListExtra(EXTRA_COOKIES).orEmpty()
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 与协议登录端同一份 UA：htoken 绑定的环境指纹才自洽，否则风控反复判环境异常
            settings.userAgentString = ua
            // 风控页含跨域 iframe（alicdn/aliyun 域），默认拒三方 Cookie 会让 SDK 拿不到会话
            cm.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, u: String, favicon: android.graphics.Bitmap?) {
                    AppLog.d("TB", "risk check page start: ${u.take(120)}")
                }

                override fun onPageFinished(view: WebView, u: String) {
                    AppLog.d("TB", "risk check page finish: ${u.take(120)}")
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    val desc = "risk check error ${error.errorCode} ${error.description} url=${request.url.toString().take(100)}"
                    if (request.isForMainFrame) AppLog.e("TB", desc) else AppLog.w("TB", desc)
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    AppLog.w("TB", "risk check http ${response.statusCode} url=${request.url.toString().take(100)}")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                    val level = msg.messageLevel()
                    val text = "risk check console[$level] ${msg.message()?.take(200)}"
                    if (level == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                        AppLog.e("TB", text)
                    } else {
                        AppLog.d("TB", text)
                    }
                    return true
                }
            }
        }
        // 预置协议端会话 Cookie（Domain=.taobao.com 已由上层拼接），须先种 Cookie 再 loadUrl
        for (entry in preset) {
            val i = entry.indexOf('\n')
            if (i <= 0) continue
            cm.setCookie(entry.substring(0, i), entry.substring(i + 1))
        }
        cm.flush()
        webView.loadUrl(url)
        AppLog.d("TB", "risk check open url=${url.take(100)} presetCookies=${preset.size}")

        card.addView(header)
        card.addView(subtitle)
        card.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (dm.heightPixels * 0.6f).toInt(),
            ).apply { setMargins((16 * dp).toInt(), 0, (16 * dp).toInt(), 0) },
        )
        card.addView(closeBtn)
        root.addView(
            card,
            android.widget.FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                setMargins((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            },
        )
        webViewRef = webView
        setContentView(root)

        pollDeadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        handler.postDelayed(poll, 1_500)
    }

    override fun finish() {
        // 成功时 resultHeader 已在 poll 里写好；统一在此一次性回传，避免 finish 双发
        if (!done && resultHeader == null) {
            done = true
        }
        onResult?.let { cb -> cb(resultHeader); onResult = null }
        super.finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webViewRef?.let { wv ->
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.loadUrl("about:blank")
            wv.destroy()
        }
        webViewRef = null
        onResult = null
        super.onDestroy()
    }
}
