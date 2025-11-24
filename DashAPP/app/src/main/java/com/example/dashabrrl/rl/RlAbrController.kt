package com.example.dashabrrl.rl

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import java.nio.FloatBuffer
import kotlin.math.roundToInt

class RlAbrController(
  private val context: Context,
  private val bandwidthMeter: BandwidthMeter,
  private val logger: com.example.dashabrrl.telemetry.QosLogger? = null
) {
  private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
  private var session: OrtSession? = null
  private val handler = Handler(Looper.getMainLooper())
  private var running = false

  // Cache for mapping from index -> bitrate for convenience
  private var currentGroupFormats: List<Format> = emptyList()

  fun loadModel(bytes: ByteArray) {
    session = env.createSession(bytes)
  }

  fun start(player: ExoPlayer) {
    running = true
    // Prime formats after prepare; delay a bit to let tracks be available
    handler.postDelayed({ updateFormats(player) }, 1000)
    schedule(player)
  }

  fun stop() {
    running = false
    try { session?.close() } catch (_: Exception) {}
  }

  private fun schedule(player: ExoPlayer) {
    if (!running) return
    handler.postDelayed({
      try {
        decideAndApply(player)
      } catch (_: Throwable) { /* ignore */ }
      schedule(player)
    }, 1000)
  }

  private fun updateFormats(player: ExoPlayer) {
    val tracks = player.currentTracks
    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    if (videoGroups.isNotEmpty()) {
      val g = videoGroups.first()
      val formats = mutableListOf<Format>()
      for (i in 0 until g.length) formats += g.getTrackFormat(i)
      currentGroupFormats = formats
    }
  }

  private fun decideAndApply(player: ExoPlayer) {
    updateFormats(player)
    val tracks = player.currentTracks
    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    if (videoGroups.isEmpty()) return
    val group = videoGroups.first()
    val count = group.length
    if (count == 0) return

    // Use 70% of the measured bandwidth so the RL policy stays conservative and leaves headroom (e.g., for audio).
    val trueBw = bandwidthMeter.bitrateEstimate.toDouble().coerceAtLeast(1.0)
    val bw = trueBw * 0.7
    val bufferMs = player.totalBufferedDuration.toDouble()

    // Build feature vector: [bw_bps, buffer_s, n_tracks, bitrates...]
    val bitrates = (0 until count).map { group.getTrackFormat(it).bitrate.toFloat() }
    val feature = FloatArray(3 + count)
    feature[0] = bw.toFloat()
    feature[1] = (bufferMs / 1000.0).toFloat()
    feature[2] = count.toFloat()
    for (i in 0 until count) feature[3 + i] = bitrates[i]

    val chosen = runModel(feature, count) ?: greedyPick(bw, bitrates)

    if (chosen in 0 until count) {
      val override = TrackSelectionOverride(group.mediaTrackGroup, listOf(chosen))
      val newParams = player.trackSelectionParameters
        .buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        .addOverride(override)
        .build()
      player.trackSelectionParameters = newParams
      val brJoined = bitrates.joinToString("/") { it.toInt().toString() }
      logger?.logEvent(
        "rl_decision",
        "true_bw=${trueBw.toLong()} eff_bw=${bw.toLong()} buffer_ms=${bufferMs.toLong()} chosen=${chosen} bitrates=${brJoined}" )
    }
  }

  private fun greedyPick(bwBps: Double, bitrates: List<Float>): Int {
    // Conservative: pick highest bitrate <= 0.85 * bw
    val cap = (0.85 * bwBps).toFloat()
    var idx = 0
    var best = -1f
    for (i in bitrates.indices) {
      val br = bitrates[i]
      if (br <= cap && br > best) { best = br; idx = i }
    }
    return idx
  }

  private fun runModel(features: FloatArray, trackCount: Int): Int? {
    val s = session ?: return null
    val shape = longArrayOf(1, features.size.toLong())
    val fb = FloatBuffer.wrap(features)
    var tensor: OnnxTensor? = null
    var results: OrtSession.Result? = null
    try {
      tensor = OnnxTensor.createTensor(env, fb, shape)
      results = s.run(mapOf("input" to tensor))
      val value = results[0].value
      val out: FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> (value.firstOrNull() as? FloatArray) ?: return null
        else -> return null
      }
      if (out.isEmpty()) return null
      var maxIdx = 0
      var maxVal = Float.NEGATIVE_INFINITY
      val limit = minOf(out.size, trackCount)
      for (i in 0 until limit) {
        if (out[i] > maxVal) { maxVal = out[i]; maxIdx = i }
      }
      return maxIdx
    } finally {
      try { results?.close() } catch (_: Exception) {}
      try { tensor?.close() } catch (_: Exception) {}
    }
  }
}
