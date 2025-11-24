package com.example.dashabrrl.rl

import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.sqrt

/**
 * Shared state to bridge QosLogger (data collection) and RlTrackSelection (decision making).
 *
 * Units are chosen to match the Python SABRE RL environment:
 *  - throughput: bits per second
 *  - latency:   seconds
 */
object RlState {

  // Max history length (matches history_len=5 in Python env)
  private const val HISTORY_LEN = 5

  // Lookahead horizon for the RL agent (number of future chunks).
  const val LOOKAHEAD = 5

  // History of raw throughput in bits/second
  private val throughputHistory = ConcurrentLinkedDeque<Double>()

  // History of latency in seconds
  private val latencyHistory = ConcurrentLinkedDeque<Double>()

  /**
   * Add a new measurement to the histories.
   */
  fun update(throughputBps: Double, latencySec: Double) {
    throughputHistory.addFirst(throughputBps)
    latencyHistory.addFirst(latencySec)

    if (throughputHistory.size > HISTORY_LEN) throughputHistory.removeLast()
    if (latencyHistory.size > HISTORY_LEN) latencyHistory.removeLast()
  }

  /**
   * Return [current, mean, std] for throughput (bits/s) as FloatArray.
   */
  fun getThroughputStats(): FloatArray {
    return calculateStats(throughputHistory)
  }

  /**
   * Return [current, mean, std] for latency (seconds) as FloatArray.
   */
  fun getLatencyStats(): FloatArray {
    return calculateStats(latencyHistory)
  }

  /**
   * Reset state when starting a new playback session.
   */
  fun clear() {
    throughputHistory.clear()
    latencyHistory.clear()
  }

  private fun calculateStats(history: ConcurrentLinkedDeque<Double>): FloatArray {
    if (history.isEmpty()) return floatArrayOf(0f, 0f, 0f)

    val snapshot = history.toList()
    val current = snapshot.firstOrNull() ?: 0.0
    val mean = snapshot.average()

    var sumSq = 0.0
    for (v in snapshot) {
      val d = v - mean
      sumSq += d * d
    }
    val std = if (snapshot.size > 1) sqrt(sumSq / snapshot.size) else 0.0

    return floatArrayOf(
      current.toFloat(),
      mean.toFloat(),
      std.toFloat()
    )
  }
}

