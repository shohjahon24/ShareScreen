package com.guard.sharescreen

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

class CameraSharingService : Service() {
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private lateinit var videoSource: VideoSource
    private var videoTrack: VideoTrack? = null
    private lateinit var audioSource: AudioSource
    private var audioTrack: AudioTrack? = null
    private lateinit var eglBase: EglBase
    private lateinit var signalingClient: SignalingClient
    private var videoCapturer: VideoCapturer? = null

    override fun onCreate() {
        super.onCreate()
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

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient.sendIceCandidate(candidate)
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                Log.d("WebRTC", "onTrack: $transceiver")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d("CameraSharingService", "Connection state changed: $newState")
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

    private fun startCameraCapture() {
        val cameraEnumerator = Camera2Enumerator(this)
        val cameraName = cameraEnumerator.deviceNames.find { cameraEnumerator.isBackFacing(it) }
            ?: cameraEnumerator.deviceNames.first()

        videoCapturer = cameraEnumerator.createCapturer(cameraName, null)
        val surfaceTextureHelper = SurfaceTextureHelper.create("CameraCaptureThread", eglBase.eglBaseContext)

        videoSource = peerConnectionFactory.createVideoSource(false)
        videoCapturer?.initialize(surfaceTextureHelper, this, videoSource.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30)

        videoTrack = peerConnectionFactory.createVideoTrack("CAMERA_TRACK", videoSource)
        videoTrack?.setEnabled(true)

        val videoSender = peerConnection?.addTransceiver(
            videoTrack,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        )?.sender

        val parameters = videoSender?.parameters
        parameters?.encodings?.forEach {
            it.maxBitrateBps = 3_000_000
            it.minBitrateBps = 1_000_000
            it.maxFramerate = 30
        }
        videoSender?.parameters = parameters

        // Microphone Audio
        val audioConstraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }

        audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        audioTrack = peerConnectionFactory.createAudioTrack("MIC_AUDIO", audioSource)
        audioTrack?.setEnabled(true)
        peerConnection?.addTrack(audioTrack)

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
                    "a=fmtp:$1 x-google-start-bitrate=1000000; x-google-max-bitrate=3000000; x-google-min-bitrate=1000000;"
                )
                peerConnection?.setLocalDescription(SdpObserverAdapter(), SessionDescription(description.type, modifiedSdp))
                signalingClient.sendOffer(SessionDescription(description.type, modifiedSdp))
            }

            override fun onCreateFailure(error: String) {
                Log.e("WebRTC", "Failed to create offer: $error")
            }
        }, mediaConstraints)
    }

    private fun startForegroundServiceNotification() {
        val channelId = "CameraSharingChannel"
        val channel = NotificationChannel(channelId, "Camera Sharing Service", NotificationManager.IMPORTANCE_LOW)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Camera Sharing")
            .setContentText("Streaming camera and mic")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startCameraCapture()
        return START_STICKY
    }

    override fun onDestroy() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoTrack?.dispose()
        audioTrack?.dispose()
        audioSource.dispose()
        peerConnection?.close()
        signalingClient.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}