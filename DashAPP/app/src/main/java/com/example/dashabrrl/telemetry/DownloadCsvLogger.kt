package com.example.dashabrrl.telemetry

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.Format
import androidx.media3.common.Player
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes two CSV logs under the app's Downloads directory:
 *  - playback_log.csv: what is actually being played (track/resolution/audio) over time
 *  - http_log.csv: what is requested over HTTP (segments, manifests, etc.)
 *
 * Files are appended if they already exist.
 *
 * Left-most column is always wall-clock time, right-most column is playback mode (adaptive/fixed/rl).
 */
class DownloadCsvLogger(
  context: Context,
  private val mode: String
) {
  private val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS", Locale.US)

  private val resolver = context.contentResolver

  private val playbackUri: android.net.Uri
  private val httpUri: android.net.Uri
  private val eventsUri: android.net.Uri
  private val playbackIsNew: Boolean
  private val httpIsNew: Boolean
  private val eventsIsNew: Boolean

  private var playbackWriter: BufferedWriter? = null
  private var httpWriter: BufferedWriter? = null
  private var eventsWriter: BufferedWriter? = null

  init {
    val (pUri, pNew) = getOrCreateCsvUri("playback_log.csv")
    playbackUri = pUri
    playbackIsNew = pNew

    val (hUri, hNew) = getOrCreateCsvUri("http_log.csv")
    httpUri = hUri
    httpIsNew = hNew

    val (eUri, eNew) = getOrCreateCsvUri("events_log.csv")
    eventsUri = eUri
    eventsIsNew = eNew
  }

  private fun getOrCreateCsvUri(name: String): Pair<android.net.Uri, Boolean> {
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
      MediaStore.Downloads.EXTERNAL_CONTENT_URI
    }

    var existingUri: android.net.Uri? = null
    val projection = arrayOf(
      MediaStore.Downloads._ID,
      MediaStore.Downloads.DISPLAY_NAME
    )
    val selection = "${MediaStore.Downloads.DISPLAY_NAME}=?"
    val selectionArgs = arrayOf(name)

    resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
      if (cursor.moveToFirst()) {
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
        val id = cursor.getLong(idCol)
        existingUri = ContentUris.withAppendedId(collection, id)
      }
    }

    if (existingUri != null) {
      return existingUri!! to false
    }

    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, name)
      put(MediaStore.Downloads.MIME_TYPE, "text/csv")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      }
    }

    val createdUri = resolver.insert(collection, values)
      ?: throw IllegalStateException("Failed to create $name in public Downloads")

    return createdUri to true
  }

  @Synchronized
  private fun ensurePlaybackWriter() {
    if (playbackWriter == null) {
      // For existing files, ensure a header line at the top.
      if (!playbackIsNew) {
        val hasHeader = resolver.openInputStream(playbackUri)?.bufferedReader()?.use { reader ->
          val firstLine = reader.readLine()
          firstLine != null && firstLine.startsWith("time,")
        } ?: false

        if (!hasHeader) {
          val existingContent = resolver.openInputStream(playbackUri)?.bufferedReader()
            ?.use { it.readText() }.orEmpty()

          val outRewrite = resolver.openOutputStream(playbackUri, "wt")
            ?: throw IllegalStateException("Failed to rewrite playback_log.csv")
          val writerRewrite = BufferedWriter(OutputStreamWriter(outRewrite, Charsets.UTF_8))
          writerRewrite.write(
            "time,position_ms,track_type,resolution_or_label,bitrate_bps,mime,freeze_sec,mode"
          )
          writerRewrite.newLine()
          if (existingContent.isNotEmpty()) {
            writerRewrite.write(existingContent)
          }
          writerRewrite.flush()
          playbackWriter = writerRewrite
          return
        }
      }

      val out = resolver.openOutputStream(playbackUri, "wa")
        ?: throw IllegalStateException("Failed to open playback_log.csv for append")
      val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
      if (playbackIsNew) {
        writer.write(
          "time,position_ms,track_type,resolution_or_label,bitrate_bps,mime,freeze_sec,mode"
        )
        writer.newLine()
        writer.flush()
      }
      playbackWriter = writer
    }
  }

  @Synchronized
  private fun ensureHttpWriter() {
    if (httpWriter == null) {
      // For existing files, ensure a header line at the top.
      if (!httpIsNew) {
        val hasHeader = resolver.openInputStream(httpUri)?.bufferedReader()?.use { reader ->
          val firstLine = reader.readLine()
          firstLine != null && firstLine.startsWith("time,")
        } ?: false

        if (!hasHeader) {
          val existingContent = resolver.openInputStream(httpUri)?.bufferedReader()
            ?.use { it.readText() }.orEmpty()

          val outRewrite = resolver.openOutputStream(httpUri, "wt")
            ?: throw IllegalStateException("Failed to rewrite http_log.csv")
          val writerRewrite = BufferedWriter(OutputStreamWriter(outRewrite, Charsets.UTF_8))
          writerRewrite.write(
            "time,event,position_ms,data_type,track_type,uri,content_type," +
              "load_duration_ms,bytes_loaded,http_code,error_class,mode"
          )
          writerRewrite.newLine()
          if (existingContent.isNotEmpty()) {
            writerRewrite.write(existingContent)
          }
          writerRewrite.flush()
          httpWriter = writerRewrite
          return
        }
      }

      val out = resolver.openOutputStream(httpUri, "wa")
        ?: throw IllegalStateException("Failed to open http_log.csv for append")
      val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
      if (httpIsNew) {
        writer.write(
          "time,event,position_ms,data_type,track_type,uri,content_type," +
            "load_duration_ms,bytes_loaded,http_code,error_class,mode"
        )
        writer.newLine()
        writer.flush()
      }
      httpWriter = writer
    }
  }

  @Synchronized
  private fun ensureEventsWriter() {
    if (eventsWriter == null) {
      // For existing files, ensure a header line at the top.
      if (!eventsIsNew) {
        val hasHeader = resolver.openInputStream(eventsUri)?.bufferedReader()?.use { reader ->
          val firstLine = reader.readLine()
          firstLine != null && firstLine.startsWith("time,")
        } ?: false

        if (!hasHeader) {
          val existingContent = resolver.openInputStream(eventsUri)?.bufferedReader()
            ?.use { it.readText() }.orEmpty()

          val outRewrite = resolver.openOutputStream(eventsUri, "wt")
            ?: throw IllegalStateException("Failed to rewrite events_log.csv")
          val writerRewrite = BufferedWriter(OutputStreamWriter(outRewrite, Charsets.UTF_8))
          writerRewrite.write("time,source,event,details,mode")
          writerRewrite.newLine()
          if (existingContent.isNotEmpty()) {
            writerRewrite.write(existingContent)
          }
          writerRewrite.flush()
          eventsWriter = writerRewrite
          return
        }
      }

      val out = resolver.openOutputStream(eventsUri, "wa")
        ?: throw IllegalStateException("Failed to open events_log.csv for append")
      val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
      if (eventsIsNew) {
        writer.write("time,source,event,details,mode")
        writer.newLine()
        writer.flush()
      }
      eventsWriter = writer
    }
  }

  /**
   * Log a media segment that will be / is being played.
   * This is driven by ExoPlayer load-completed callbacks for MEDIA data.
   */
  @Synchronized
  fun logPlaybackSegment(
    segmentStartMs: Long,
    trackType: String,
    format: Format?,
    freezeSec: Double
  ) {
    ensurePlaybackWriter()

    val time = sdf.format(Date())
    val positionMs = segmentStartMs

    val (resolutionLabel, bitrate, mime) = when {
      trackType == "VIDEO" && format != null -> {
        val res = if (format.width > 0 && format.height > 0) {
          "${format.width}x${format.height}"
        } else {
          ""
        }
        Triple(res, format.bitrate, format.sampleMimeType ?: "")
      }
      trackType == "AUDIO" && format != null -> {
        Triple("audio", format.bitrate, format.sampleMimeType ?: "")
      }
      else -> Triple("", format?.bitrate ?: -1, format?.sampleMimeType ?: "")
    }

    val line = listOf(
      time,
      positionMs.toString(),
      trackType,
      resolutionLabel,
      bitrate.toString(),
      mime,
      String.format(Locale.US, "%.3f", freezeSec),
      mode
    ).joinToString(",")

    playbackWriter?.appendLine(line)
    playbackWriter?.flush()
  }

  /**
   * Log an HTTP-related event (segment/manifest/DRM/etc).
   */
  @Synchronized
  fun logHttpEvent(
    event: String,
    positionMs: Long?,
    dataType: String,
    trackType: String,
    uri: String,
    contentType: String?,
    loadDurationMs: Long?,
    bytesLoaded: Long?,
    httpCode: Int?,
    errorClass: String?
  ) {
    ensureHttpWriter()

    val time = sdf.format(Date())

    val line = listOf(
      time,
      event,
      (positionMs ?: -1L).toString(),
      dataType,
      trackType,
      uri,
      contentType ?: "",
      loadDurationMs?.toString() ?: "",
      bytesLoaded?.toString() ?: "",
      httpCode?.toString() ?: "",
      errorClass ?: "",
      mode
    ).joinToString(",")

    httpWriter?.appendLine(line)
    httpWriter?.flush()
  }

  /**
   * Log high-level events and errors (player, RL, network, etc.).
   */
  @Synchronized
  fun logEvent(
    source: String,
    event: String,
    details: String
  ) {
    ensureEventsWriter()

    val time = sdf.format(Date())
    val line = listOf(
      time,
      source,
      event,
      details,
      mode
    ).joinToString(",")

    eventsWriter?.appendLine(line)
    eventsWriter?.flush()
  }

  @Synchronized
  fun close() {
    try {
      playbackWriter?.flush()
      playbackWriter?.close()
    } catch (_: Exception) {
    }
    playbackWriter = null

    try {
      httpWriter?.flush()
      httpWriter?.close()
    } catch (_: Exception) {
    }
    httpWriter = null

    try {
      eventsWriter?.flush()
      eventsWriter?.close()
    } catch (_: Exception) {
    }
    eventsWriter = null
  }
}
