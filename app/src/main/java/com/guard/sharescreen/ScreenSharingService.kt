package com.guard.sharescreen

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresPermission
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

class ScreenSharingService : Service() {
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var videoSource: VideoSource
    private var videoTrack: VideoTrack? = null
    private var eglBase: EglBase? = null
    private lateinit var signalingClient: SignalingClient
    private var surfaceViewRenderer: SurfaceViewRenderer? = null

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        eglBase = EglBase.create()
        initializeWebRTC()
        initializeSignalingClient()
        startForegroundServiceNotification()
    }

    private fun initializeWebRTC() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
        )

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase!!.eglBaseContext))
            .createPeerConnectionFactory()

        setupPeerConnection()
    }

    private fun setupPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            enableDtlsSrtp = true
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient.sendIceCandidate(candidate)
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                Log.d("WebRTC", "Track received: ${transceiver.receiver.track()?.kind()}")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d("WebRTC", "Connection state changed: $newState")
            }

            override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream?>) {}
            override fun onDataChannel(dataChannel: DataChannel) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
        })
    }

    private fun initializeSignalingClient() {
        signalingClient = SignalingClient(
            serverUrl = "http://10.100.26.3:3000",
            clientId = UUID.randomUUID().toString(),
            onOfferReceived = { offer, senderId ->
                peerConnection?.setRemoteDescription(SdpObserverAdapter(), offer)
                peerConnection?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), description)
                        signalingClient.sendAnswer(description)
                    }
                }, MediaConstraints())
            },
            onAnswerReceived = { answer, senderId ->
                peerConnection?.setRemoteDescription(SdpObserverAdapter(), answer)
            },
            onIceCandidateReceived = { candidate, senderId ->
                peerConnection?.addIceCandidate(candidate)
            }
        )
    }

    private fun startForegroundServiceNotification() {
        val channelId = "ScreenSharingChannel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "Screen Sharing", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Screen Sharing")
            .setContentText("Streaming your screen and audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startScreenCapture(resultCode: Int, data: Intent) {
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        val surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase!!.eglBaseContext)

        videoSource = peerConnectionFactory.createVideoSource(false)
        val videoCapturer = ScreenCapturerAndroid(
            data,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopScreenCapture()
                }
            }
        )

        videoCapturer.initialize(surfaceTextureHelper, this, videoSource.capturerObserver)
        videoCapturer.startCapture(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
            30
        )

        videoTrack = peerConnectionFactory.createVideoTrack("SCREEN_SHARE_TRACK", videoSource)
        videoTrack?.setEnabled(true)

        val videoSender = peerConnection?.addTransceiver(
            videoTrack,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        )?.sender

        val videoParams = videoSender?.parameters
        videoParams?.encodings?.forEach {
            it.maxBitrateBps = 4_000_000
            it.minBitrateBps = 1_500_000
            it.maxFramerate = 30
        }
        videoSender?.parameters = videoParams

        val displayMetrics = resources.displayMetrics
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            displayMetrics.widthPixels,
            displayMetrics.heightPixels,
            displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            Surface(surfaceTextureHelper.surfaceTexture),
            object : VirtualDisplay.Callback() {
                override fun onStopped() {
                    stopScreenCapture()
                }
            },
            null
        )

        // ✅ INTERNAL AUDIO CAPTURE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val bufferSize = AudioRecord.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioRecord.startRecording()

            val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            val audioTrack = peerConnectionFactory.createAudioTrack("INTERNAL_AUDIO_TRACK", audioSource)
            audioTrack.setEnabled(true)
            peerConnection?.addTrack(audioTrack)

            Log.d("WebRTC", "Internal audio capture started")
        } else {
            Log.e("WebRTC", "Internal audio capture requires Android 10+")
        }

        createAndSendOfferForScreenSharing()
    }

    private fun createAndSendOfferForScreenSharing() {
        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                val modifiedSdp = description.description.replace(
                    Regex("a=fmtp:(\\d+)\\s"),
                    "a=fmtp:$1 x-google-start-bitrate=1600000; x-google-max-bitrate=4000000; x-google-min-bitrate=1500000;"
                )

                val sessionDescription = SessionDescription(description.type, modifiedSdp)
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sessionDescription)
                signalingClient.sendOffer(sessionDescription)
            }

            override fun onCreateFailure(error: String) {
                Log.e("WebRTC", "Failed to create offer: $error")
            }
        }, mediaConstraints)
    }

    fun stopScreenCapture() {
        peerConnection?.close()
        virtualDisplay?.release()
        mediaProjection?.stop()
        virtualDisplay = null
        mediaProjection = null
        videoTrack?.dispose()
        surfaceViewRenderer?.release()
        surfaceViewRenderer = null
        videoTrack = null
        signalingClient.disconnect()
        stopSelf()
        Log.d("ScreenSharingService", "Screen sharing stopped")
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode == Activity.RESULT_OK && data != null) {
            startScreenCapture(resultCode, data)
        } else {
            Log.e("ScreenSharingService", "Invalid MediaProjection parameters")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class SignalingClient(
    serverUrl: String,
    private val clientId: String, // Add a clientId
    private val onOfferReceived: (SessionDescription, String) -> Unit, // Pass ID with messages
    private val onAnswerReceived: (SessionDescription, String) -> Unit,
    private val onIceCandidateReceived: (IceCandidate, String) -> Unit
) {

    private val options = IO.Options().apply {
        secure = true // Use HTTPS
        transports = arrayOf("websocket", "polling") // Specify transport methods
    }
    private val socket: Socket = IO.socket(serverUrl, options)

    init {
        socket.on("offer") { args ->
            val data = args[0] as JSONObject
            Log.d("WebRTC", "Offer received: $data")

            // Extract the `offer` object and then the `sdp` field
            val offerObject = data.optJSONObject("offer")
            val sdp = offerObject?.optString("sdp") // Safely extract SDP from `offer`
            val senderId = data.optString("clientId") // Safely extract sender ID

            if (sdp != null && senderId.isNotEmpty()) {
                onOfferReceived(
                    SessionDescription(SessionDescription.Type.OFFER, sdp), senderId
                )
            } else {
                Log.e("WebRTC", "Invalid offer data: $data")
            }
        }

        socket.on("answer") { args ->
            val data = args[0] as JSONObject
            Log.d("WebRTC", "Answer received: $data")

            // Extract the `answer` object and then the `sdp` field
            val answerObject = data.optJSONObject("answer")
            val sdp = answerObject?.optString("sdp") // Safely extract SDP from `answer`
            val senderId = data.optString("clientId") // Safely extract sender ID
            Log.d("WebRTC", ": $senderId")

            if (sdp != null && senderId.isNotEmpty()) {
                onAnswerReceived(
                    SessionDescription(SessionDescription.Type.ANSWER, sdp), senderId
                )
            } else {
                Log.e("WebRTC", "Invalid answer data: $data")
            }
        }

        socket.on("ice-candidate") { args ->
            val data = args[0] as JSONObject
            Log.d("WebRTC", "ICE Candidate received: $data")

            // Extract the `candidate` object
            val candidateObject = data.optJSONObject("candidate")
            val clientId = data.optString("clientId")

            if (candidateObject != null) {
                val candidate =
                    candidateObject.optString("candidate") // Extract the `candidate` string
                val sdpMid = candidateObject.optString("sdpMid")       // Extract `sdpMid`
                val sdpMLineIndex =
                    candidateObject.optInt("sdpMLineIndex", -1) // Extract `sdpMLineIndex`

                // Validate fields
                if (candidate.isNotEmpty() && sdpMid.isNotEmpty() && sdpMLineIndex >= 0 && clientId.isNotEmpty()) {
                    // Create ICE candidate object
                    val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)

                    // Pass it to the handler
                    onIceCandidateReceived(iceCandidate, clientId)
                } else {
                    Log.e("WebRTC", "Invalid ICE candidate data: $data")
                }
            } else {
                Log.e("WebRTC", "Missing candidate object in ICE candidate data: $data")
            }
        }

        socket.on("connect_error") {
            Log.d("WebRTC", "Failed to connect to signaling server. Retrying...")
            socket.connect()
        }


        socket.connect()
        Log.d("WebRTC", "Signaling client connected: ${socket.isActive}")
    }

    fun sendAnswer(answer: SessionDescription) {
        val message = JSONObject().apply {
            put("answer", JSONObject().apply { // Nest the answer
                put("type", "answer")
                put("sdp", answer.description)
            })
            put("clientId", clientId) // Attach clientId
        }
        socket.emit("answer", message)
        Log.d("WebRTC", "Answer sent with ID: $clientId")
    }

    fun sendOffer(offer: SessionDescription) {
        val message = JSONObject().apply {
            put("offer", JSONObject().apply { // Nest the offer
                put("type", "offer")
                put("sdp", offer.description)
            })
            put("clientId", clientId) // Attach clientId
        }
        socket.emit("offer", message)
        Log.d("WebRTC", "Offer sent with ID: $clientId")
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        val message = JSONObject().apply {
            put("candidate", JSONObject().apply { // Nest the candidate
                put("candidate", candidate.sdp)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            })
            put("clientId", clientId) // Attach clientId
        }
        socket.emit("ice-candidate", message)
        Log.d("WebRTC", "ICE candidate sent with ID: $clientId")
    }

    fun disconnect() {
        socket.disconnect()
    }
}

open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
