package com.resqgo.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pilot data: every file goes to outbox/, is posted to the Google Apps Script in the
 * owner's Google account (see pilot/Code.gs), then moves to sent/. Nothing is deleted,
 * so a weekly zip export still works if uploads never succeed.
 */
object Uploader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)

    val waiting = MutableStateFlow(0)
    val status = MutableStateFlow(if (enabled()) "Not uploaded yet" else "Upload isn't set up in this build. Use Share pilot data.")

    fun enabled() = BuildConfig.UPLOAD_URL.isNotBlank()

    private fun outbox(ctx: Context) = File(ctx.filesDir, "outbox").apply { mkdirs() }
    private fun sent(ctx: Context) = File(ctx.filesDir, "sent").apply { mkdirs() }

    /** Writes off the main thread; [content] is built there too. */
    fun queue(ctx: Context, name: String, content: () -> String) {
        val app = ctx.applicationContext
        scope.launch {
            File(outbox(app), name).writeText(content())
            waiting.value = outbox(app).list()?.size ?: 0
        }
    }

    fun flushSoon(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { flush(app) }
    }

    /**
     * A ride file still called current-* with no ride running means Android (or a crash)
     * killed the service mid-ride. Its last checkpoint already says ended=interrupted.
     */
    fun recoverInterrupted(ctx: Context) {
        ctx.filesDir.listFiles { f -> f.name.startsWith("current-") }?.forEach {
            it.renameTo(File(outbox(ctx), it.name.removePrefix("current-")))
        }
    }

    private fun flush(ctx: Context) {
        if (RideState.phase.value == Phase.IDLE) recoverInterrupted(ctx)
        val files = outbox(ctx).listFiles().orEmpty().sortedBy { it.name }
        waiting.value = files.size
        if (!enabled() || files.isEmpty() || !busy.compareAndSet(false, true)) return
        try {
            val rider = Prefs(ctx).riderId.ifBlank { "unknown" }
            var done = 0
            for (f in files) {
                if (!postUpload(BuildConfig.UPLOAD_URL, BuildConfig.UPLOAD_KEY, rider, f.name, f.readText())) break
                f.renameTo(File(sent(ctx), f.name))
                done++
            }
            val time = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH).format(Date())
            status.value = if (done == files.size) "All uploaded at $time" else "Uploaded $done of ${files.size} at $time. The rest retry later."
        } finally {
            busy.set(false)
            waiting.value = outbox(ctx).list()?.size ?: 0
        }
    }

    /** Zips everything collected on this phone, for sharing over WhatsApp or Drive by hand. */
    fun exportZip(ctx: Context): File {
        recoverInterrupted(ctx)
        val rider = Prefs(ctx).riderId.ifBlank { "unknown" }
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val zip = File(dir, "resqgo-$rider-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (f in listOf(outbox(ctx), sent(ctx)).flatMap { it.listFiles().orEmpty().asList() }) {
                out.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }
}

/**
 * Posts one file as JSON. Google Apps Script answers a POST with a redirect to the
 * script's output, which HttpURLConnection follows as a GET. True only on {"ok":true}.
 */
fun postUpload(url: String, key: String, rider: String, name: String, content: String): Boolean = try {
    val body = """{"key":${jsonString(key)},"rider":${jsonString(rider)},"name":${jsonString(name)},"content":${jsonString(content)}}"""
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(body.toByteArray()) }
        c.responseCode in 200..299 && c.inputStream.bufferedReader().readText().replace(" ", "").contains("\"ok\":true")
    } finally {
        c.disconnect()
    }
} catch (e: Exception) { // offline, timeout, wrong URL: the file stays in the outbox
    false
}

fun jsonString(s: String): String = buildString {
    append('"')
    for (ch in s) when {
        ch == '"' -> append("\\\"")
        ch == '\\' -> append("\\\\")
        ch == '\n' -> append("\\n")
        ch == '\r' -> append("\\r")
        ch == '\t' -> append("\\t")
        ch < ' ' -> append("\\u%04x".format(ch.code))
        else -> append(ch)
    }
    append('"')
}
