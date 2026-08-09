package com.arrowsbot

import android.app.Application
import com.arrowsbot.capture.CaptureManager
import com.arrowsbot.state.BotEngine

class ArrowsBotApp : Application() {
    lateinit var captureManager: CaptureManager
        private set
    lateinit var botEngine: BotEngine
        private set

    override fun onCreate() {
        super.onCreate()
        captureManager = CaptureManager(this)
        botEngine = BotEngine(this, captureManager)
    }
}