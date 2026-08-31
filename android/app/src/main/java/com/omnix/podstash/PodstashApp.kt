package com.omnix.podstash

import android.app.Application
import androidx.media3.exoplayer.ExoPlayer
import com.omnix.podstash.data.Store
import com.omnix.podstash.work.AutoScanScheduler

class PodstashApp : Application() {
    lateinit var store: Store
        private set
    lateinit var player: ExoPlayer
        private set

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        player = ExoPlayer.Builder(this).build()
        AutoScanScheduler.ensure(this)
    }
}
