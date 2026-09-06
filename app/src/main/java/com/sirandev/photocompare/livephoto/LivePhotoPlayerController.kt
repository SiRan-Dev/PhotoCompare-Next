package com.sirandev.photocompare.livephoto

import android.content.Context
import android.net.Uri
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.sirandev.photocompare.ui.compare.PaneSide
import java.io.File

/**
 * One ExoPlayer per compare pane so that both live photos can play simultaneously on a
 * long-press. Playback runs exactly once per trigger (REPEAT_MODE_OFF) and is muted by
 * default; owned by the compare screen — [release] must be called when the screen goes away.
 *
 * Video output goes through a plain [TextureView] that the compare pane binds via
 * [attachView]; the pane applies the still image's transform to that view, so the motion
 * overlay matches the photo 1:1 (a PlayerView is deliberately NOT used — its SurfaceView
 * renders black inside Compose interop, and it would bypass the pane's zoom transform).
 */
class LivePhotoPlayerController(context: Context) {

    private val players: Map<PaneSide, ExoPlayer> = PaneSide.entries.associateWith { side ->
        ExoPlayer.Builder(context.applicationContext).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            volume = 0f // muted by default
        }
    }

    fun attachView(side: PaneSide, view: TextureView) {
        players.getValue(side).setVideoTextureView(view)
    }

    fun detachView(side: PaneSide, view: TextureView) {
        players.getValue(side).clearVideoTextureView(view)
    }

    fun play(side: PaneSide, file: File) {
        players.getValue(side).apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            seekTo(0)
            prepare()
            playWhenReady = true
        }
    }

    fun stopAll() {
        players.values.forEach { player ->
            player.pause()
            player.seekTo(0)
        }
    }

    fun release() {
        players.values.forEach { it.release() }
    }
}
