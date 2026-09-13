package com.projectorreceiver

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.ui.PlayerView

class PlayerActivity : android.app.Activity(), PlaybackController.UiListener {
    private lateinit var playerView: PlayerView
    private lateinit var statusView: TextView
    private lateinit var playbackController: PlaybackController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        statusView = findViewById(R.id.player_status)
        playbackController = (application as ProjectorApplication).playbackController
        playbackController.attachPlayerView(playerView)
        playbackController.addUiListener(this)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        if (::playbackController.isInitialized) playbackController.attachPlayerView(playerView)
    }

    override fun onPlaybackStateChanged(state: PlaybackState, message: String?) {
        val showStatus = state != PlaybackState.PLAYING && state != PlaybackState.IDLE
        statusView.text = message.orEmpty()
        statusView.visibility = if (showStatus) View.VISIBLE else View.GONE
    }

    private fun hideSystemUi() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        Log.i(TAG, "Playback stopped by user")
        playbackController.stopAndReturnToConnection(this)
        finish()
    }

    override fun onDestroy() {
        if (::playbackController.isInitialized) {
            playbackController.removeUiListener(this)
            playbackController.detachPlayerView(playerView)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ProjectorReceiver"
    }
}
