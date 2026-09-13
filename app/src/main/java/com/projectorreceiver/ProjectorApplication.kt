package com.projectorreceiver

import android.app.Application

class ProjectorApplication : Application() {
    lateinit var playbackController: PlaybackController
        private set

    lateinit var controlServer: ControlServer
        private set

    override fun onCreate() {
        super.onCreate()
        playbackController = PlaybackController(this)
        controlServer = ControlServer(this, playbackController)
        controlServer.start()
    }

    override fun onTerminate() {
        controlServer.stop()
        super.onTerminate()
    }
}
