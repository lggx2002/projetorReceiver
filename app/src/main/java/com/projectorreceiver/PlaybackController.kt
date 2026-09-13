package com.projectorreceiver

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class PlaybackState {
    IDLE,
    CONNECTING,
    PLAYING,
    PAUSED,
    BUFFERING,
    ERROR
}

data class PlayRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val type: String = "auto"
)

data class PlaybackStatus(
    val state: PlaybackState,
    val url: String?,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedMs: Long,
    val decoder: String?,
    val error: String?,
    val videoWidth: Int,
    val videoHeight: Int
)

@SuppressLint("UnsafeOptInUsageError")
class PlaybackController(private val appContext: Context) {
    interface UiListener {
        fun onPlaybackStateChanged(state: PlaybackState, message: String?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiListeners = CopyOnWriteUiListeners()

    private var player: ExoPlayer? = null
    private var attachedPlayerView: PlayerView? = null
    private var retryRunnable: Runnable? = null
    private var retryAttempt = 0
    private var retryScheduled = false
    private var lastRequest: PlayRequest? = null
    private var state = PlaybackState.IDLE
    private var currentUrl: String? = null
    private var currentError: String? = null
    private var activeDecoder: String? = null
    private var videoWidth = 0
    private var videoHeight = 0

    @Volatile private var snapshotState = PlaybackState.IDLE
    @Volatile private var snapshotUrl: String? = null
    @Volatile private var snapshotPositionMs = 0L
    @Volatile private var snapshotDurationMs = 0L
    @Volatile private var snapshotBufferedMs = 0L
    @Volatile private var snapshotDecoder: String? = null
    @Volatile private var snapshotError: String? = null
    @Volatile private var snapshotVideoWidth = 0
    @Volatile private var snapshotVideoHeight = 0

    fun play(context: Context, request: PlayRequest) {
        mainHandler.post { playOnMain(context, request) }
    }

    fun pause() {
        mainHandler.post {
            player?.pause()
            if (player != null && state != PlaybackState.IDLE && state != PlaybackState.ERROR) {
                updateState(PlaybackState.PAUSED, "Paused")
            }
        }
    }

    fun resume() {
        mainHandler.post {
            player?.play()
            if (player != null && state != PlaybackState.IDLE && state != PlaybackState.ERROR) {
                updateState(PlaybackState.PLAYING, null)
            }
        }
    }

    fun stopAndReturnToConnection(context: Context) {
        mainHandler.post {
            stopOnMain()
            openConnectionScreen(context)
        }
    }

    fun seek(positionMs: Long) {
        mainHandler.post {
            val currentPlayer = player ?: return@post
            val duration = currentPlayer.duration
            val target = if (duration > 0) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
            currentPlayer.seekTo(target)
        }
    }

    fun setVolume(volume: Float) {
        mainHandler.post {
            player?.volume = volume.coerceIn(0f, 1f)
        }
    }

    fun attachPlayerView(playerView: PlayerView) {
        mainHandler.post {
            attachedPlayerView = playerView
            playerView.player = player
        }
    }

    fun detachPlayerView(playerView: PlayerView) {
        mainHandler.post {
            if (attachedPlayerView === playerView) {
                playerView.player = null
                attachedPlayerView = null
            }
        }
    }

    fun addUiListener(listener: UiListener) {
        mainHandler.post {
            uiListeners.add(listener)
            listener.onPlaybackStateChanged(state, stateMessage())
        }
    }

    fun removeUiListener(listener: UiListener) {
        uiListeners.remove(listener)
    }

    fun hasActivePlayback(): Boolean = state != PlaybackState.IDLE || player != null

    fun status(): PlaybackStatus {
        if (Looper.myLooper() == Looper.getMainLooper()) return statusOnMain()

        val result = AtomicReference<PlaybackStatus>()
        val latch = CountDownLatch(1)
        mainHandler.post {
            result.set(statusOnMain())
            latch.countDown()
        }
        if (latch.await(250, TimeUnit.MILLISECONDS)) {
            return result.get()
        }
        return PlaybackStatus(
            state = snapshotState,
            url = snapshotUrl,
            positionMs = snapshotPositionMs,
            durationMs = snapshotDurationMs,
            bufferedMs = snapshotBufferedMs,
            decoder = snapshotDecoder,
            error = snapshotError,
            videoWidth = snapshotVideoWidth,
            videoHeight = snapshotVideoHeight
        )
    }

    private fun playOnMain(context: Context, request: PlayRequest, resetRetry: Boolean = true) {
        if (request.url.isBlank()) return

        val existingView = attachedPlayerView
        val retainedRetryAttempt = retryAttempt
        stopOnMain()
        attachedPlayerView = existingView
        if (!resetRetry) retryAttempt = retainedRetryAttempt
        lastRequest = request
        currentUrl = request.url
        currentError = null
        activeDecoder = null
        videoWidth = 0
        videoHeight = 0
        if (resetRetry) retryAttempt = 0
        updateState(PlaybackState.CONNECTING, "Connecting…")

        val newPlayer = createPlayer()
        player = newPlayer
        if (existingView != null) existingView.player = newPlayer
        prepare(newPlayer, request)
        openPlayerScreen(context)
    }

    private fun createPlayer(): ExoPlayer {
        val renderersFactory = DefaultRenderersFactory(appContext)
            // MediaCodec hardware decoders are tried first; fallback is only for init failure.
            .setEnableDecoderFallback(true)
        val builder = ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)

        val exoPlayer = builder.build()
        exoPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        exoPlayer.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        exoPlayer.addListener(playerListener)
        exoPlayer.addAnalyticsListener(analyticsListener)
        return exoPlayer
    }

    private fun prepare(exoPlayer: ExoPlayer, request: PlayRequest) {
        val mediaItem = MediaItem.Builder()
            .setUri(request.url)
            .apply {
                when (normalizeType(request.type)) {
                    "hls" -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    "dash" -> setMimeType(MimeTypes.APPLICATION_MPD)
                }
            }
            .build()
        val mediaSource = createMediaSource(mediaItem, request)
        Log.i(TAG, "Preparing ${normalizeType(request.type)} stream: ${request.url}")
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
    }

    private fun createMediaSource(mediaItem: MediaItem, request: PlayRequest): MediaSource {
        val type = detectedType(request.url, request.type)
        if (type == "rtsp") {
            if (request.headers.isNotEmpty()) {
                Log.w(TAG, "HTTP headers ignored for RTSP stream")
            }
            return RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .createMediaSource(mediaItem)
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(request.headers)

        return when (type) {
            "hls" -> HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
            "dash" -> DashMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
            "progressive" -> ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
            else -> DefaultMediaSourceFactory(httpFactory).createMediaSource(mediaItem)
        }
    }

    private fun scheduleReconnect() {
        if (retryScheduled || lastRequest == null || retryAttempt >= MAX_RETRIES) {
            if (retryAttempt >= MAX_RETRIES) {
                updateState(PlaybackState.ERROR, currentError ?: "Stream unavailable")
            }
            return
        }

        val delayMs = when (retryAttempt) {
            0 -> 1_000L
            1 -> 2_000L
            else -> 5_000L
        }
        retryAttempt += 1
        retryScheduled = true
        updateState(PlaybackState.CONNECTING, "Reconnecting…")
        Log.i(TAG, "Reconnect attempt $retryAttempt scheduled in ${delayMs}ms")

        val runnable = Runnable {
            retryScheduled = false
            val request = lastRequest ?: return@Runnable
            val context = attachedPlayerView?.context ?: appContext
            playOnMain(context, request, resetRetry = false)
        }
        retryRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun handlePlayerError(error: PlaybackException) {
        val description = error.errorCodeName ?: error.message ?: "Playback error"
        currentError = description
        Log.e(TAG, "Network/playback error: $description", error)
        updateState(PlaybackState.ERROR, description)

        if (isAuthorizationError(error)) {
            Log.w(TAG, "Not retrying HTTP authorization error")
            return
        }
        scheduleReconnect()
    }

    private fun isAuthorizationError(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            val text = "${cause.javaClass.simpleName} ${cause.message.orEmpty()}"
            if (text.contains("401") || text.contains("403")) return true
            cause = cause.cause
        }
        return false
    }

    private fun stopOnMain() {
        retryRunnable?.let(mainHandler::removeCallbacks)
        retryRunnable = null
        retryScheduled = false
        retryAttempt = 0
        attachedPlayerView?.player = null
        attachedPlayerView = null
        player?.removeListener(playerListener)
        player?.removeAnalyticsListener(analyticsListener)
        player?.release()
        player = null
        lastRequest = null
        currentUrl = null
        currentError = null
        activeDecoder = null
        videoWidth = 0
        videoHeight = 0
        updateState(PlaybackState.IDLE, null)
        Log.i(TAG, "Playback stopped")
    }

    private fun openPlayerScreen(context: Context) {
        val intent = Intent(context, PlayerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun openConnectionScreen(context: Context) {
        val intent = Intent(context, ConnectionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun updateState(newState: PlaybackState, message: String?) {
        state = newState
        snapshotState = newState
        uiListeners.forEach { it.onPlaybackStateChanged(newState, message) }
    }

    private fun stateMessage(): String? = when (state) {
        PlaybackState.CONNECTING -> "Connecting…"
        PlaybackState.BUFFERING -> "Buffering…"
        PlaybackState.PAUSED -> "Paused"
        PlaybackState.ERROR -> currentError ?: "Stream unavailable"
        else -> null
    }

    private fun statusOnMain(): PlaybackStatus {
        val currentPlayer = player
        val status = PlaybackStatus(
            state = state,
            url = currentUrl,
            positionMs = currentPlayer?.currentPosition ?: 0L,
            durationMs = currentPlayer?.duration?.takeIf { it >= 0 } ?: 0L,
            bufferedMs = currentPlayer?.bufferedPosition ?: 0L,
            decoder = activeDecoder,
            error = currentError,
            videoWidth = videoWidth,
            videoHeight = videoHeight
        )
        snapshotState = status.state
        snapshotUrl = status.url
        snapshotPositionMs = status.positionMs
        snapshotDurationMs = status.durationMs
        snapshotBufferedMs = status.bufferedMs
        snapshotDecoder = status.decoder
        snapshotError = status.error
        snapshotVideoWidth = status.videoWidth
        snapshotVideoHeight = status.videoHeight
        return status
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> updateState(PlaybackState.BUFFERING, "Buffering…")
                Player.STATE_READY -> {
                    retryAttempt = 0
                    retryScheduled = false
                    currentError = null
                    if (player?.isPlaying == true || player?.playWhenReady == true) {
                        updateState(PlaybackState.PLAYING, null)
                        Log.i(TAG, "Playback started")
                    } else {
                        updateState(PlaybackState.PAUSED, "Paused")
                    }
                }
                Player.STATE_ENDED -> {
                    Log.i(TAG, "Playback ended")
                    updateState(PlaybackState.IDLE, null)
                }
                Player.STATE_IDLE -> if (state != PlaybackState.IDLE) {
                    updateState(PlaybackState.CONNECTING, "Connecting…")
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                retryAttempt = 0
                currentError = null
                updateState(PlaybackState.PLAYING, null)
                Log.i(TAG, "Playback is actively rendering")
            } else if (player?.playbackState == Player.STATE_READY) {
                updateState(PlaybackState.PAUSED, "Paused")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            videoWidth = videoSize.width
            videoHeight = videoSize.height
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            activeDecoder = decoderName
            snapshotDecoder = decoderName
            Log.i(TAG, "video/avc decoder initialized: $decoderName (init ${initializationDurationMs}ms)")
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            Log.i(TAG, "audio decoder initialized: $decoderName")
        }
    }

    companion object {
        private const val TAG = "ProjectorReceiver"
        private const val MAX_RETRIES = 3

        fun normalizeType(type: String): String = when (type.lowercase(Locale.US)) {
            "hls", "dash", "progressive", "rtsp" -> type.lowercase(Locale.US)
            else -> "auto"
        }

        fun detectedType(url: String, requestedType: String): String {
            val explicit = normalizeType(requestedType)
            if (explicit != "auto") return explicit
            if (url.lowercase(Locale.US).startsWith("rtsp://")) return "rtsp"
            val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
            return when {
                path.endsWith(".m3u8") -> "hls"
                path.endsWith(".mpd") -> "dash"
                path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") -> "progressive"
                else -> "auto"
            }
        }
    }
}

private class CopyOnWriteUiListeners {
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<PlaybackController.UiListener>()

    fun add(listener: PlaybackController.UiListener) {
        listeners.add(listener)
    }

    fun remove(listener: PlaybackController.UiListener) {
        listeners.remove(listener)
    }

    fun forEach(action: (PlaybackController.UiListener) -> Unit) {
        listeners.forEach(action)
    }
}
