// child-app/src/main/java/com/guardianlink/child/webrtc/ChildWebRtcClient.kt
package com.guardianlink.child.webrtc

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.util.StreamSessionPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ChildWebRtcClient — manages WebRTC peer connection on the child device.
 *
 * Signaling is done via Firebase Realtime DB:
 *  /webrtc/{sessionId}/offer   — parent's SDP offer
 *  /webrtc/{sessionId}/answer  — child's SDP answer
 *  /webrtc/{sessionId}/candidates/{id} — ICE candidates
 *
 * Data channels are used for screen frames and audio when not using
 * native WebRTC media tracks.
 */
@Singleton
class ChildWebRtcClient @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val eglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var sessionId: String = ""
    private var sessionExpiresAt: Long = 0L
    private var offerListener: ValueEventListener? = null
    private var remoteCandidatesListener: ValueEventListener? = null
    private val receivedCandidateIds = mutableSetOf<String>()
    private var connected = false

    // ICE servers — use Google STUN + configure TURN in production
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        // Add TURN servers for reliable NAT traversal:
        // PeerConnection.IceServer.builder("turn:your-turn-server.com:3478")
        //     .setUsername("user").setPassword("pass").createIceServer()
    )

    init {
        initializeWebRtc()
    }

    // ── Initialization ────────────────────────────────────────────────────────

    private fun initializeWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()

        Timber.i("WebRTC initialized")
    }

    // ── Session Management ────────────────────────────────────────────────────

    /**
     * Start listening for an offer from the parent for this session.
     * When received, creates answer and exchanges ICE candidates.
     */
    fun startSession(session: String, expiresAt: Long) {
        stopSession()
        require(StreamSessionPolicy.isValidSessionId(session)) { "Invalid WebRTC session id" }
        require(expiresAt > System.currentTimeMillis()) { "WebRTC session has expired" }
        sessionId = session
        sessionExpiresAt = expiresAt
        listenForOffer()
    }

    fun stopSession() {
        offerListener?.let {
            FirebaseDatabase.getInstance()
                .getReference("${FirebasePaths.webRtcPath(sessionId)}/offer")
                .removeEventListener(it)
        }
        remoteCandidatesListener?.let {
            FirebaseDatabase.getInstance()
                .getReference("${FirebasePaths.webRtcPath(sessionId)}/parent_candidates")
                .removeEventListener(it)
        }
        offerListener = null
        remoteCandidatesListener = null
        receivedCandidateIds.clear()
        peerConnection?.close()
        dataChannel?.close()
        peerConnection = null
        dataChannel    = null
        connected      = false
        sessionId      = ""
        sessionExpiresAt = 0L
        Timber.i("WebRTC session stopped")
    }

    fun isConnected(): Boolean = connected

    // ── Signaling ─────────────────────────────────────────────────────────────

    private fun listenForOffer() {
        val offerRef = FirebaseDatabase.getInstance()
            .getReference("${FirebasePaths.webRtcPath(sessionId)}/offer")

        offerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (sessionExpired()) return
                val sdp = snapshot.getValue(String::class.java) ?: return
                handleOffer(sdp)
                offerRef.removeEventListener(this)
                offerListener = null
            }
            override fun onCancelled(error: DatabaseError) {
                Timber.e("WebRTC offer listener cancelled: ${error.message}")
            }
        }
        offerRef.addValueEventListener(offerListener!!)
    }

    private fun handleOffer(sdpString: String) {
        if (sessionExpired()) return
        createPeerConnection()

        val offerSdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)

        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                createAnswer()
            }
            override fun onSetFailure(error: String?) {
                Timber.e("setRemoteDescription failed: $error")
            }
        }, offerSdp)
    }

    private fun createAnswer() {
        if (sessionExpired()) return
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                // Push answer to Firebase
                FirebaseDatabase.getInstance()
                    .getReference("${FirebasePaths.webRtcPath(sessionId)}/answer")
                    .setValue(sdp.description)
            }
            override fun onCreateFailure(error: String?) {
                Timber.e("createAnswer failed: $error")
            }
        }, MediaConstraints())
    }

    private fun createPeerConnection() {
        if (sessionExpired()) return
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate == null || sessionExpired()) return
                    // Push ICE candidate to Firebase
                    FirebaseDatabase.getInstance()
                        .getReference("${FirebasePaths.webRtcPath(sessionId)}/candidates")
                        .push()
                        .setValue(mapOf(
                            "sdpMid"        to candidate.sdpMid,
                            "sdpMLineIndex" to candidate.sdpMLineIndex,
                            "sdp"           to candidate.sdp
                        ))
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                    connected = state == PeerConnection.PeerConnectionState.CONNECTED
                    if (state == PeerConnection.PeerConnectionState.FAILED ||
                        state == PeerConnection.PeerConnectionState.DISCONNECTED ||
                        state == PeerConnection.PeerConnectionState.CLOSED
                    ) {
                        connected = false
                    }
                    Timber.i("WebRTC connection state: $state")
                }

                override fun onDataChannel(dc: DataChannel?) {
                    dataChannel = dc
                    Timber.i("Data channel received: ${dc?.label()}")
                }

                // Required overrides (no-op for our use case)
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            }
        )

        // Also listen for remote ICE candidates from parent
        listenForRemoteCandidates()
    }

    private fun listenForRemoteCandidates() {
        val ref = FirebaseDatabase.getInstance()
            .getReference("${FirebasePaths.webRtcPath(sessionId)}/parent_candidates")

        remoteCandidatesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
            if (sessionExpired()) return
                snapshot.children.forEach { childSnap ->
                    val candidateId = childSnap.key ?: return@forEach
                    if (!receivedCandidateIds.add(candidateId)) return@forEach
                    val sdpMid   = childSnap.child("sdpMid").getValue(String::class.java) ?: return@forEach
                    val sdpIdx   = childSnap.child("sdpMLineIndex").getValue(Int::class.java) ?: 0
                    val sdp      = childSnap.child("sdp").getValue(String::class.java) ?: return@forEach
                    val candidate = IceCandidate(sdpMid, sdpIdx, sdp)
                    peerConnection?.addIceCandidate(candidate)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(remoteCandidatesListener!!)
    }

    // ── Data Sending ──────────────────────────────────────────────────────────

    fun sendScreenFrame(bytes: ByteArray) = sendData(bytes)
    fun sendCameraFrame(bytes: ByteArray) = sendData(bytes)
    fun sendAudioFrame(bytes: ByteArray)  = sendData(bytes)

    private fun sendData(bytes: ByteArray) {
        if (sessionExpired()) {
            connected = false
            return
        }
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        val buffer = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(bytes), true
        )
        dc.send(buffer)
    }

    private fun sessionExpired(): Boolean =
        sessionId.isBlank() || sessionExpiresAt <= System.currentTimeMillis()

    // ── SdpObserverAdapter ────────────────────────────────────────────────────

    open inner class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Timber.e("SDP create failure: $error")
        }
        override fun onSetFailure(error: String?) {
            Timber.e("SDP set failure: $error")
        }
    }
}
