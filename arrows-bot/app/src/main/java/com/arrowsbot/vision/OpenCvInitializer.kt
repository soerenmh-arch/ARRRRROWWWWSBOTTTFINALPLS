package com.arrowsbot.vision

import org.opencv.android.OpenCVLoader

object OpenCvInitializer {
    @Volatile
    var loaded: Boolean = false
        private set

    fun initialize(): Boolean {
        loaded = OpenCVLoader.initLocal()
        return loaded
    }
}