package app.uebungsplayer

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Die Wiedergabe lebt prozessweit (nicht in der Activity). Ein Vordergrunddienst
 * (PlaybackService) hält den Prozess am Leben, solange abgespielt wird – auch bei gesperrtem Gerät.
 * Alle Methoden werden auf dem Hauptthread aufgerufen.
 */
object Engine {

    private lateinit var app: Context
    private var inited = false
    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var player: MediaPlayer? = null

    var track: Track? = null
        private set
    var title: String = ""
        private set
    var ann = Annotations()
        private set
    var activeSegment: Segment? = null
    var pendingStartMs: Int? = null
    var loop = false
        private set
    var speed = 100
        private set

    /** Wird auf dem Hauptthread aufgerufen, wenn sich Stück, Liste oder Auswahl ändern. */
    var onChange: (() -> Unit)? = null

    private var loopGuardUntil = 0L
    private var seekTarget = 0
    private var seekTime = 0L

    fun init(ctx: Context) {
        if (inited) return
        app = ctx.applicationContext
        loop = prefs().getBoolean("loop", false)
        Settings.load(app)
        inited = true
    }

    private fun prefs() = app.getSharedPreferences("app", Context.MODE_PRIVATE)
    private fun notifyChange() { onChange?.invoke() }

    val loaded: Boolean get() = player != null
    fun duration(): Int = player?.duration ?: 0
    fun isPlaying(): Boolean = player?.isPlaying == true

    fun position(): Int {
        val p = player ?: return 0
        return if (SystemClock.uptimeMillis() - seekTime < 150) seekTarget else p.currentPosition
    }

    fun items(): List<Item> = ann.items()

    // ------------------------------------------------------------ Stück öffnen

    fun open(t: Track, addToHistory: Boolean = true): Boolean {
        pause()
        player?.release()
        player = null
        track = t
        ann = Annotations()
        pendingStartMs = null
        activeSegment = null
        title = t.name.substringBeforeLast('.')

        val uri = Saf.docUri(Uri.parse(t.tree), t.docId)
        val mp = MediaPlayer()
        try {
            mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(app, uri)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            track = null
            title = ""
            notifyChange()
            return false
        }
        mp.setOnCompletionListener { onCompletion() }
        player = mp
        readTitle(uri)
        if (addToHistory) History.add(app, t)

        io.execute {
            val text = try {
                val tree = Uri.parse(t.tree)
                val e = Saf.list(app, tree, t.parentId).firstOrNull { it.name == yamlName(t) }
                if (e != null) Saf.readText(app, Saf.docUri(tree, e.docId)) else null
            } catch (e: Exception) {
                null
            }
            val parsed = if (text != null) Yaml.parse(text) else Annotations()
            handler.post {
                if (track === t) {
                    ann = parsed
                    notifyChange()
                }
            }
        }
        notifyChange()
        return true
    }

    private fun yamlName(t: Track) = t.name + ".yaml"

    private fun readTitle(uri: Uri) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(app, uri)
            val tag = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            if (!tag.isNullOrBlank()) title = tag
        } catch (e: Exception) {
            // Titel-Tag ist optional
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    // ------------------------------------------------------------ Wiedergabe

    fun setLoop(on: Boolean) {
        loop = on
        prefs().edit().putBoolean("loop", on).apply()
    }

    /** Tempo in Prozent (50–200); die Tonhöhe bleibt unverändert. */
    fun setSpeed(percent: Int) {
        speed = percent.coerceIn(50, 200)
        if (isPlaying()) applySpeed()
    }

    // setPlaybackParams darf nur bei laufender Wiedergabe aufgerufen werden, sonst startet
    // MediaPlayer auf manchen Geräten von selbst.
    private fun applySpeed() {
        val p = player ?: return
        try {
            p.playbackParams = PlaybackParams().setSpeed(speed / 100f).setPitch(1f)
        } catch (e: Exception) {
            // Gerät unterstützt das Tempo nicht
        }
    }

    fun togglePlay() {
        if (isPlaying()) pause() else play()
    }

    fun play() {
        val p = player ?: return
        if (p.isPlaying) return
        val s = activeSegment
        if (s != null) {
            if (p.currentPosition < s.startMs || p.currentPosition >= s.endMs) seekRaw(s.startMs)
        } else if (p.currentPosition >= p.duration - 100) {
            seekRaw(0)
        }
        p.start()
        applySpeed()
        try {
            ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
        } catch (e: Exception) {
            // Wiedergabe läuft dann nur im Vordergrund sicher weiter
        }
        startTicker()
        notifyChange()
    }

    fun pause() {
        val p = player
        if (p != null && p.isPlaying) p.pause()
        handler.removeCallbacks(ticker)
        if (inited) app.stopService(Intent(app, PlaybackService::class.java))
        notifyChange()
    }

    /** Stoppt und setzt das Musikstück auf den Anfang (hebt auch die Ausschnittauswahl auf). */
    fun rewindToStart() {
        activeSegment = null
        pause()
        jumpTo(0)
    }

    fun playSegment(s: Segment) {
        activeSegment = s
        setSpeed(s.speed)
        jumpTo(s.startMs, keepSegment = true)
        play()
    }

    private fun onCompletion() {
        val p = player ?: return
        val s = activeSegment
        if (s != null && loop) {
            seekRaw(s.startMs)
            p.start()
            applySpeed()
            startTicker()
            return
        }
        if (s != null) seekRaw(s.startMs)
        handler.removeCallbacks(ticker)
        app.stopService(Intent(app, PlaybackService::class.java))
        notifyChange()
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (!p.isPlaying) return
            val s = activeSegment
            val now = SystemClock.uptimeMillis()
            if (s != null && now >= loopGuardUntil && p.currentPosition >= s.endMs) {
                if (loop) {
                    loopGuardUntil = now + 250
                    seekRaw(s.startMs)
                } else {
                    pause()
                    seekRaw(s.startMs)
                    return
                }
            }
            handler.postDelayed(this, 30)
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun seekRaw(ms: Int) {
        val p = player ?: return
        seekTarget = ms
        seekTime = SystemClock.uptimeMillis()
        if (Build.VERSION.SDK_INT >= 26) p.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) else p.seekTo(ms)
    }

    fun jumpTo(ms: Int, keepSegment: Boolean = false) {
        val p = player ?: return
        val t = ms.coerceIn(0, p.duration)
        seekRaw(t)
        val s = activeSegment
        if (!keepSegment && s != null && (t < s.startMs || t >= s.endMs)) activeSegment = null
        notifyChange()
    }

    private fun jumpTargets(): List<Int> =
        (ann.markers.map { it.ms } + ann.segments.map { it.startMs }).distinct().sorted()

    /** false, wenn es kein weiteres Ziel gibt. */
    fun jumpNext(): Boolean {
        val pos = position()
        val t = jumpTargets().firstOrNull { it > pos + 300 } ?: return false
        jumpTo(t)
        return true
    }

    fun jumpPrev() {
        val pos = position()
        jumpTo(jumpTargets().lastOrNull { it < pos - 1000 } ?: 0)
    }

    // ------------------------------------------------------------ Ausschnitte und Marker

    fun nextName(prefix: String, used: List<String>): String {
        var n = 1
        while (used.contains("$prefix $n")) n++
        return "$prefix $n"
    }

    fun beginSegment() {
        pendingStartMs = position()
        notifyChange()
    }

    fun cancelPending() {
        pendingStartMs = null
        notifyChange()
    }

    /** null, wenn das Ende zu dicht am Anfang liegt. */
    fun endSegment(prefix: String): Segment? {
        val st = pendingStartMs ?: return null
        val pos = position()
        if (pos - st < 200) return null
        val s = Segment(nextName(prefix, ann.segments.map { it.name }), st, pos, speed)
        pendingStartMs = null
        ann.segments.add(s)
        commit()
        return s
    }

    fun addMarker(prefix: String): Marker {
        val m = Marker(nextName(prefix, ann.markers.map { it.name }), position())
        ann.markers.add(m)
        commit()
        return m
    }

    fun deleteItem(item: Item) {
        if (item is Segment) {
            if (activeSegment === item) activeSegment = null
            ann.segments.removeAll { it === item }
        } else {
            ann.markers.removeAll { it === item }
        }
        commit()
    }

    /** Übernimmt das aktuell eingestellte Tempo in den Ausschnitt. */
    fun takeSpeed(s: Segment) {
        s.speed = speed
        commit()
    }

    /** Nach jeder Änderung an Ausschnitten/Markern: sortieren, speichern, Anzeige aktualisieren. */
    fun commit() {
        ann.segments.sortBy { it.startMs }
        ann.markers.sortBy { it.ms }
        save()
        notifyChange()
    }

    private fun save() {
        val t = track ?: return
        val text = Yaml.write(ann)
        io.execute {
            val ok = try {
                Saf.writeText(app, Uri.parse(t.tree), t.parentId, yamlName(t), text)
            } catch (e: Exception) {
                false
            }
            if (!ok) handler.post { Toast.makeText(Lang.wrap(app), R.string.err_save, Toast.LENGTH_LONG).show() }
        }
    }
}
