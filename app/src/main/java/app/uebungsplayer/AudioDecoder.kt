package app.uebungsplayer

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Dekodiert einen Zeitbereich einer Audiodatei in Mono-Samples (Float, Kanäle gemittelt).
 *
 * MediaExtractor springt nur auf Synchronisationspunkte. Deshalb wird etwas vor dem Anfang
 * aufgesetzt (Vorlauf, damit z. B. MP3-Decoder eingeschwungen sind) und anhand der Zeitstempel
 * samplegenau auf [startMs, endMs) zugeschnitten.
 */
object AudioDecoder {

    class Pcm(val samples: FloatArray, val sampleRate: Int)

    private const val PREROLL_US = 500_000L
    private const val TIMEOUT_US = 10_000L

    /** Läuft im Hintergrund; [cancelled] wird regelmäßig abgefragt. */
    fun decode(ctx: Context, uri: Uri, startMs: Int, endMs: Int, cancelled: () -> Boolean): Pcm {
        val startUs = startMs * 1000L
        val endUs = endMs * 1000L
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("keine Audiospur")
            ex.selectTrack(track)
            val inFormat = ex.getTrackFormat(track)
            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false

            ex.seekTo(maxOf(0L, startUs - PREROLL_US), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(inFormat, null, null, 0)
            c.start()

            val out = FloatCollector(((endMs - startMs) / 1000.0 * sampleRate).toInt() + 1024)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (cancelled()) throw AudioAnalysis.CancelledException()
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        // Nach dem Ende nur noch etwas weiterlesen (Decoder-Verzögerung), dann Schluss
                        if (n < 0 || ex.sampleTime > endUs + PREROLL_US) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = c.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val reachedEnd = append(
                            buf.slice().order(ByteOrder.nativeOrder()), isFloat, channels, sampleRate,
                            info.presentationTimeUs, startUs, endUs, out
                        )
                        if (reachedEnd) outputDone = true
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            return Pcm(out.toArray(), sampleRate)
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            ex.release()
        }
    }

    /** Hängt die Frames im Bereich [startUs, endUs) gemittelt an; true, wenn das Ende erreicht ist. */
    private fun append(
        buf: ByteBuffer, isFloat: Boolean, channels: Int, sampleRate: Int,
        ptsUs: Long, startUs: Long, endUs: Long, out: FloatCollector
    ): Boolean {
        val ch = maxOf(1, channels)
        val frames = if (isFloat) buf.remaining() / (4 * ch) else buf.remaining() / (2 * ch)
        val fb = if (isFloat) buf.asFloatBuffer() else null
        val sb = if (isFloat) null else buf.asShortBuffer()
        for (i in 0 until frames) {
            val t = ptsUs + i * 1_000_000L / sampleRate
            if (t >= endUs) return true
            if (t < startUs) continue
            var sum = 0f
            for (k in 0 until ch) {
                val idx = i * ch + k
                sum += if (fb != null) fb.get(idx) else sb!!.get(idx) / 32768f
            }
            out.add(sum / ch)
        }
        return false
    }

    private class FloatCollector(initial: Int) {
        private var data = FloatArray(maxOf(initial, 1024))
        private var size = 0

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
