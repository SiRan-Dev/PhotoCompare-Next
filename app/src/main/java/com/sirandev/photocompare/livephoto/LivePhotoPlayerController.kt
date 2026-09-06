package com.sirandev.photocompare.livephoto

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Single ExoPlayer instance for Live Photo playback (long-press to play, release to stop).
 * Owned by the compare screen; [release] must be called when the screen goes away.
 */
class LivePhotoPlayerController(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext).build()

    init {
        // play exactly once per long-press; the frozen last frame stays visible until release
        player.repeatMode = Player.REPEAT_MODE_OFF
    }

    fun play(file: File) {
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
    }

    fun stop() {
        player.pause()
        player.seekTo(0)
    }

    fun release() {
        player.release()
    }
}
