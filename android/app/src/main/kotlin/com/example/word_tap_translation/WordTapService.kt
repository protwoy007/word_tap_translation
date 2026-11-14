package com.example.word_tap_translation

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class WordTapService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = ConcurrentHashMap<String, String>()
    private val wordRegex = Regex("^[A-Za-z][A-Za-z'-]*$")
    private val anyWord = Regex("[A-Za-z][A-Za-z'-]*")
    private val latin = Regex("[A-Za-z]{2,}")

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var panel: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var srcView: TextView
    private lateinit var sentBn: TextView
    private lateinit var wordView: TextView
    private lateinit var meaningView: TextView
    private lateinit var wordBn: TextView

    private var tts: TextToSpeech? = null
    private var shown = false
    private var fromSelection = false
    private var sessionToken = 0
    private var wordToken = 0
    private var currentText = ""
    private var currentWord = ""
    private var selStart = -1
    private var selEnd = -1
    private var longPressed = false
    private var pending: Runnable? = null
    private var clickCount = 0
    private var lastClickTime = 0L
    private val lastRect = Rect()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM
        params.y = dp(96)
        params.horizontalMargin = 0.03f
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        buildPanel()
    }

    private fun label(color: String, size: Float): TextView {
        val t = TextView(this)
        t.setTextColor(Color.parseColor(color))
        t.textSize = size
        return t
    }

    private fun buildPanel() {
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F2202124"))
        bg.cornerRadius = dp(16).toFloat()

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = bg
        panel.setPadding(dp(16), dp(12), dp(16), dp(8))

        srcView = label("#FFFFFF", 16f)
        srcView.movementMethod = LinkMovementMethod.getInstance()
        srcView.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) longPressed = false
            false
        }
        srcView.setOnLongClickListener {
            longPressed = true
            translateSentence()
            true
        }
        scroller = ScrollView(this)
        scroller.addView(srcView)

        sentBn = label("#81C995", 18f)
        sentBn.setPadding(0, dp(8), 0, 0)

        wordView = label("#FFFFFF", 20f)
        wordView.typeface = Typeface.DEFAULT_BOLD
        wordView.setPadding(0, dp(10), 0, 0)

        meaningView = label("#BDC1C6", 14f)
        meaningView.setPadding(0, dp(4), 0, 0)

        wordBn = label("#81C995", 20f)
        wordBn.setPadding(0, dp(6), 0, 0)

        val speak = label("#8AB4F8", 14f)
        speak.text = "Speak"
        speak.setPadding(0, dp(10), dp(16), dp(8))
        speak.setOnClickListener { speakNow() }

        val close = label("#8AB4F8", 14f)
        close.text = "Close"
        close.gravity = Gravity.END
        close.setPadding(dp(16), dp(10), 0, dp(8))
        close.setOnClickListener { hide() }

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(speak, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(close)

        panel.addView(
            scroller,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        panel.addView(sentBn)
        panel.addView(wordView)
        panel.addView(meaningView)
        panel.addView(wordBn)
        panel.addView(row)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (pkg == packageName || pkg == "com.android.systemui") return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> onSelection(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClick(event)
        }
    }

    private fun onSelection(event: AccessibilityEvent) {
        val node = event.source
        var full: CharSequence? = node?.text
        var a = node?.textSelectionStart ?: -1
        var b = node?.textSelectionEnd ?: -1
        if (full == null || a < 0 || b < 0) {
            full = event.text?.firstOrNull()
            a = event.fromIndex
            b = event.toIndex
        }
        val s = minOf(a, b)
        val e = maxOf(a, b)

        pending?.let { main.removeCallbacks(it) }

        if (full == null || s < 0 || e <= s || e > full.length) {
            val r = Runnable { if (fromSelection) hide() }
            pending = r
            main.postDelayed(r, 400)
            return
        }

        val picked = full.subSequence(s, e).toString().trim()
        if (picked.isEmpty() || picked.length > 500 || !latin.containsMatchIn(picked)) return

        val r = Runnable { show(picked, true) }
        pending = r
        main.postDelayed(r, 350)
    }

    private fun onClick(event: AccessibilityEvent) {
        val src = event.source ?: return
        val r = Rect()
        src.getBoundsInScreen(r)
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < 500 && r == lastRect) clickCount++ else clickCount = 1
        lastClickTime = now
        lastRect.set(r)
        if (clickCount < 3) return
        clickCount = 0

        val sb = StringBuilder()
        collect(src, 0, sb)
        if (sb.isEmpty()) collect(src.parent, 0, sb)
        val text = sb.toString().trim()
        if (text.isEmpty() || !latin.containsMatchIn(text)) return
        show(text.take(500), false)
    }

    private fun collect(n: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (n == null || depth > 5 || sb.length > 600 || n.isPassword) return
        val t = n.text?.takeIf { it.isNotBlank() }
            ?: n.contentDescription?.takeIf { it.isNotBlank() }
        if (t != null) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
            return
        }
        for (i in 0 until n.childCount) {
            collect(n.getChild(i), depth + 1, sb)
        }
    }

    private fun show(text: String, sel: Boolean) {
        sessionToken++
        wordToken++
        fromSelection = sel
        currentText = text
        currentWord = ""
        selStart = -1
        selEnd = -1

        if (!shown) {
            try {
                wm.addView(panel, params)
                shown = true
            } catch (e: Exception) {
                return
            }
        }

        if (wordRegex.matches(text)) {
            scroller.visibility = View.GONE
            sentBn.visibility = View.GONE
            lookupWord(text, -1, -1)
            return
        }

        scroller.visibility = View.VISIBLE
        scroller.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            if (text.length > 140) dp(110) else LinearLayout.LayoutParams.WRAP_CONTENT
        )
        render()
        wordView.visibility = View.GONE
        meaningView.visibility = View.GONE
        wordBn.visibility = View.GONE
        sentBn.visibility = View.GONE
        if (sel) translateSentence()
    }

    private fun render() {
        val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        val sp = SpannableString(currentText)
        sp.setSpan(
            BackgroundColorSpan(Color.parseColor("#55FFD54F")),
            0,
            currentText.length,
            flags
        )
        for (m in anyWord.findAll(currentText)) {
            val start = m.range.first
            val end = m.range.last + 1
            val w = m.value
            val chosen = start == selStart && end == selEnd
            if (chosen) {
                sp.setSpan(BackgroundColorSpan(Color.parseColor("#FFFFD54F")), start, end, flags)
            }
            sp.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        if (longPressed) {
                            longPressed = false
                            return
                        }
                        lookupWord(w, start, end)
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = Color.parseColor(if (chosen) "#202124" else "#8AB4F8")
                        ds.isUnderlineText = false
                    }
                },
                start,
                end,
                flags
            )
        }
        srcView.text = sp
    }

    private fun translateSentence() {
        if (currentText.isEmpty() || wordRegex.matches(currentText)) return
        val my = sessionToken
        val text = currentText
        sentBn.visibility = View.VISIBLE
        sentBn.text = "..."
        pool.execute {
            val bn = translate(text)
            main.post {
                if (my == sessionToken) sentBn.text = bn ?: "No internet"
            }
        }
    }

    private fun lookupWord(w: String, start: Int, end: Int) {
        wordToken++
        val my = wordToken
        val session = sessionToken
        currentWord = w
        selStart = start
        selEnd = end
        if (start >= 0) render()
        wordView.text = w
        wordView.visibility = View.VISIBLE
        meaningView.visibility = View.GONE
        wordBn.visibility = View.VISIBLE
        wordBn.text = "..."

        pool.execute {
            val m = meaning(w)
            main.post {
                if (my == wordToken && session == sessionToken && m != null) {
                    meaningView.text = m
                    meaningView.visibility = View.VISIBLE
                }
            }
        }
        pool.execute {
            val bn = translate(w)
            main.post {
                if (my == wordToken && session == sessionToken) wordBn.text = bn ?: "No internet"
            }
        }
    }

    private fun speakNow() {
        val t = if (currentWord.isNotEmpty()) currentWord else currentText
        if (t.isNotEmpty()) tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "wtt")
    }

    private fun hide() {
        sessionToken++
        wordToken++
        if (shown) {
            try {
                wm.removeView(panel)
            } catch (e: Exception) {
            }
            shown = false
        }
    }

    private fun get(url: String): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 5000
            if (c.responseCode != 200) {
                c.disconnect()
                null
            } else {
                val s = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                s
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun translate(t: String): String? {
        val hit = cache[t]
        if (hit != null) return hit
        val q = URLEncoder.encode(t, "UTF-8")
        var out: String? = null

        val g = get("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=bn&dt=t&q=$q")
        if (g != null) {
            try {
                val arr = JSONArray(g).getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    sb.append(arr.getJSONArray(i).getString(0))
                }
                out = sb.toString()
            } catch (e: Exception) {
            }
        }

        if (out == null) {
            val m = get("https://api.mymemory.translated.net/get?q=$q&langpair=en%7Cbn")
            if (m != null) {
                try {
                    out = JSONObject(m).getJSONObject("responseData").getString("translatedText")
                } catch (e: Exception) {
                }
            }
        }

        if (out != null) cache[t] = out
        return out
    }

    private fun meaning(w: String): String? {
        val body = get("https://api.dictionaryapi.dev/api/v2/entries/en/" + URLEncoder.encode(w, "UTF-8"))
            ?: return null
        return try {
            val m = JSONArray(body).getJSONObject(0).getJSONArray("meanings").getJSONObject(0)
            m.getString("partOfSpeech") + ": " +
                m.getJSONArray("definitions").getJSONObject(0).getString("definition")
        } catch (e: Exception) {
            null
        }
    }

    override fun onInterrupt() {
        hide()
    }

    override fun onDestroy() {
        hide()
        tts?.shutdown()
        pool.shutdown()
        super.onDestroy()
    }
}
