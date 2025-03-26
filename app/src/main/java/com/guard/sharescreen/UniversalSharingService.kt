package com.guard.sharescreen

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import org.webrtc.*
import java.util.*

class UniversalSharingService : Service() {
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private lateinit var eglBase: EglBase
    private lateinit var signalingClient: SignalingClient

    // Camera & Audio
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    // Screen Sharing
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var currentMode: String? = null

    override fun onCreate() {
        super.onCreate()
        eglBase = EglBase.create()
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
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
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
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

        peerConnection =
            peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    signalingClient.sendIceCandidate(candidate)
                }

                override fun onTrack(transceiver: RtpTransceiver) {}
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
                override fun onDataChannel(dc: DataChannel) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
            })
    }

    private fun initializeSignalingClient() {
        signalingClient = SignalingClient(
            serverUrl = "http://10.100.26.3:3000",
            clientId = UUID.randomUUID().toString(),
            onOfferReceived = { offer, _ ->
                peerConnection?.setRemoteDescription(SdpObserverAdapter(), offer)
                peerConnection?.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        peerConnection?.setLocalDescription(SdpObserverAdapter(), description)
                        signalingClient.sendAnswer(description)
                    }
                }, MediaConstraints())
            },
            onAnswerReceived = { answer, _ ->
                peerConnection?.setRemoteDescription(SdpObserverAdapter(), answer)
            },
            onIceCandidateReceived = { candidate, _ ->
                peerConnection?.addIceCandidate(candidate)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("mode")

        if (mode == "stop") {
            stopCurrentCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        if (mode != null && mode != currentMode) {
            currentMode = mode

            when (mode) {
                "screen" -> {
                    val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                    val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("data", Intent::class.java)
                    } else {
                        intent.getParcelableExtra<Intent>("data")
                    }

                    if (resultCode == Activity.RESULT_OK && data != null) {
                        startScreenCapture(resultCode, data)
                    }
                }

                "front" -> {
                    startCameraCapture(front = true)

                }

                "back" -> {
                    startCameraCapture(front = false)
                }
            }

        }

        return START_STICKY
    }

    private fun startCameraCapture(front: Boolean) {
        val cameraEnumerator = Camera2Enumerator(this)
        val cameraName = cameraEnumerator.deviceNames.find {
            if (front) cameraEnumerator.isFrontFacing(it) else cameraEnumerator.isBackFacing(it)
        } ?: cameraEnumerator.deviceNames.first()

        videoCapturer = cameraEnumerator.createCapturer(cameraName, null)
        val surfaceTextureHelper =
            SurfaceTextureHelper.create("CameraCaptureThread", eglBase.eglBaseContext)

        videoSource = peerConnectionFactory.createVideoSource(false)
        videoCapturer?.initialize(surfaceTextureHelper, this, videoSource!!.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30)

        videoTrack = peerConnectionFactory.createVideoTrack("CAMERA_TRACK", videoSource)
        videoTrack?.setEnabled(true)

        audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        audioTrack = peerConnectionFactory.createAudioTrack("MIC_AUDIO", audioSource)
        audioTrack?.setEnabled(true)

        peerConnection?.addTrack(audioTrack)
        peerConnection?.addTrack(videoTrack)

        createAndSendOffer()
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        val surfaceTextureHelper =
            SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)

        videoSource = peerConnectionFactory.createVideoSource(false)
        val videoCapturer = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        })

        videoCapturer.initialize(surfaceTextureHelper, this, videoSource!!.capturerObserver)
        videoCapturer.startCapture(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
            30
        )

        videoTrack = peerConnectionFactory.createVideoTrack("SCREEN_SHARE_TRACK", videoSource)
        videoTrack?.setEnabled(true)
        peerConnection?.addTrack(videoTrack)


        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            Surface(surfaceTextureHelper.surfaceTexture),
            null, null
        )

        createAndSendOffer()
    }

    private fun createAndSendOffer() {
        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                val modifiedSdp = description.description.replace(
                    Regex("a=fmtp:(\\d+)\\s"),
                    "a=fmtp:$1 x-google-start-bitrate=1000000; x-google-max-bitrate=4000000; x-google-min-bitrate=1000000;"
                )
                val sdp = SessionDescription(description.type, modifiedSdp)
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                signalingClient.sendOffer(sdp)
            }

            override fun onCreateFailure(error: String) {
                Log.e("WebRTC", "Failed to create offer: $error")
            }
        }, mediaConstraints)
    }

    private fun startForegroundServiceNotification() {
        val channelId = "UniversalSharingChannel"
        val channel =
            NotificationChannel(channelId, "Sharing Service", NotificationManager.IMPORTANCE_LOW)
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Universal Sharing")
            .setContentText("Streaming screen or camera")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoSource?.dispose()
        videoTrack?.dispose()
        audioTrack?.dispose()
        audioSource?.dispose()
        virtualDisplay?.release()
        mediaProjection?.stop()
        peerConnection?.close()
        signalingClient.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopCurrentCapture() {
        try {
            videoCapturer?.let { capturer ->
                try {
                    capturer.stopCapture()
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                } catch (e: RuntimeException) {
                    e.printStackTrace()
                }
                capturer.dispose()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        videoCapturer = null

        videoTrack?.dispose()
        videoTrack = null

        videoSource?.dispose()
        videoSource = null

        audioTrack?.dispose()
        audioTrack = null

        audioSource?.dispose()
        audioSource = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null
    }

}
