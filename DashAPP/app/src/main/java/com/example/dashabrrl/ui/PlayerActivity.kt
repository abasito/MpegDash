package com.example.dashabrrl.ui

import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.os.Bundle
import android.view.View
import java.util.Locale
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.PlayerView
import com.example.dashabrrl.R
import com.example.dashabrrl.rl.RlInferenceEngine
import com.example.dashabrrl.rl.RlTrackSelection
import com.example.dashabrrl.telemetry.QosLogger
import com.example.dashabrrl.net.TappingDataSource
import com.example.dashabrrl.net.ByteTapRegistry
import java.io.File

class PlayerActivity : AppCompatActivity() {
  companion object {
    const val EXTRA_MODE = "mode"
    const val EXTRA_MPD_URL = "mpd_url"

    const val MODE_ADAPTIVE = "adaptive"
    const val MODE_FIXED = "fixed"
    const val MODE_RL = "rl"

    @Volatile
    private var simpleCache: SimpleCache? = null

    private fun getSimpleCache(context: android.content.Context): SimpleCache {
      val existing = simpleCache
      if (existing != null) return existing
      synchronized(PlayerActivity::class.java) {
        val again = simpleCache
        if (again != null) return again
        val cacheDir = File(context.cacheDir, "media_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(200L * 1024 * 1024) // 200 MB
        val dbProvider = StandaloneDatabaseProvider(context.applicationContext)
        val cache = SimpleCache(cacheDir, evictor, dbProvider)
        simpleCache = cache
        return cache
      }
    }
  }

  private lateinit var playerView: PlayerView
  private var player: ExoPlayer? = null
  private lateinit var trackSelector: DefaultTrackSelector
  private lateinit var qosLogger: QosLogger
  private var rlEngine: RlInferenceEngine? = null
  private var playbackMode: String = MODE_ADAPTIVE
  // Fixed mode helper state
  private var fixedSelectedSortedPos: Int = -1


  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_player)

    playerView = findViewById(R.id.playerView)

    val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ADAPTIVE
    val mpdUrl = intent.getStringExtra(EXTRA_MPD_URL) ?: ""
    playbackMode = mode

    initPlayer()
    validateAndStart(mpdUrl)

    // Always hide legacy bottom controls; quality will be under the settings icon.
    findViewById<View>(R.id.fixedControls)?.visibility = View.GONE

    // In Fixed mode, a normal click on the settings icon opens the resolution chooser.
    // (Only attach this handler in Fixed mode to avoid overriding the default settings menu otherwise.)
    if (playbackMode == MODE_FIXED) {
      playerView.post {
        val settingsBtnId = androidx.media3.ui.R.id.exo_settings
        playerView.findViewById<View?>(settingsBtnId)?.setOnClickListener {
          showResolutionChooser()
        }
      }
    }
  }

  private fun buildRenderersFactory(): RenderersFactory {
    val audioSink: AudioSink = DefaultAudioSink.Builder()
      .setEnableFloatOutput(true)
      .setEnableAudioTrackPlaybackParams(true)
      .build()

    return object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
      override fun buildAudioSink(
        context: android.content.Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
      ): AudioSink {
        // AC3 is supported by the platform on modern devices, and FFmpeg extension is added as a fallback
        return audioSink
      }
    }.setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
      .setEnableDecoderFallback(true)
  }

  private fun initPlayer() {
    val bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(this)
    com.example.dashabrrl.rl.RlState.clear()

    var selectionFactory: ExoTrackSelection.Factory =
      AdaptiveTrackSelection.Factory()

    if (playbackMode == MODE_RL) {
      try {
        val modelBytes = assets.open("abr_agent.onnx").use { it.readBytes() }
        rlEngine = RlInferenceEngine(modelBytes)
        selectionFactory = RlTrackSelection.Factory(rlEngine!!)
        Log.i("PlayerActivity", "RL Model loaded and selection factory created")
      } catch (e: Exception) {
        Log.e("PlayerActivity", "Failed to load RL model from assets", e)
      }
    }

    trackSelector = DefaultTrackSelector(this, selectionFactory)

    val loadControl = DefaultLoadControl.Builder()
      .setBufferDurationsMs(
        15000, // min buffer before start
        50000, // max buffer
        2500,  // playback rebuffer
        5000   // back buffer
      )
      .build()

    player = ExoPlayer.Builder(this, buildRenderersFactory())
      .setTrackSelector(trackSelector)
      .setLoadControl(loadControl)
      .setBandwidthMeter(bandwidthMeter)
      .build()
    playerView.player = player
  if (playbackMode == MODE_FIXED) {
      player?.addListener(object : androidx.media3.common.Player.Listener {
          override fun onTracksChanged(tracks: Tracks) {
              super.onTracksChanged(tracks)

              // Only auto-select once, or if nothing is selected yet
              if (fixedSelectedSortedPos != -1) return

              val group = getCurrentVideoGroup() ?: return
              val sorted = sortedVideoIndices(group)

              if (sorted.isEmpty()) return

              // Choose default fixed quality:
              // 0 = highest, (sorted.size - 1) = lowest, or a middle index if you prefer
              val defaultPos = 0 // highest quality

              if (applyFixedOverrideBySortedPosition(defaultPos)) {
                  qosLogger.logEvent(
                      "fixed_resolution_auto",
                      "sorted_index=$defaultPos"
                  )
              }
          }
      })
  }
    // Show CC button and auto-select text when only undetermined language is available.
    try {
      // PlayerView is a StyledPlayerView alias; this method is available in Media3 UI.
      playerView.setShowSubtitleButton(true)
    } catch (_: Throwable) { /* no-op if not available */ }

    // Configure track selection:
    // - Keep subtitle behavior
    // - Remove display / viewport based resolution caps for video
    player!!.trackSelectionParameters =
      player!!.trackSelectionParameters
        .buildUpon()
        .setSelectUndeterminedTextLanguage(true)
        .clearVideoSizeConstraints()
        .clearViewportSizeConstraints()
        .build()


      // QoS logging
    qosLogger = QosLogger(this, bandwidthMeter)
    qosLogger.attach(player!!)
    player?.addAnalyticsListener(qosLogger)

    // Verbose Media3 logging (use Media3 Log, not android.util.Log)
    androidx.media3.common.util.Log.setLogLevel(
      androidx.media3.common.util.Log.LOG_LEVEL_ALL
    )

    // Baseline detailed logs
    // EventLogger unavailable in this setup; using focused custom listener below

    // Focused debug listener to Logcat
    player?.addAnalyticsListener(object : AnalyticsListener {
      override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        val ct = loadEventInfo.responseHeaders["Content-Type"]?.firstOrNull()
        Log.d("DBG", "load_started type=${mediaLoadData.dataType} trackType=${mediaLoadData.trackType} uri=${loadEventInfo.dataSpec.uri} ct=${ct}")
      }
      override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        Log.d("DBG", "load_completed type=${mediaLoadData.dataType} bytes=${loadEventInfo.bytesLoaded}")
      }
      override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
        val ct = loadEventInfo.responseHeaders["Content-Type"]?.firstOrNull()
        val uriStr = loadEventInfo.dataSpec.uri.toString()
        Log.e("DBG", "load_error type=${mediaLoadData.dataType} trackType=${mediaLoadData.trackType} uri=${uriStr} ct=${ct} err=${error}")
        // Dump last bytes read for this URI to confirm NAL length prefix
        ByteTapRegistry.hexTail(uriStr)?.let { hex ->
          Log.e("DBG", "last_bytes[$uriStr] ${hex}")
        }

        // In fixed mode, if a high-quality representation is missing on the server (404),
        // gracefully fall back to the next lower quality.
        if (playbackMode == MODE_FIXED && mediaLoadData.trackType == C.TRACK_TYPE_VIDEO && error is HttpDataSource.InvalidResponseCodeException && error.responseCode == 404) {
          degradeFixedSelection()
        }
      }
      override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: MediaLoadData) {
        val f = mediaLoadData.trackFormat
        Log.i("DBG", "format_changed id=${f?.id} codecs=${f?.codecs} mime=${f?.sampleMimeType} w=${f?.width} h=${f?.height} initBytes=${f?.initializationData?.sumOf { it.size }}")
      }
      override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
        Log.e("DBG", "player_error ${error.errorCodeName} cause=${error.cause}")
      }
    })
  }

  private fun buildDashSource(url: String): MediaSource {
    val mediaItem = MediaItem.Builder()
      .setUri(Uri.parse(url))
      .setMimeType(MimeTypes.APPLICATION_MPD)
      .build()

    val client = okhttp3.OkHttpClient.Builder()
      .followRedirects(true)
      .followSslRedirects(true)
      .connectTimeout(java.time.Duration.ofSeconds(5))
      .readTimeout(java.time.Duration.ofSeconds(10))
      .build()

    val baseFactory: DataSource.Factory = OkHttpDataSource.Factory(client)
    val tappingFactory: DataSource.Factory = TappingDataSource.Factory(baseFactory)

    val cache = getSimpleCache(applicationContext)

    val cacheFactory = CacheDataSource.Factory()
      .setCache(cache)
      .setUpstreamDataSourceFactory(tappingFactory)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    return DashMediaSource.Factory(cacheFactory).createMediaSource(mediaItem)
  }

  private fun prepareAndPlay(url: String) {
    val source = buildDashSource(url)
    player?.setMediaSource(source)
    player?.prepare()
    player?.playWhenReady = true
  }

  private fun isNetworkConnected(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java)
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  private fun validateAndStart(url: String) {
    if (url.isBlank()) {
      Toast.makeText(this, "MPD URL is empty", Toast.LENGTH_SHORT).show()
      return
    }
    if (!isNetworkConnected()) {
      Toast.makeText(this, "No network connection", Toast.LENGTH_LONG).show()
      return
    }

    Toast.makeText(this, "Checking connection...", Toast.LENGTH_SHORT).show()

    Thread {
      var connected = false
      var mpdOk = false
      var httpCode: Int? = null
      var exceptionMsg: String? = null
      try {
        val urlObj = java.net.URL(url)
        val conn = (urlObj.openConnection() as java.net.HttpURLConnection)
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.instanceFollowRedirects = true
        conn.requestMethod = "GET"
        conn.connect()
        val code = conn.responseCode
        connected = (code in 200..299)
        httpCode = code
        if (connected) {
          val stream = conn.inputStream
          val buf = ByteArray(4096)
          val read = stream.read(buf)
          if (read > 0) {
            val head = String(buf, 0, read, Charsets.UTF_8)
            mpdOk = head.contains("<MPD", ignoreCase = true)
          }
          stream.close()
        }
        conn.disconnect()
      } catch (t: Throwable) {
        exceptionMsg = t.message ?: t.javaClass.simpleName
      }

      runOnUiThread {
        if (!connected) {
          val msg = exceptionMsg ?: (
            if (httpCode != null) "Failed to connect: HTTP ${'$'}httpCode" else "Failed to connect"
          )
          Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
          return@runOnUiThread
        }
        Toast.makeText(this, "Connected to server", Toast.LENGTH_SHORT).show()
        if (!mpdOk) {
          Toast.makeText(this, "Fetched URL but MPD not detected. Check path.", Toast.LENGTH_LONG).show()
          // Still proceed to try playback; some servers may not return MPD early in first bytes
        } else {
          Toast.makeText(this, "MPD OK. Starting playback...", Toast.LENGTH_SHORT).show()
        }

        prepareAndPlay(url)
      }
    }.start()
  }

  private fun showResolutionChooser() {
    val group = getCurrentVideoGroup()
    if (group == null) {
      Toast.makeText(this, "No video tracks", Toast.LENGTH_SHORT).show()
      return
    }

    val sorted = sortedVideoIndices(group)
    val entries = sorted.map { idx ->
      val f = group.getTrackFormat(idx)
      val wh = "${f.width}x${f.height}"
      val mbps = formatMbitPerSec(f.bitrate)
      "$wh  $mbps"
    }

    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
      .setTitle("Pick resolution")
      .setItems(entries.toTypedArray()) { _, which ->
        applyFixedOverrideBySortedPosition(which)
        qosLogger.logEvent("fixed_resolution_pick", "sorted_index=$which label=${entries[which]}")
      }
      .show()
  }

  private fun getCurrentVideoGroup(): Tracks.Group? {
    val t = player?.currentTracks ?: return null
    return t.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
  }

  private fun sortedVideoIndices(group: Tracks.Group): List<Int> {
    val indices = (0 until group.length).toList()
    return indices.sortedWith(compareByDescending<Int> { idx ->
      val f = group.getTrackFormat(idx)
      val h = if (f.height != Format.NO_VALUE) f.height else 0
      h
    }.thenByDescending { idx ->
      val f = group.getTrackFormat(idx)
      val br = if (f.bitrate != Format.NO_VALUE) f.bitrate else 0
      br
    })
  }

  private fun formatMbitPerSec(bitrate: Int): String {
    if (bitrate <= 0 || bitrate == Format.NO_VALUE) return "– Mbit/s"
    val mbps = bitrate / 1_000_000.0
    return String.format(Locale.US, "%.1f Mbit/s", mbps)
  }

  private fun applyFixedOverrideBySortedPosition(sortedPos: Int): Boolean {
    val group = getCurrentVideoGroup() ?: return false
    val sorted = sortedVideoIndices(group)
    if (sortedPos !in sorted.indices) return false
    val trackIndex = sorted[sortedPos]
    val override = TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
    player?.trackSelectionParameters = player?.trackSelectionParameters
      ?.buildUpon()
      ?.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
      ?.addOverride(override)
      ?.build() ?: TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
    fixedSelectedSortedPos = sortedPos
    return true
  }

  private fun degradeFixedSelection() {
    val group = getCurrentVideoGroup() ?: return
    val sorted = sortedVideoIndices(group)
    // If we don't know the current sorted position, infer it from the selected track.
    if (fixedSelectedSortedPos !in sorted.indices) {
      val currentPos = sorted.indexOfFirst { idx -> group.isTrackSelected(idx) }
      fixedSelectedSortedPos = if (currentPos >= 0) currentPos else sorted.indexOfLast { true }
    }
    val nextPos = fixedSelectedSortedPos - 1
    if (nextPos >= 0) {
      if (applyFixedOverrideBySortedPosition(nextPos)) {
        Toast.makeText(this, "Selected quality unavailable. Falling back.", Toast.LENGTH_SHORT).show()
        qosLogger.logEvent("fixed_resolution_fallback", "to_sorted_index=$nextPos")
      }
    }
  }

  override fun onStart() {
    super.onStart()
    playerView.onResume()
  }

  override fun onStop() {
    super.onStop()
    playerView.onPause()
    rlEngine?.close()
    player?.release()
    player = null
  }
}
