package com.example.dashabrrl.telemetry

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import com.example.dashabrrl.net.ByteTapRegistry
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.BandwidthMeter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QosLogger(private val context: Context, private val bandwidthMeter: BandwidthMeter) : AnalyticsListener {
  private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
  private val outFile: File by lazy {
    File(context.getExternalFilesDir(null), "qos_log.csv")
  }
  private val eventsFile: File by lazy {
    File(context.getExternalFilesDir(null), "events_log.csv")
  }
  private var writer: FileWriter? = null
  private var eventsWriter: FileWriter? = null
  private var playerRef: Player? = null

  fun attach(player: Player) {
    playerRef = player
  }

  private fun writeHeaderIfNeeded() {
    if (writer == null) {
      writer = FileWriter(outFile, true)
      if (outFile.length() == 0L) {
        writer?.appendLine("time,state,position_ms,playing,video_bitrate,video_width,video_height,buffer_ms,bandwidth_bps,dropped_frames")
        writer?.flush()
      }
    }
    if (eventsWriter == null) {
      eventsWriter = FileWriter(eventsFile, true)
      if (eventsFile.length() == 0L) {
        eventsWriter?.appendLine("time,event,details")
        eventsWriter?.flush()
      }
    }
  }

  override fun onPlaybackStateChanged(_eventTime: AnalyticsListener.EventTime, state: Int) {
    log()
  }

  override fun onRenderedFirstFrame(_eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
    log()
  }

  override fun onVideoSizeChanged(_eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
    log()
  }

  override fun onBandwidthEstimate(
    eventTime: AnalyticsListener.EventTime,
    totalLoadTimeMs: Int,
    totalBytesLoaded: Long,
    bitrateEstimate: Long
  ) { log() }

  override fun onPlayerError(_eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
    log()
    logEvent("player_error", "${error.errorCodeName}: ${error.message}")
  }

  private fun log() {
    writeHeaderIfNeeded()
    val player = playerRef ?: return
    val tracks = player.currentTracks
    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    var videoBitrate = -1
    var videoWidth = -1
    var videoHeight = -1
    if (videoGroups.isNotEmpty()) {
      val g = videoGroups.first()
      for (i in 0 until g.length) {
        if (g.isTrackSelected(i)) {
          val f = g.getTrackFormat(i)
          videoBitrate = f.bitrate
          videoWidth = f.width
          videoHeight = f.height
          break
        }
      }
    }
    val bandwidth = bandwidthMeter.bitrateEstimate
    val bufferMs = player.totalBufferedDuration.toInt()
    val positionMs = player.currentPosition
    val state = when (player.playbackState) {
      Player.STATE_IDLE -> "IDLE"
      Player.STATE_BUFFERING -> "BUFFERING"
      Player.STATE_READY -> "READY"
      Player.STATE_ENDED -> "ENDED"
      else -> "UNKNOWN"
    }
    val dropped = (player as? ExoPlayer)?.videoDecoderCounters?.droppedBufferCount ?: -1

    val line = listOf(
      sdf.format(Date()),
      state,
      positionMs,
      player.isPlaying,
      videoBitrate,
      videoWidth,
      videoHeight,
      bufferMs,
      bandwidth,
      dropped
    ).joinToString(",")

    writer?.appendLine(line)
    writer?.flush()
  }

  fun logEvent(event: String, details: String) {
    writeHeaderIfNeeded()
    eventsWriter?.appendLine("${sdf.format(Date())},${event},${details}")
    eventsWriter?.flush()
  }

  // Track and format change logging
  override fun onVideoInputFormatChanged(
    eventTime: AnalyticsListener.EventTime,
    format: androidx.media3.common.Format,
    decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
  ) {
    logEvent("video_input_format", "bitrate=${format.bitrate}, size=${format.width}x${format.height}")
  }

  override fun onTracksChanged(
    eventTime: AnalyticsListener.EventTime,
    tracks: androidx.media3.common.Tracks
  ) {
    logEvent("tracks_changed", "video_selected=${tracks.groups.any { it.type==C.TRACK_TYPE_VIDEO && (0 until it.length).any(it::isTrackSelected) }}")
  }

  override fun onDownstreamFormatChanged(
    eventTime: AnalyticsListener.EventTime,
    mediaLoadData: MediaLoadData
  ) {
    val f = mediaLoadData.trackFormat
    val type = trackTypeName(mediaLoadData.trackType)
    logEvent(
      "downstream_format",
      "type=${type} id=${f?.id} codecs=${f?.codecs} mime=${f?.sampleMimeType} w=${f?.width} h=${f?.height} initBytes=${f?.initializationData?.sumOf { it.size }}"
    )
  }

  // Network load events
  override fun onLoadStarted(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData
  ) {
    logEvent(
      "load_started",
      "type=${dataTypeName(mediaLoadData.dataType)} trackType=${trackTypeName(mediaLoadData.trackType)} uri=${loadEventInfo.uri}"
    )
  }

  override fun onLoadCompleted(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData
  ) {
    val ct = loadEventInfo.responseHeaders["Content-Type"]?.firstOrNull()
    logEvent(
      "load_completed",
      "type=${dataTypeName(mediaLoadData.dataType)} trackType=${trackTypeName(mediaLoadData.trackType)} dur_ms=${loadEventInfo.loadDurationMs} bytes=${loadEventInfo.bytesLoaded} ct=${ct ?: ""} uri=${loadEventInfo.uri}"
    )
  }

  override fun onLoadError(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData,
    error: IOException,
    wasCanceled: Boolean
  ) {
    val http = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
    val ct = loadEventInfo.responseHeaders["Content-Type"]?.firstOrNull()
    val uriStr = loadEventInfo.dataSpec.uri.toString()
    val tail = ByteTapRegistry.hexTail(uriStr, 64) ?: ""
    logEvent(
      "load_error",
      "type=${dataTypeName(mediaLoadData.dataType)} trackType=${trackTypeName(mediaLoadData.trackType)} code=${http} ct=${ct ?: ""} err=${error.javaClass.simpleName}:${error.message} uri=${uriStr} tail=${tail}"
    )
  }

  private fun dataTypeName(t: Int): String = when (t) {
    // Common types mapping for readability
    4 -> "MANIFEST"
    3 -> "DRM"
    2 -> "INIT"
    1 -> "MEDIA"
    else -> t.toString()
  }

  private fun trackTypeName(t: Int): String = when (t) {
    C.TRACK_TYPE_VIDEO -> "VIDEO"
    C.TRACK_TYPE_AUDIO -> "AUDIO"
    C.TRACK_TYPE_TEXT -> "TEXT"
    C.TRACK_TYPE_METADATA -> "META"
    else -> t.toString()
  }
}
