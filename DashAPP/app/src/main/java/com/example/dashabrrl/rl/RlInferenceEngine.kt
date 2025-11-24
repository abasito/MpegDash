package com.example.dashabrrl.rl

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.nio.FloatBuffer

/**
 * Thin wrapper around ONNX Runtime for the ABR RL policy.
 *
 * Python export used:
 *   input_names=["chunk_sizes", "buffer_sec", "throughput", "latency"]
 *   output_names=["logits", "value"]
 */
class RlInferenceEngine(modelBytes: ByteArray) {

  companion object {
    private const val TAG = "RlInference"
  }

  private var env: OrtEnvironment? = null
  private var session: OrtSession? = null

  init {
    try {
      env = OrtEnvironment.getEnvironment()
      session = env?.createSession(modelBytes)
      Log.d(TAG, "ONNX Session created. Inputs=${session?.inputNames} Outputs=${session?.outputNames}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create ONNX session", e)
    }
  }

  /**
   * Run the RL policy.
   *
   * @param bufferSec       Current buffer level in seconds.
   * @param throughputStats FloatArray[3] = [current, mean, std] in bits/second.
   * @param latencyStats    FloatArray[3] = [current, mean, std] in seconds.
   * @param chunkSizesBits  Flattened FloatArray of shape [LOOKAHEAD * nActions], in bits.
   *
   * @return action index in [0, nActions-1]. Fallback is 0 on error.
   */
  fun predict(
    bufferSec: Float,
    throughputStats: FloatArray,
    latencyStats: FloatArray,
    chunkSizesBits: FloatArray
  ): Int {
    val s = session
    val e = env
    if (s == null || e == null) return 0

    val nActions = if (RlState.LOOKAHEAD > 0) chunkSizesBits.size / RlState.LOOKAHEAD else 0
    if (nActions <= 0) {
      Log.w(TAG, "predict: Invalid nActions from chunkSizesBits.size=${chunkSizesBits.size}")
      return 0
    }

    val bufferTensor = OnnxTensor.createTensor(
      e,
      FloatBuffer.wrap(floatArrayOf(bufferSec)),
      longArrayOf(1, 1)
    )
    val tpTensor = OnnxTensor.createTensor(
      e,
      FloatBuffer.wrap(throughputStats),
      longArrayOf(1, 3)
    )
    val latTensor = OnnxTensor.createTensor(
      e,
      FloatBuffer.wrap(latencyStats),
      longArrayOf(1, 3)
    )
    val chunkTensor = OnnxTensor.createTensor(
      e,
      FloatBuffer.wrap(chunkSizesBits),
      longArrayOf(1, RlState.LOOKAHEAD.toLong(), nActions.toLong())
    )

    val inputs = mapOf(
      "buffer_sec" to bufferTensor,
      "throughput" to tpTensor,
      "latency" to latTensor,
      "chunk_sizes" to chunkTensor
    )

    return try {
      val result = s.run(inputs)
      result.use {
        @Suppress("UNCHECKED_CAST")
        val logitsValue = it[0].value as Array<FloatArray>
        val logits = logitsValue[0]
        argmax(logits)
      }
    } catch (e2: Exception) {
      Log.e(TAG, "Inference failed", e2)
      0
    } finally {
      try {
        bufferTensor.close()
      } catch (_: Exception) {
      }
      try {
        tpTensor.close()
      } catch (_: Exception) {
      }
      try {
        latTensor.close()
      } catch (_: Exception) {
      }
      try {
        chunkTensor.close()
      } catch (_: Exception) {
      }
    }
  }

  private fun argmax(array: FloatArray): Int {
    var maxIdx = 0
    var maxVal = array[0]
    for (i in 1 until array.size) {
      if (array[i] > maxVal) {
        maxVal = array[i]
        maxIdx = i
      }
    }
    return maxIdx
  }

  fun close() {
    try {
      session?.close()
    } catch (_: Exception) {
    }
    try {
      env?.close()
    } catch (_: Exception) {
    }
  }
}
