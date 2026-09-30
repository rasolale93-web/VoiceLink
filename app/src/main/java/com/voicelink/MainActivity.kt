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
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private var ws: WebSocket? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var localAudio: AudioTrack? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

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
        initializeWebRtc()
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

        val disconnect = button("قطع تماس")
        disconnect.setOnClickListener { disconnectCall() }
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
            setSingleLine(true)
        }
        settingsPanel.addView(url)
        settingsPanel.addView(code)

        val connect = button("🔗 اتصال به سرور")
        settingsPanel.addView(connect, LinearLayout.LayoutParams(-1, dp(54)).apply {
            setMargins(0, dp(8), 0, 0)
        })
        page.addView(settingsPanel)

        page.addView(text(
            "صدای واقعی با WebRTC فعال است. گوشی گیرنده پس از دریافت درخواست تماس، به‌صورت خودکار پاسخ صوتی می‌دهد.",
            13f
        ).apply { setPadding(dp(8), dp(18), dp(8), 0) })

        settingsButton.setOnClickListener {
            settingsPanel.visibility = if (settingsPanel.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        role.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                primaryAction.text = if (position == 0) "📞 برقراری تماس" else "🔔 آماده دریافت تماس"
            }
        }

        connect.setOnClickListener { connectToServer() }
        primaryAction.setOnClickListener { startCall() }

        scroll.addView(page)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun initializeWebRtc() {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        } catch (e: Exception) {
            status.text = "🔴 خطای آماده‌سازی صدا"
        }
    }

    private fun createPeer() {
        peer?.dispose()
        remoteDescriptionSet = false
        pendingCandidates.clear()

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peer = factory?.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                sendSignal(JSONObject().apply {
                    put("type", "ice")
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                })
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                runOnUiThread {
                    status.text = when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> "🟢 تماس صوتی برقرار است"
                        PeerConnection.IceConnectionState.DISCONNECTED -> "🟡 ارتباط صوتی قطع شد"
                        PeerConnection.IceConnectionState.FAILED -> "🔴 اتصال صوتی ناموفق"
                        else -> status.text
                    }
                }
            }

            override fun onAddStream(stream: MediaStream) {
                runOnUiThread { status.text = "🟢 صدای طرف مقابل متصل شد" }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddIceCandidate(candidate: IceCandidate) {}
            override fun onIceConnectionChangeLegacy(state: PeerConnection.IceConnectionState) {}
            override fun onDataChannel(dataChannel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
            override fun onTrack(transceiver: RtpTransceiver) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {}
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {}
            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
        })

        val audioSource = factory?.createAudioSource(MediaConstraints())
        localAudio = factory?.createAudioTrack("voicelink_audio", audioSource)
        localAudio?.setEnabled(true)
        localAudio?.let { track ->
            peer?.addTrack(track, listOf("voicelink_stream"))
        }
    }

    private fun startCall() {
        if (role.selectedItemPosition != 0) {
            status.text = "🟢 گیرنده آماده دریافت خودکار تماس است"
            return
        }
        if (ws == null) {
            status.text = "🔴 ابتدا به سرور وصل شوید"
            settingsPanel.visibility = View.VISIBLE
            return
        }
        createPeer()
        status.text = "🟡 در حال ایجاد تماس صوتی..."
        peer?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                peer?.setLocalDescription(SimpleSdpObserver(), description)
                sendSignal(JSONObject().apply {
                    put("type", "offer")
                    put("sdp", description.description)
                })
                runOnUiThread { status.text = "📞 درخواست تماس صوتی ارسال شد" }
            }
            override fun onCreateFailure(error: String) {
                runOnUiThread { status.text = "🔴 ایجاد تماس ناموفق" }
            }
        }, MediaConstraints())
    }

    private fun handleSignal(raw: String) {
        try {
            val msg = JSONObject(raw)
            when (msg.optString("type")) {
                "call" -> {
                    if (role.selectedItemPosition == 1) {
                        runOnUiThread { status.text = "🔔 تماس ورودی؛ پاسخ خودکار..." }
                    }
                }
                "offer" -> {
                    if (role.selectedItemPosition != 1) return
                    createPeer()
                    val description = SessionDescription(SessionDescription.Type.OFFER, msg.getString("sdp"))
                    peer?.setRemoteDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            remoteDescriptionSet = true
                            flushCandidates()
                            peer?.createAnswer(object : SimpleSdpObserver() {
                                override fun onCreateSuccess(answer: SessionDescription) {
                                    peer?.setLocalDescription(SimpleSdpObserver(), answer)
                                    sendSignal(JSONObject().apply {
                                        put("type", "answer")
                                        put("sdp", answer.description)
                                    })
                                    runOnUiThread { status.text = "🟢 پاسخ خودکار تماس ارسال شد" }
                                }
                            }, MediaConstraints())
                        }
                    }, description)
                }
                "answer" -> {
                    val description = SessionDescription(SessionDescription.Type.ANSWER, msg.getString("sdp"))
                    peer?.setRemoteDescription(object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            remoteDescriptionSet = true
                            flushCandidates()
                            runOnUiThread { status.text = "🟢 در انتظار برقراری صدای دوطرفه..." }
                        }
                    }, description)
                }
                "ice" -> {
                    val candidate = IceCandidate(
                        msg.optString("sdpMid"),
                        msg.optInt("sdpMLineIndex"),
                        msg.optString("candidate")
                    )
                    if (remoteDescriptionSet) peer?.addIceCandidate(candidate)
                    else pendingCandidates.add(candidate)
                }
                "joined" -> runOnUiThread {
                    status.text = if (role.selectedItemPosition == 0) "🟢 آماده تماس" else "🟢 گیرنده آماده است"
                    primaryAction.isEnabled = true
                }
            }
        } catch (_: Exception) {
            runOnUiThread { status.text = "🔴 پیام ارتباطی نامعتبر" }
        }
    }

    private fun flushCandidates() {
        pendingCandidates.forEach { peer?.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    private fun sendSignal(message: JSONObject) {
        val currentCode = code.text.toString().trim()
        message.put("code", currentCode)
        ws?.send(message.toString())
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
                runOnUiThread { status.text = "🟢 اتصال به سرور برقرار است" }
                val join = JSONObject().apply {
                    put("type", "join")
                    put("code", code.text.toString().trim())
                    put("role", role.selectedItem.toString())
                }
                webSocket.send(join.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignal(text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    status.text = "🔴 خطای اتصال به سرور"
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

    private fun disconnectCall() {
        ws?.close(1000, "user")
        peer?.close()
        peer?.dispose()
        peer = null
        localAudio = null
        remoteDescriptionSet = false
        pendingCandidates.clear()
        primaryAction.isEnabled = false
        status.text = "⚪ تماس قطع شد"
    }

    override fun onDestroy() {
        disconnectCall()
        factory?.dispose()
        factory = null
        super.onDestroy()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {}
        override fun onSetFailure(error: String) {}
    }
}
