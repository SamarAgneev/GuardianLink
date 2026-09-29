package com.guardianlink.parent.data.webrtc

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.guardianlink.common.constants.FirebasePaths
import com.guardianlink.common.model.StreamSession
import com.guardianlink.common.util.StreamSessionPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.*
import timber.log.Timber
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParentWebRtcClient @Inject constructor(
    @ApplicationContext context: Context
) {
    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var session: StreamSession? = null
    private var answerListener: ValueEventListener? = null
    private var childCandidatesListener: ValueEventListener? = null
    private val receivedCandidateIds = mutableSetOf<String>()
    private var onFrame: ((ByteArray) -> Unit)? = null

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun startSession(streamSession: StreamSession, expectedDeviceId: String, frameHandler: (ByteArray) -> Unit): Boolean {
        stopSession()
        val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        // StreamSessionPolicy.canStart() checks session-id format, expiry, deviceId match,
        // parentId match, AND status == PENDING — this is stricter than the previous
        // isValidSessionId()-only check, which would silently accept a stale session
        // (already ACTIVE/ENDED/FAILED from a previous stream) as long as its expiresAt
        // timestamp hadn't passed, letting the parent "reconnect" to a session the child
        // is no longer listening on instead of the freshly-created PENDING one.
        if (currentUid == null ||
            !StreamSessionPolicy.canStart(streamSession, expectedDeviceId, currentUid, System.currentTimeMillis())
        ) {
            return false
        }
        session = streamSession
        onFrame = frameHandler

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        peerConnection = factory.createPeerConnection(config, observer()) ?: run {
            session = null
            onFrame = null
            return false
        }
        dataChannel = peerConnection?.createDataChannel("guardianlink-stream", DataChannel.Init())?.also {
            it.registerObserver(dataChannelObserver())
        }
        listenForAnswer()
        listenForChildCandidates()
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                signalingRef("offer").setValue(sdp.description)
            }
        }, MediaConstraints())
        return true
    }

    fun stopSession() {
        val current = session
        current?.let {
            answerListener?.let { listener -> signalingRef("answer", it.sessionId).removeEventListener(listener) }
            childCandidatesListener?.let { listener ->
                FirebaseDatabase.getInstance().getReference("${FirebasePaths.webRtcPath(it.sessionId)}/candidates")
                    .removeEventListener(listener)
            }
        }
        answerListener = null
        childCandidatesListener = null
        receivedCandidateIds.clear()
        dataChannel?.close()
        peerConnection?.close()
        dataChannel = null
        peerConnection = null
        session = null
        onFrame = null
    }

    private fun listenForAnswer() {
        val current = session ?: return
        val ref = signalingRef("answer")
        answerListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (current.expiresAt.toDate().time <= System.currentTimeMillis()) return
                val answer = snapshot.getValue(String::class.java) ?: return
                peerConnection?.setRemoteDescription(
                    SdpObserverAdapter(),
                    SessionDescription(SessionDescription.Type.ANSWER, answer)
                )
                ref.removeEventListener(this)
                answerListener = null
            }
            override fun onCancelled(error: DatabaseError) {
                Timber.e("WebRTC answer listener cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(answerListener!!)
    }

    private fun listenForChildCandidates() {
        val current = session ?: return
        val ref = FirebaseDatabase.getInstance()
            .getReference("${FirebasePaths.webRtcPath(current.sessionId)}/candidates")
        childCandidatesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (current.expiresAt.toDate().time <= System.currentTimeMillis()) return
                snapshot.children.forEach { candidateSnapshot ->
                    val candidateId = candidateSnapshot.key ?: return@forEach
                    if (!receivedCandidateIds.add(candidateId)) return@forEach
                    val mid = candidateSnapshot.child("sdpMid").getValue(String::class.java) ?: return@forEach
                    val index = candidateSnapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: return@forEach
                    val sdp = candidateSnapshot.child("sdp").getValue(String::class.java) ?: return@forEach
                    peerConnection?.addIceCandidate(IceCandidate(mid, index, sdp))
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Timber.e("WebRTC candidate listener cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(childCandidatesListener!!)
    }

    private fun observer() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            val current = session ?: return
            candidate ?: return
            FirebaseDatabase.getInstance()
                .getReference("${FirebasePaths.webRtcPath(current.sessionId)}/parent_candidates")
                .push()
                .setValue(mapOf("sdpMid" to candidate.sdpMid, "sdpMLineIndex" to candidate.sdpMLineIndex, "sdp" to candidate.sdp))
        }
        override fun onDataChannel(channel: DataChannel?) {
            dataChannel = channel
            channel?.registerObserver(dataChannelObserver())
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
            Timber.i("Parent WebRTC connection state: $state")
        }
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

    private fun dataChannelObserver() = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() {}
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            onFrame?.invoke(bytes)
        }
    }

    private fun signalingRef(child: String, id: String = session?.sessionId.orEmpty()) =
        FirebaseDatabase.getInstance().getReference("${FirebasePaths.webRtcPath(id)}/$child")

    open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Timber.e("WebRTC SDP create failure: $error") }
        override fun onSetFailure(error: String?) { Timber.e("WebRTC SDP set failure: $error") }
    }
}