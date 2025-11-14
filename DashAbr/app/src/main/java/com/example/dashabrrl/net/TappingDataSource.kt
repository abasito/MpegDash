package com.example.dashabrrl.net

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap

/**
 * Wraps a DataSource and records a small tail of bytes per-URI to help debug
 * parser failures (e.g., invalid NAL length). Use ByteTapRegistry.hexTail(uri)
 * in AnalyticsListener.onLoadError to print the last bytes read.
 */
class TappingDataSource(private val upstream: DataSource) : DataSource {
  private var currentUri: Uri? = null

  override fun addTransferListener(transferListener: TransferListener) {
    upstream.addTransferListener(transferListener)
  }

  override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
    currentUri = dataSpec.uri
    // Clear any old ring for this uri
    currentUri?.let { ByteTapRegistry.clear(it.toString()) }
    return upstream.open(dataSpec)
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    val n = upstream.read(buffer, offset, length)
    val u = currentUri
    if (n > 0 && u != null) {
      ByteTapRegistry.record(u.toString(), buffer, offset, n)
    }
    return n
  }

  override fun getUri(): Uri? = upstream.uri

  override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

  override fun close() {
    upstream.close()
    currentUri = null
  }

  class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = TappingDataSource(upstreamFactory.createDataSource())
  }
}

object ByteTapRegistry {
  private const val RING_SIZE = 128
  private data class Ring(val buf: ByteArray = ByteArray(RING_SIZE), var pos: Int = 0, var filled: Int = 0)
  private val rings = ConcurrentHashMap<String, Ring>()

  fun record(uri: String, src: ByteArray, off: Int, len: Int) {
    val ring = rings.computeIfAbsent(uri) { Ring() }
    var i = 0
    while (i < len) {
      ring.buf[ring.pos] = src[off + i]
      ring.pos = (ring.pos + 1) % ring.buf.size
      if (ring.filled < ring.buf.size) ring.filled++
      i++
    }
  }

  fun clear(uri: String) { rings.remove(uri) }

  fun hexTail(uri: String, max: Int = RING_SIZE): String? {
    val ring = rings[uri] ?: return null
    val outCount = minOf(max, ring.filled)
    val sb = StringBuilder(outCount * 3)
    var idx = (ring.pos - outCount + ring.buf.size) % ring.buf.size
    repeat(outCount) {
      val v: Int = ring.buf[idx].toInt() and 0xFF
      if (it > 0) sb.append(' ')
      if (v < 16) sb.append('0')
      sb.append(v.toString(16))
      idx = (idx + 1) % ring.buf.size
    }
    return sb.toString()
  }
}

