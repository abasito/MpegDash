package com.example.dashabrrl.telemetry

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.Format
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.example.dashabrrl.net.ByteTapRegistry
import java.io.IOException
import java.util.ArrayDeque

class QosLogger(
  private val context: Context,
  private val bandwidthMeter: BandwidthMeter,
  private val downloadLogger: DownloadCsvLogger
) : AnalyticsListener {
  private var playerRef: Player? = null
  private var lastPlaybackState: Int = Player.STATE_IDLE
  private var freezeStartRealtimeMs: Long? = null
  private val pendingFreezeDurationsMs: ArrayDeque<Long> = ArrayDeque()

  private data class PendingSegment(
    val startMs: Long,
    val trackType: String,
    val format: Format?,
    val freezeSec: Double
  )

  private val pendingSegments: MutableList<PendingSegment> = mutableListOf()
  private val mainHandler = Handler(Looper.getMainLooper())
  private var segmentPollerStarted: Boolean = false

  private val segmentPollRunnable = object : Runnable {
    override fun run() {
      try {
        dispatchPlayedSegments()
      } finally {
        if (segmentPollerStarted) {
          mainHandler.postDelayed(this, 200L)
        }
      }
    }
  }

  fun attach(player: Player) {
    playerRef = player
    lastPlaybackState = player.playbackState
    if (!segmentPollerStarted) {
      segmentPollerStarted = true
      mainHandler.post(segmentPollRunnable)
    }
  }

  override fun onPlaybackStateChanged(_eventTime: AnalyticsListener.EventTime, state: Int) {
    val player = playerRef
    // Track stalling (rebuffering) periods when the player wants to play.
    if (player != null) {
      if (state == Player.STATE_BUFFERING && freezeStartRealtimeMs == null && player.playWhenReady) {
        freezeStartRealtimeMs = System.currentTimeMillis()
      } else if (lastPlaybackState == Player.STATE_BUFFERING && state == Player.STATE_READY && player.playWhenReady) {
        val start = freezeStartRealtimeMs
        if (start != null) {
          val durationMs = System.currentTimeMillis() - start
          if (durationMs > 0) {
            pendingFreezeDurationsMs.addLast(durationMs)
          }
        }
        freezeStartRealtimeMs = null
      }
    }
    lastPlaybackState = state
  }

  override fun onPlayerError(_eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
    downloadLogger.logEvent(
      source = "QOS",
      event = "player_error",
      details = "${error.errorCodeName}: ${error.message ?: ""}"
    )
  }

  override fun onVideoInputFormatChanged(
    eventTime: AnalyticsListener.EventTime,
    format: Format,
    decoderReuseEvaluation: DecoderReuseEvaluation?
  ) {
    downloadLogger.logEvent(
      source = "QOS",
      event = "video_input_format",
      details = "bitrate=${format.bitrate}, size=${format.width}x${format.height}"
    )
  }

  override fun onTracksChanged(
    eventTime: AnalyticsListener.EventTime,
    tracks: Tracks
  ) {
    val hasVideoSelected =
      tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && (0 until it.length).any(it::isTrackSelected) }
    downloadLogger.logEvent(
      source = "QOS",
      event = "tracks_changed",
      details = "video_selected=${hasVideoSelected}"
    )
  }

  override fun onDownstreamFormatChanged(
    eventTime: AnalyticsListener.EventTime,
    mediaLoadData: MediaLoadData
  ) {
    val f = mediaLoadData.trackFormat
    val type = trackTypeName(mediaLoadData.trackType)
    downloadLogger.logEvent(
      source = "QOS",
      event = "downstream_format",
      details = "type=${type} id=${f?.id} codecs=${f?.codecs} mime=${f?.sampleMimeType} w=${f?.width} h=${f?.height} initBytes=${f?.initializationData?.sumOf { it.size }}"
    )
  }

  // Network load events
  override fun onLoadStarted(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData
  ) {
    val dataType = dataTypeName(mediaLoadData.dataType)
    val trackType = trackTypeName(mediaLoadData.trackType)

    // Structured HTTP CSV in Downloads
    val positionMs = playerRef?.currentPosition
    val ct = loadEventInfo.responseHeaders["Content-Type"]?.firstOrNull()
    downloadLogger.logHttpEvent(
      event = "load_started",
      positionMs = positionMs,
      dataType = dataType,
      trackType = trackType,
      uri = loadEventInfo.uri.toString(),
      contentType = ct,
      loadDurationMs = null,
      bytesLoaded = null,
      httpCode = null,
      errorClass = null
    )

    downloadLogger.logEvent(
      source = "QOS",
      event = "load_started",
      details = "type=${dataType} trackType=${trackType} uri=${loadEventInfo.uri}"
    )
  }

  override fun onLoadCompleted(
    eventTime: AnalyticsListener.EventTime,
    loadEventInfo: LoadEventInfo,
    mediaLoadData: MediaLoadData
  ) {
    val ct = loadEventInfo.responseHeaders["Content-Type"]?.firstOrNull()
    val dataType = dataTypeName(mediaLoadData.dataType)
    val trackType = trackTypeName(mediaLoadData.trackType)

    // Structured HTTP CSV in Downloads
    val positionMs = playerRef?.currentPosition
    downloadLogger.logHttpEvent(
      event = "load_completed",
      positionMs = positionMs,
      dataType = dataType,
      trackType = trackType,
      uri = loadEventInfo.uri.toString(),
      contentType = ct,
      loadDurationMs = loadEventInfo.loadDurationMs,
      bytesLoaded = loadEventInfo.bytesLoaded,
      httpCode = null,
      errorClass = null
    )

    downloadLogger.logEvent(
      source = "QOS",
      event = "load_completed",
      details = "type=${dataType} trackType=${trackType} dur_ms=${loadEventInfo.loadDurationMs} bytes=${loadEventInfo.bytesLoaded} ct=${ct ?: ""} uri=${loadEventInfo.uri}"
    )

    // Segment-level playback log: VIDEO/AUDIO media chunks.
    if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA &&
      (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO || mediaLoadData.trackType == C.TRACK_TYPE_AUDIO)
    ) {
      val segmentStartMs =
        if (mediaLoadData.mediaStartTimeMs != C.TIME_UNSET) mediaLoadData.mediaStartTimeMs
        else playerRef?.currentPosition ?: -1L

      val freezeMs = if (pendingFreezeDurationsMs.isNotEmpty()) pendingFreezeDurationsMs.removeFirst() else 0L
      val freezeSec = freezeMs / 1000.0
      val f = mediaLoadData.trackFormat
      val readableTrackType = trackTypeName(mediaLoadData.trackType)
      pendingSegments.add(
        PendingSegment(
          startMs = segmentStartMs,
          trackType = readableTrackType,
          format = f,
          freezeSec = freezeSec
        )
      )
    }

    // RL data collection: Only for VIDEO MEDIA chunks
    if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO &&
      mediaLoadData.dataType == C.DATA_TYPE_MEDIA
    ) {
      val durationMs = loadEventInfo.loadDurationMs.coerceAtLeast(1L)
      val durationSec = durationMs / 1000.0
      val bits = loadEventInfo.bytesLoaded * 8.0

      // Throughput in bits/second
      val throughputBps = bits / durationSec

      // Latency proxy: use the same duration (or replace with RTT if available)
      val latencySec = durationSec

      com.example.dashabrrl.rl.RlState.update(
        throughputBps = throughputBps,
        latencySec = latencySec
      )
    }
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
    val dataType = dataTypeName(mediaLoadData.dataType)
    val trackType = trackTypeName(mediaLoadData.trackType)

    // Structured HTTP CSV in Downloads
    val positionMs = playerRef?.currentPosition
    downloadLogger.logHttpEvent(
      event = "load_error",
      positionMs = positionMs,
      dataType = dataType,
      trackType = trackType,
      uri = uriStr,
      contentType = ct,
      loadDurationMs = loadEventInfo.loadDurationMs,
      bytesLoaded = loadEventInfo.bytesLoaded,
      httpCode = http,
      errorClass = error.javaClass.simpleName
    )

    downloadLogger.logEvent(
      source = "QOS",
      event = "load_error",
      details = "type=${dataType} trackType=${trackType} code=${http} ct=${ct ?: ""} err=${error.javaClass.simpleName}:${error.message} uri=${uriStr} tail=${tail}"
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

  fun release() {
    segmentPollerStarted = false
    mainHandler.removeCallbacksAndMessages(null)
    pendingSegments.clear()
  }

  /**
   * Generic event logging entrypoint used by other components (e.g., RL controllers).
   */
  fun logEvent(event: String, details: String) {
    downloadLogger.logEvent(
      source = "QOS",
      event = event,
      details = details
    )
  }

  /**
   * Poll current playback position and emit playback_log entries when segments
   * actually start playing on screen. This ensures playback_log.csv reflects
   * what is being rendered, not just what was loaded.
   */
  private fun dispatchPlayedSegments() {
    val player = playerRef ?: return
    if (!player.isPlaying) return

    val positionMs = player.currentPosition
    if (pendingSegments.isEmpty()) return

    val it = pendingSegments.iterator()
    while (it.hasNext()) {
      val seg = it.next()
      if (seg.startMs <= positionMs) {
        downloadLogger.logPlaybackSegment(
          segmentStartMs = seg.startMs,
          trackType = seg.trackType,
          format = seg.format,
          freezeSec = seg.freezeSec
        )
        it.remove()
      }
    }
  }
}
