package com.projectorreceiver

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import java.util.Locale

class ConnectionActivity : android.app.Activity() {
    private lateinit var preferences: StreamPreferences
    private lateinit var urlInput: EditText
    private lateinit var connectButton: Button
    private lateinit var autoConnectCheckbox: CheckBox
    private lateinit var statusMessage: TextView
    private lateinit var serverStatus: TextView
    private lateinit var serverEndpoint: TextView
    private lateinit var serverDetails: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_connection)

        preferences = StreamPreferences(this)
        urlInput = findViewById(R.id.url_input)
        connectButton = findViewById(R.id.connect_button)
        autoConnectCheckbox = findViewById(R.id.auto_connect_checkbox)
        statusMessage = findViewById(R.id.status_message)
        serverStatus = findViewById(R.id.server_status)
        serverEndpoint = findViewById(R.id.server_endpoint)
        serverDetails = findViewById(R.id.server_details)

        (application as ProjectorApplication).controlServer.start()
        updateServerDetails()
        window.decorView.postDelayed({ updateServerDetails() }, 250L)

        urlInput.setText(preferences.lastUrl)
        autoConnectCheckbox.isChecked = preferences.autoConnect

        connectButton.setOnClickListener { connectFromInput() }
        autoConnectCheckbox.setOnCheckedChangeListener { _, checked ->
            preferences.autoConnect = checked
        }
        urlInput.setOnEditorActionListener { _, actionId, event ->
            val isDone = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            if (isDone) {
                connectFromInput()
                true
            } else {
                false
            }
        }

        // Keep the appliance ready for D-pad input without opening a software keyboard.
        urlInput.requestFocus()

        if (savedInstanceState == null && preferences.autoConnect && preferences.lastUrl.isNotBlank()) {
            window.decorView.post { connectFromInput() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::serverStatus.isInitialized) updateServerDetails()
    }

    private fun updateServerDetails() {
        val server = (application as ProjectorApplication).controlServer
        val ip = NetworkUtils.localIpv4Address()
        serverStatus.text = getString(
            if (server.isRunning()) R.string.server_ready else R.string.server_starting
        )
        serverStatus.setTextColor(getColor(if (server.isRunning()) R.color.accent else R.color.text_muted))
        serverEndpoint.text = server.controlUrl() ?: getString(R.string.server_endpoint_unavailable)
        serverDetails.text = if (ip == null) {
            getString(R.string.server_network_unavailable)
        } else {
            getString(R.string.server_ip_port, ip, ControlServer.PORT)
        }
    }

    private fun connectFromInput() {
        val streamUrl = urlInput.text.toString().trim()
        val parsedUri = runCatching { streamUrl.toUri() }.getOrNull()
        val scheme = parsedUri?.scheme?.lowercase(Locale.US)
        val isSupportedStream = scheme == "rtsp" || scheme == "http" || scheme == "https"
        val hasHost = !parsedUri?.host.isNullOrBlank()

        if (streamUrl.isBlank()) {
            showError(getString(R.string.status_invalid_url))
            return
        }
        if (!isSupportedStream || !hasHost) {
            showError(getString(R.string.status_invalid_scheme))
            return
        }

        preferences.lastUrl = streamUrl
        statusMessage.text = getString(R.string.player_connecting)
        statusMessage.setTextColor(getColor(R.color.text_muted))
        (application as ProjectorApplication).playbackController.play(this, PlayRequest(streamUrl))
    }

    private fun showError(message: String) {
        statusMessage.text = message
        statusMessage.setTextColor(getColor(R.color.error))
        urlInput.requestFocus()
    }

}
