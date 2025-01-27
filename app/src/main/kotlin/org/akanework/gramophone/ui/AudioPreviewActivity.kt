package org.akanework.gramophone.ui

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.net.toFile
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.getBooleanStrict
import org.akanework.gramophone.logic.hasScopedStorageV1
import org.akanework.gramophone.logic.playOrPause
import org.akanework.gramophone.logic.startAnimation
import org.akanework.gramophone.logic.utils.CalculationUtils.convertDurationToTimeStamp
import org.akanework.gramophone.logic.utils.exoplayer.GramophoneMediaSourceFactory
import org.akanework.gramophone.logic.utils.exoplayer.GramophoneRenderFactory
import org.akanework.gramophone.ui.components.FullBottomSheet.Companion.SLIDER_UPDATE_INTERVAL
import org.akanework.gramophone.ui.components.SquigglyProgress
import uk.akane.libphonograph.getColumnIndexOrNull

@OptIn(UnstableApi::class)
class AudioPreviewActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var d: AlertDialog
    private lateinit var player: ExoPlayer
    private lateinit var audioTitle: TextView
    private lateinit var artistTextView: TextView
    private lateinit var currentPositionTextView: TextView
    private lateinit var durationTextView: TextView
    private lateinit var albumArt: ImageView
    private lateinit var timeSlider: Slider
    private lateinit var timeSeekbar: SeekBar
    private lateinit var playPauseButton: MaterialButton
    private lateinit var progressDrawable: SquigglyProgress
    private lateinit var openIcon: ImageView
    private lateinit var openText: TextView
    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())
    private var runnableRunning = false
    private var isUserTracking = false
    private val updateSliderRunnable = object : Runnable {
        override fun run() {
            val duration = player.contentDuration.let { if (it == C.TIME_UNSET) null else it }
                ?: player.mediaMetadata.durationMs
            if (duration != null) {
                timeSlider.valueTo = duration.toFloat().coerceAtLeast(1f)
                timeSeekbar.max = duration.toInt()
                durationTextView.text = convertDurationToTimeStamp(
                    player.contentDuration.let { if (it == C.TIME_UNSET) null else it }
                        ?: player.mediaMetadata.durationMs ?: 0)
            }
            val currentPosition = player.currentPosition.toFloat().coerceAtMost(timeSlider.valueTo)
                .coerceAtLeast(timeSlider.valueFrom)
            if (!isUserTracking) {
                timeSlider.value = currentPosition
                timeSeekbar.progress = currentPosition.toInt()
                currentPositionTextView.text = convertDurationToTimeStamp(currentPosition.toLong())
            }
            if (runnableRunning) handler.postDelayed(this, 100)
        }
    }

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "default_progress_bar" -> updateSliderVisibility()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        d = MaterialAlertDialogBuilder(this)
            .setView(R.layout.activity_audio_preview)
            .setOnDismissListener {
                runnableRunning = false
                player.release()
                handler.postDelayed(this::finish, 200)
            }
            .show()
        audioTitle = d.findViewById(R.id.title_text_view)!!
        artistTextView = d.findViewById(R.id.artist_text_view)!!
        currentPositionTextView = d.findViewById(R.id.current_position_text_view)!!
        durationTextView = d.findViewById(R.id.duration_text_view)!!
        albumArt = d.findViewById(R.id.album_art)!!
        timeSlider = d.findViewById(R.id.time_slider)!!
        timeSeekbar = d.findViewById(R.id.slider_squiggly)!!
        playPauseButton = d.findViewById(R.id.play_pause_replay_button)!!
        openIcon = d.findViewById(R.id.open_icon)!!
        openText = d.findViewById(R.id.open_text_view)!!

        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)
        updateSliderVisibility()

        val seekBarProgressWavelength =
            resources.getDimensionPixelSize(R.dimen.media_seekbar_progress_wavelength).toFloat()
        val seekBarProgressAmplitude =
            resources.getDimensionPixelSize(R.dimen.media_seekbar_progress_amplitude).toFloat()
        val seekBarProgressPhase =
            resources.getDimensionPixelSize(R.dimen.media_seekbar_progress_phase).toFloat()
        val seekBarProgressStrokeWidth =
            resources.getDimensionPixelSize(R.dimen.media_seekbar_progress_stroke_width).toFloat()

        timeSeekbar.progressDrawable = SquigglyProgress().also {
            progressDrawable = it
            it.waveLength = seekBarProgressWavelength
            it.lineAmplitude = seekBarProgressAmplitude
            it.phaseSpeed = seekBarProgressPhase
            it.strokeWidth = seekBarProgressStrokeWidth
            it.transitionEnabled = true
            it.animate = false
        }

        player = ExoPlayer.Builder(
            this,
            GramophoneRenderFactory(this)
                .setEnableAudioFloatOutput(
                    prefs.getBooleanStrict("floatoutput", false)
                )
                .setEnableDecoderFallback(true)
                .setEnableAudioTrackPlaybackParams( // hardware/system-accelerated playback speed
                    prefs.getBooleanStrict("ps_hardware_acc", true)
                )
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER),
            GramophoneMediaSourceFactory(this)
        )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), true
            ).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                runnableRunning = isPlaying
                handler.post(updateSliderRunnable)
                updatePlayPauseButton()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                handler.post(updateSliderRunnable)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return
                updateMediaMetadata(player)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateMediaMetadata(player)
            }
        })
        playPauseButton.setOnClickListener {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            player.playOrPause()
        }

        timeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                player.seekTo(value.toLong())
                currentPositionTextView.text = convertDurationToTimeStamp(value.toLong())
            }
        }

        timeSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentPositionTextView.text = convertDurationToTimeStamp(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTracking = true
                progressDrawable.animate = false
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    player.seekTo(it.progress.toLong())
                }
                isUserTracking = false
                progressDrawable.animate = player.isPlaying
            }
        })
        openIcon.setOnClickListener(this)
        openText.setOnClickListener(this)

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                val queryUri = if (uri.scheme == "file")
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else if (uri.scheme == "content" && uri.pathSegments.firstOrNull() == "media")
                    uri
                else if (hasScopedStorageV1() && uri.scheme == "content")
                    try {
                        MediaStore.getMediaUri(this, uri)
                    } catch (e: Exception) {
                        if (e.message != "Provider for this Uri is not supported.")
                            throw e
                        null
                    }
                else null
                val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DURATION)
                val cursor = if (queryUri != null) contentResolver.query(
                    queryUri, projection, if (uri.scheme == "file")
                        MediaStore.Audio.Media.DATA + " = ?" else null,
                    if (uri.scheme == "file") arrayOf(uri.toFile().absolutePath) else null, null
                ) else null
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                if (cursor?.moveToFirst() == true) {
                    val id = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    )
                    val durationMs =
                        cursor.getColumnIndexOrNull(MediaStore.Audio.Media.DURATION)?.let {
                            cursor.getLong(it)
                        }
                    mediaItem.setMediaId(id.toString())
                    mediaItem.setMediaMetadata(MediaMetadata.Builder()
                        .setDurationMs(durationMs)
                        .build())
                    openIcon.visibility = View.VISIBLE
                    openText.visibility = View.VISIBLE
                } else {
                    openIcon.visibility = View.GONE
                    openText.visibility = View.GONE
                }
                player.setMediaItem(mediaItem.build())
                player.prepare()
                player.play()
                cursor?.close()
            }
        }
    }

    override fun onClick(v: View?) {
        player.currentMediaItem?.mediaId?.let {
            if (it != MediaItem.DEFAULT_MEDIA_ID)
                it else null
        }?.let { id ->
            startActivity(Intent(this, MainActivity::class.java).also {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                it.putExtra(MainActivity.PLAYBACK_AUTO_PLAY_ID, id)
                player.contentPosition.let { pos ->
                    if (pos != C.TIME_UNSET)
                        it.putExtra(MainActivity.PLAYBACK_AUTO_PLAY_POSITION, pos)
                }
            })
        }
    }

    override fun onPause() {
        super.onPause()
        player.playWhenReady = false
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        if (d.isShowing)
            d.dismiss()
        super.onDestroy()
    }

    private fun updateSliderVisibility() {
        if (prefs.getBooleanStrict("default_progress_bar", false)) {
            timeSlider.visibility = View.VISIBLE
            timeSeekbar.visibility = View.GONE
        } else {
            timeSlider.visibility = View.GONE
            timeSeekbar.visibility = View.VISIBLE
        }
    }

    private fun updatePlayPauseButton() {
        if (player.isPlaying) {
            if (playPauseButton.getTag(R.id.play_next) as Int? != 1) {
                playPauseButton.icon = AppCompatResources.getDrawable(this, R.drawable.play_anim)
                playPauseButton.icon.startAnimation()
                playPauseButton.setTag(R.id.play_next, 1)
            }
            if (!isUserTracking) {
                progressDrawable.animate = true
            }
            if (!runnableRunning) {
                handler.postDelayed(updateSliderRunnable, SLIDER_UPDATE_INTERVAL)
                runnableRunning = true
            }
        } else if (player.playbackState != Player.STATE_BUFFERING) {
            if (playPauseButton.getTag(R.id.play_next) as Int? != 2) {
                playPauseButton.icon =
                    AppCompatResources.getDrawable(this, R.drawable.pause_anim)
                playPauseButton.icon.startAnimation()
                playPauseButton.setTag(R.id.play_next, 2)
            }
            if (!isUserTracking) {
                progressDrawable.animate = false
            }
        }
    }

    private fun updateMediaMetadata(player: Player) {
        audioTitle.text = player.mediaMetadata.title ?: getString(R.string.unknown_title)
        artistTextView.text = player.mediaMetadata.artist ?: getString(R.string.unknown_artist)
        player.mediaMetadata.artworkData?.let {
            val bitmap = BitmapFactory.decodeByteArray(it, 0, it.size)
            albumArt.setImageBitmap(bitmap)
        } ?: run {
            albumArt.setImageResource(R.drawable.ic_default_cover)
        }
    }
}
