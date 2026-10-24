package app.uebungsplayer

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.widget.CompoundButtonCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Analyse-Bereich im Bearbeiten-Popup eines Ausschnitts: Button „Analysieren“, Fortschritt und
 * Ergebnisse (Stimmung, drei Tempo- und drei Tonartvorschläge mit Vertrauensfarbe).
 *
 * Analysiert wird der Bereich, der gerade in den Zeitfeldern steht. Das Ergebnis wird erst mit
 * „OK“ übernommen ([result]); passt es dann nicht mehr zu Anfang und Ende, verfällt es.
 */
class AnalysisPanel(
    private val act: AppCompatActivity,
    parent: LinearLayout,
    seg: Segment,
    private val startF: EditText,
    private val endF: EditText,
    private val speedF: EditText
) {
    /** Hinweise, die nur direkt nach einer Analyse gezeigt werden (nicht gespeichert). */
    private class Notes(val tuningFailed: Boolean, val tempoMsg: Int?, val keyFailed: Boolean)

    private val dp = act.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var pending: SegmentAnalysis? = seg.validAnalysis()
    private var notes: Notes? = null
    private var cancel: AtomicBoolean? = null
    private var busy = false

    private val button: Button
    private val progress: ProgressBar
    private val status: TextView
    private val stale: TextView
    private val results = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private var tempoRadios: List<AppCompatRadioButton> = emptyList()
    private var atSpeed: TextView? = null

    init {
        parent.addView(label(R.string.analysis_head).apply { setPadding(0, px(20), 0, 0) })

        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        button = Button(act, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.analyze)
            isAllCaps = false
            textSize = 18f
            setTextColor(Settings.accent)
            setOnClickListener { start() }
        }
        progress = ProgressBar(act).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Settings.accent)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(px(28), px(28)).apply { marginStart = px(8) }
        }
        status = TextView(act).apply {
            setText(R.string.analyzing)
            textSize = 14f
            visibility = View.GONE
            setPadding(px(8), 0, 0, 0)
        }
        row.addView(button)
        row.addView(progress)
        row.addView(status)
        parent.addView(row)

        stale = TextView(act).apply {
            setText(R.string.analysis_stale)
            textSize = 14f
            visibility = View.GONE
        }
        parent.addView(stale)
        parent.addView(results)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateStale()
        }
        startF.addTextChangedListener(watcher)
        endF.addTextChangedListener(watcher)
        speedF.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateAtSpeed()
        })

        updateButton()
        render()
        updateStale()
    }

    /** Ergebnis für „OK“ (inklusive gewähltem Tempo); der Aufrufer prüft, ob es zum Bereich passt. */
    fun result(): SegmentAnalysis? = pending

    /** Popup geschlossen: laufende Analyse abbrechen, ihr Ergebnis verwerfen. */
    fun cancel() {
        cancel?.set(true)
    }

    // ------------------------------------------------------------ Analyse starten

    private fun start() {
        val t0 = parseTime(startF.text.toString())
        val t1 = parseTime(endF.text.toString())
        if (t0 == null || t1 == null || t1 > Engine.duration() || t1 - t0 < 100) {
            toast(R.string.err_range)
            return
        }
        if (t1 - t0 > MAX_MS) {
            toast(R.string.analysis_too_long)
            return
        }
        val uri: Uri = Engine.trackUri() ?: return
        val doTuning = Settings.analyzeTuning
        val doTempo = Settings.analyzeTempo
        val doKey = Settings.analyzeKey
        cancel?.set(true)
        val c = AtomicBoolean(false)
        cancel = c
        setBusy(true)
        val appCtx = act.applicationContext

        EXECUTOR.execute {
            var res: AudioAnalysis.Result? = null
            var failed = false
            try {
                val pcm = AudioDecoder.decode(appCtx, uri, t0, t1) { c.get() }
                val x = AudioAnalysis.decimate(pcm.samples, pcm.sampleRate)
                val sr = AudioAnalysis.decimatedRate(pcm.sampleRate)
                res = AudioAnalysis.analyze(x, sr, doTuning, doTempo, doKey) { c.get() }
            } catch (e: AudioAnalysis.CancelledException) {
                // Popup geschlossen oder neue Analyse gestartet
            } catch (e: Exception) {
                failed = true
            } catch (e: OutOfMemoryError) {
                failed = true
            }
            handler.post {
                if (!c.get()) finish(res, failed, t0, t1, doTuning, doKey)
            }
        }
    }

    private fun finish(res: AudioAnalysis.Result?, failed: Boolean, t0: Int, t1: Int, doTuning: Boolean, doKey: Boolean) {
        setBusy(false)
        if (failed || res == null) {
            toast(R.string.analysis_failed)
            return
        }
        when {
            res.tooShort -> toast(R.string.analysis_too_short)
            res.silent -> toast(R.string.analysis_silent)
            else -> {
                pending = SegmentAnalysis(
                    startMs = t0,
                    endMs = t1,
                    tuningHz = res.tuningHz.takeUnless { it.isNaN() },
                    tuningTolHz = res.tuningTolHz.takeUnless { it.isNaN() },
                    tempos = res.tempo?.map { TempoEstimate(it.bpm, it.confidence) } ?: emptyList(),
                    tempoChoice = 0,
                    keys = res.keys?.map { KeyEstimate(it.tonic, it.minor, it.confidence) } ?: emptyList()
                )
                notes = Notes(
                    tuningFailed = doTuning && res.tuningHz.isNaN(),
                    tempoMsg = when {
                        res.tempoTooShort -> R.string.tempo_too_short
                        res.tempoNoPulse -> R.string.tempo_no_pulse
                        else -> null
                    },
                    keyFailed = doKey && res.keys == null
                )
                render()
                updateStale()
            }
        }
    }

    // ------------------------------------------------------------ Anzeige

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        status.visibility = if (b) View.VISIBLE else View.GONE
        updateButton()
    }

    /** Ausgegraut, wenn in den Einstellungen keine Analyse gewählt ist (oder eine gerade läuft). */
    private fun updateButton() {
        val enabled = Settings.anyAnalysis && !busy
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    private fun render() {
        results.removeAllViews()
        tempoRadios = emptyList()
        atSpeed = null
        val a = pending ?: return
        val n = notes

        if (Settings.analyzeTuning) {
            if (a.tuningHz != null) {
                results.addView(label(R.string.an_tuning))
                results.addView(value(act.getString(R.string.tuning_value, a.tuningHz, a.tuningTolHz ?: 0.0)))
            } else if (n?.tuningFailed == true) {
                results.addView(label(R.string.an_tuning))
                results.addView(value(act.getString(R.string.not_determinable)))
            }
        }

        if (Settings.analyzeTempo) {
            if (a.tempos.isNotEmpty()) {
                results.addView(label(R.string.an_tempo))
                val radios = ArrayList<AppCompatRadioButton>()
                a.tempos.forEachIndexed { i, t ->
                    val rb = AppCompatRadioButton(act).apply {
                        text = act.getString(R.string.tempo_value, t.bpm)
                        textSize = 18f
                        isChecked = i == a.tempoChoice
                        CompoundButtonCompat.setButtonTintList(this, ColorStateList.valueOf(Settings.accent))
                        setOnClickListener { choose(i) }
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    radios.add(rb)
                    results.addView(candidateRow(rb, t.confidence))
                }
                tempoRadios = radios
                val tv = TextView(act).apply { textSize = 14f }
                atSpeed = tv
                results.addView(tv)
                updateAtSpeed()
            } else if (n?.tempoMsg != null) {
                results.addView(label(R.string.an_tempo))
                results.addView(value(act.getString(n.tempoMsg)))
            }
        }

        if (Settings.analyzeKey) {
            if (a.keys.isNotEmpty()) {
                results.addView(label(R.string.an_key))
                for (k in a.keys) {
                    val tv = TextView(act).apply {
                        text = keyName(k)
                        textSize = 18f
                        setPadding(px(8), px(6), 0, px(6))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    results.addView(candidateRow(tv, k.confidence))
                }
                if (a.endMs - a.startMs < 4000) {
                    results.addView(TextView(act).apply { setText(R.string.key_short_hint); textSize = 14f })
                }
            } else if (n?.keyFailed == true) {
                results.addView(label(R.string.an_key))
                results.addView(value(act.getString(R.string.not_determinable)))
            }
        }
    }

    private fun choose(i: Int) {
        val a = pending ?: return
        pending = a.copy(tempoChoice = i)
        tempoRadios.forEachIndexed { j, rb -> rb.isChecked = j == i }
        updateAtSpeed()
    }

    /** „Bei 85 %: 81,7 BPM“ – folgt dem Tempofeld des Popups. */
    private fun updateAtSpeed() {
        val tv = atSpeed ?: return
        val sp = speedF.text.toString().toIntOrNull()?.takeIf { it in 50..200 }
        val bpm = pending?.chosenBpm
        if (sp == null || bpm == null) {
            tv.visibility = View.GONE
        } else {
            tv.visibility = View.VISIBLE
            tv.text = act.getString(R.string.tempo_at_speed, sp, bpm * sp / 100.0)
        }
    }

    /** Ergebnisse abschwächen, sobald Anfang oder Ende nicht mehr zum analysierten Bereich passen. */
    private fun updateStale() {
        val a = pending
        val ok = a == null || run {
            val t0 = parseTime(startF.text.toString())
            val t1 = parseTime(endF.text.toString())
            t0 != null && t1 != null && a.matches(t0, t1)
        }
        stale.visibility = if (ok) View.GONE else View.VISIBLE
        results.alpha = if (ok) 1f else 0.4f
    }

    private fun keyName(k: KeyEstimate): String {
        val names = act.resources.getStringArray(if (k.minor) R.array.key_minor_names else R.array.key_major_names)
        return act.getString(if (k.minor) R.string.key_minor else R.string.key_major, names[k.tonic])
    }

    private fun candidateRow(content: View, confidence: Double): LinearLayout =
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(content)
            addView(chip(confidence))
        }

    /** Vertrauen als Prozentwert auf stufenlos eingefärbtem Feld (rot – gelb – grün). */
    private fun chip(confidence: Double): TextView {
        val pct = (confidence * 100).roundToInt()
        return TextView(act).apply {
            text = act.getString(R.string.confidence_value, pct)
            contentDescription = act.getString(R.string.cd_confidence, pct)
            textSize = 14f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(confidenceColor(confidence))
            }
            layoutParams = LinearLayout.LayoutParams(px(64), px(28)).apply { marginStart = px(8) }
        }
    }

    private fun label(res: Int) = TextView(act).apply {
        setText(res)
        textSize = 14f
        setPadding(0, px(8), 0, 0)
    }

    private fun value(text: String) = TextView(act).apply {
        this.text = text
        textSize = 18f
        setPadding(px(8), px(4), 0, px(4))
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun toast(res: Int) = Toast.makeText(act, res, Toast.LENGTH_SHORT).show()

    companion object {
        /** Längster analysierbarer Ausschnitt (Speicherbedarf der dekodierten Samples). */
        private const val MAX_MS = 10 * 60 * 1000

        /** Eigener Hintergrundthread; eine neue Analyse bricht die vorige ab. */
        private val EXECUTOR = Executors.newSingleThreadExecutor()

        /** Farbton von Rot (0) über Gelb (0,5) nach Grün (1); mittlere Sättigung, gut lesbar mit Schwarz. */
        fun confidenceColor(c: Double): Int =
            Color.HSVToColor(floatArrayOf((120.0 * c.coerceIn(0.0, 1.0)).toFloat(), 0.6f, 0.9f))
    }
}
