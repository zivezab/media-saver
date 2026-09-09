package com.mediasaver

import android.app.Application

class MediaSaverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Unpacking the bundled Python runtime takes a few seconds on first
        // launch, so it starts as early as possible and the UI waits on it.
        Extractor.start(this)
        DownloadService.createChannel(this)
        DownloadHistory.load(this)
    }
}
