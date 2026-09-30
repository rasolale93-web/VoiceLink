package com.voicelink

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import okhttp3.*
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private var ws: WebSocket? = null
    private lateinit var status: TextView
    private lateinit var url: EditText
    private lateinit var code: EditText
    private lateinit var role: Spinner
    private lateinit var primaryAction: Button
    private lateinit var settingsPanel: LinearLayout

    private val green = Color.rgb(48, 135, 91)
    private val lightGreen = Color.rgb(232, 247, 238)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(Color.rgb(35, 45, 40))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

    private fun button(value: String): Button =
        Button(this).apply {
            text = value
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(green)
            minHeight = dp(52)
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
        }

        val title = text("VoiceLink", 30f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(green)
        }
        page.addView(title, LinearLayout.LayoutParams(-1, dp(48)))
        page.addView(text("تماس صوتی ساده و خانوادگی", 15f).apply { gravity = Gravity.CENTER })

        page.addView(text("نقش این گوشی", 18f, true))
        role = Spinner(this)
        role.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("تماس‌گیرنده", "گیرنده"))
        page.addView(role, LinearLayout.LayoutParams(-1, dp(52)))

        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(lightGreen)
        }
        statusBox.addView(text("وضعیت ارتباط", 14f, true))
        status = text("⚪ آماده اتصال", 18f, true)
        statusBox.addView(status)
        page.addView(statusBox, LinearLayout.LayoutParams(-1, dp(88)).apply {
            setMargins(0, dp(12), 0, dp(12))
        })

        primaryAction = button("📞 برقراری تماس")
        primaryAction.isEnabled = false
        page.addView(primaryAction, LinearLayout.LayoutParams(-1, dp(62)).apply {
            setMargins(0, 0, 0, dp(10))
        })

        val disconnect = button("قطع اتصال")
        disconnect.setOnClickListener {
            ws?.close(1000, "user")
            status.text = "⚪ اتصال بسته شد"
            primaryAction.isEnabled = false
        }
        page.addView(disconnect, LinearLayout.LayoutParams(-1, dp(54)))

        val settingsButton = Button(this).apply {
            text = "⚙ تنظیمات اتصال"
            textSize = 15f
            isAllCaps = false
        }
        page.addView(settingsButton)

        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        url = EditText(this).apply {
            hint = "آدرس WebSocket سرور"
            textSize = 15f
            setSingleLine(true)
        }
        code = EditText(this).apply {
            hint = "کد اتصال، مثلاً 1234"
            textSize = 15f
            singleLine = true
        }
        settingsPanel.addView(url)
        settingsPanel.addView(code)

        val connect = button("🔗 اتصال به سرور")
        settingsPanel.addView(connect, LinearLayout.LayoutParams(-1, dp(54)).apply {
            setMargins(0, dp(8), 0, 0)
        })
        page.addView(settingsPanel)

        page.addView(text(
            "مرحله فعلی: ارتباط WebSocket و رابط کاربری.\nمرحله بعد: اتصال صوت واقعی و تکمیل رفتار تماس‌گیرنده/گیرنده.",
            13f
        ).apply { setPadding(dp(8), dp(18), dp(8), 0) })

        settingsButton.setOnClickListener {
            settingsPanel.visibility = if (settingsPanel.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        role.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                primaryAction.text = if (position == 0) "📞 برقراری تماس" else "🔔 بررسی تماس ورودی"
            }
        }

        connect.setOnClickListener { connectToServer() }

        primaryAction.setOnClickListener {
            val currentCode = code.text.toString().trim()
            ws?.send("""{"type":"call","code":"${code.text}"}""".replace("${code.text}", currentCode))
            status.text = if (role.selectedItemPosition == 0) "📞 درخواست تماس ارسال شد" else "🔔 پیام تماس بررسی شد"
        }

        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun connectToServer() {
        val address = url.text.toString().trim()
        if (address.isEmpty()) {
            status.text = "🔴 آدرس سرور را وارد کنید"
            settingsPanel.visibility = View.VISIBLE
            return
        }
        status.text = "🟡 در حال اتصال..."
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        ws = client.newWebSocket(Request.Builder().url(address).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    status.text = "🟢 اتصال برقرار است"
                    primaryAction.isEnabled = true
                }
                val join = """{"type":"join","code":"${code.text}","role":"@@ROLE@@"}"""
                    .replace("${code.text}", code.text.toString())
                    .replace("@@ROLE@@", role.selectedItem.toString())
                webSocket.send(join)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread { status.text = "📩 پیام دریافت شد" }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    status.text = "🔴 خطای اتصال"
                    primaryAction.isEnabled = false
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    status.text = "⚪ اتصال بسته شد"
                    primaryAction.isEnabled = false
                }
            }
        })
    }
}
