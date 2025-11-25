package com.example.dashabrrl.rl

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.BaseTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.example.dashabrrl.telemetry.DownloadCsvLogger

/**
 * Custom track selection that queries the RL policy (ONNX model)
 * each time ExoPlayer needs to choose a track.
 *
 * It:
 *  - Sorts qualities by bitrate (low -> high), so action 0 is always the lowest bitrate.
 *  - Builds chunk_sizes approximation for LOOKAHEAD segments.
 *  - Gets throughput & latency stats from RlState.
 */
class RlTrackSelection(
  group: TrackGroup,
  tracks: IntArray,
  private val inferenceEngine: RlInferenceEngine,
  private val downloadLogger: DownloadCsvLogger?
) : BaseTrackSelection(group, *tracks) {

  companion object {
    // Must match the segment duration used during training (e.g. 2 seconds).
    private const val DEFAULT_SEG_DURATION_SEC = 2.0f
  }

  // selectedIndex is an index in [0, length) for this selection (BaseTrackSelection's internal index).
  private var selectedIndex = 0

  /**
   * sortedIndices: internal indices 0..length-1 sorted by bitrate ascending.
   * This ensures RL action 0 = lowest bitrate, action N-1 = highest bitrate.
   */
  private val sortedIndices: List<Int> = run {
    val internalIndices = (0 until length).toList()
    internalIndices.sortedBy { i ->
      val f = getFormat(i)
      if (f.bitrate != Format.NO_VALUE) f.bitrate else 0
    }
  }

  init {
    selectedIndex = sortedIndices.firstOrNull() ?: 0
  }

  override fun getSelectedIndex(): Int = selectedIndex

  override fun getSelectionReason(): Int = C.SELECTION_REASON_ADAPTIVE

  override fun getSelectionData(): Any? = null

  /**
   * Called by ExoPlayer when it is about to choose the next media chunk.
   * This is the ideal place to run the RL policy.
   */
  override fun updateSelectedTrack(
    playbackPositionUs: Long,
    bufferedDurationUs: Long,
    availableDurationUs: Long,
    queue: MutableList<out MediaChunk>,
    mediaChunkIterators: Array<out MediaChunkIterator>
  ) {
    // Buffer in seconds
    val bufferSec = bufferedDurationUs / 1_000_000f

    // Get stats from RlState (history up to 5 samples)
    val tpStats = RlState.getThroughputStats()
    val latStats = RlState.getLatencyStats()

    // Number of actions/qualities (same as length of this selection)
    val nActions = length
    if (nActions <= 0) return

    // Build chunk_sizes: flatten [LOOKAHEAD, nActions] -> [LOOKAHEAD * nActions]
    val chunkSizesBits = FloatArray(RlState.LOOKAHEAD * nActions)

    for (lookaheadIdx in 0 until RlState.LOOKAHEAD) {
      for (actionIdx in 0 until nActions) {
        val internalIndex = sortedIndices[actionIdx]
        val format = getFormat(internalIndex)

        val bitrateBps = if (format.bitrate != Format.NO_VALUE) {
          format.bitrate.toFloat()
        } else {
          0f
        }

        // Approximate segment size as bitrate * constant segment duration.
        val sizeBits = bitrateBps * DEFAULT_SEG_DURATION_SEC

        val flatIndex = lookaheadIdx * nActions + actionIdx
        chunkSizesBits[flatIndex] = sizeBits
      }
    }

    // Run the RL model
    val actionIndex = inferenceEngine.predict(
      bufferSec = bufferSec,
      throughputStats = tpStats,
      latencyStats = latStats,
      chunkSizesBits = chunkSizesBits
    )

    // Clamp and map back to internal index
    val clamped = actionIndex.coerceIn(0, nActions - 1)
    val newSelectedIndex = sortedIndices[clamped]

    if (newSelectedIndex != selectedIndex) {
      val f = getFormat(newSelectedIndex)
      val res = if (f.height != Format.NO_VALUE && f.width != Format.NO_VALUE) {
        "${f.width}x${f.height}"
      } else {
        ""
      }
      downloadLogger?.logEvent(
        source = "RL",
        event = "rl_decision",
        details = "buffer_sec=${bufferSec} tp_mean=${tpStats.getOrNull(1) ?: 0f} lat_mean=${latStats.getOrNull(1) ?: 0f} action=${clamped} bitrate=${f.bitrate} res=${res}"
      )
    }

    selectedIndex = newSelectedIndex
  }

  /**
   * Factory used by DefaultTrackSelector to create this selection for video tracks.
   *
   * This wraps an AdaptiveTrackSelection.Factory so that audio/text still use the standard adaptive logic.
   */
  class Factory(
    private val inferenceEngine: RlInferenceEngine,
    private val downloadLogger: DownloadCsvLogger?
  ) : ExoTrackSelection.Factory {

    private val adaptiveFactory = AdaptiveTrackSelection.Factory()

    override fun createTrackSelections(
      definitions: Array<out ExoTrackSelection.Definition>,
      bandwidthMeter: BandwidthMeter,
      mediaPeriodId: MediaSource.MediaPeriodId,
      timeline: Timeline
    ): Array<ExoTrackSelection?> {
      val baseSelections =
        adaptiveFactory.createTrackSelections(definitions, bandwidthMeter, mediaPeriodId, timeline)
      for (i in definitions.indices) {
        val def: ExoTrackSelection.Definition? = definitions[i]
        if (def != null && def.tracks.isNotEmpty() && isVideoDefinition(def)) {
          baseSelections[i] = RlTrackSelection(def.group, def.tracks, inferenceEngine, downloadLogger)
        }
      }
      return baseSelections
    }

    private fun isVideoDefinition(definition: ExoTrackSelection.Definition): Boolean {
      if (definition.tracks.isEmpty()) return false
      val group: TrackGroup = definition.group
      val format: Format = group.getFormat(definition.tracks[0])
      val mime = format.sampleMimeType
      return (mime != null && mime.startsWith("video/")) ||
        (format.height != Format.NO_VALUE || format.width != Format.NO_VALUE)
    }
  }
}
