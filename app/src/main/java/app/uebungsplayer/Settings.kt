package app.uebungsplayer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.CompoundButtonCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * App-Einstellungen: Akzentfarbe (Ausschnitte, Auswahl, Schleife, Regler) und die im
 * Ausschnitt-Popup angebotenen Analysen. Die Sprache verwaltet [Lang].
 */
object Settings {
    val PALETTE = intArrayOf(
        0xFFFF9800.toInt(), // Orange (Standard)
        0xFFE53935.toInt(), // Rot
        0xFFD81B60.toInt(), // Pink
        0xFF8E24AA.toInt(), // Violett
        0xFF43A047.toInt(), // Grün
        0xFFFDD835.toInt(), // Gelb
        0xFF8D6E63.toInt(), // Braun
        0xFF78909C.toInt()  // Grau
    )

    var accent: Int = PALETTE[0]
        private set

    var analyzeTuning = true
        private set
    var analyzeTempo = true
        private set
    var analyzeKey = true
        private set

    /** Mindestens eine Analyse gewählt (sonst ist der Analyse-Button ausgegraut). */
    val anyAnalysis: Boolean get() = analyzeTuning || analyzeTempo || analyzeKey

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
        accent = p.getInt("accent", PALETTE[0])
        analyzeTuning = p.getBoolean("analyze_tuning", true)
        analyzeTempo = p.getBoolean("analyze_tempo", true)
        analyzeKey = p.getBoolean("analyze_key", true)
    }

    fun setAnalyses(ctx: Context, tuning: Boolean, tempo: Boolean, key: Boolean) {
        analyzeTuning = tuning
        analyzeTempo = tempo
        analyzeKey = key
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putBoolean("analyze_tuning", tuning)
            .putBoolean("analyze_tempo", tempo)
            .putBoolean("analyze_key", key)
            .apply()
    }

    fun setAccent(ctx: Context, color: Int) {
        accent = color
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putInt("accent", color).apply()
    }

    /** Akzentfarbe mit Alpha für Hintergründe (z. B. gewählte Listenzeile). */
    fun accentSoft(): Int = (accent and 0x00FFFFFF) or 0x40000000
}

/** Normale Textfarbe des aktuellen Themes (hell/dunkel) als ColorStateList (inkl. deaktiviert). */
fun AppCompatActivity.primaryTextCsl(): ColorStateList {
    val tv = TypedValue()
    theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
    return if (tv.resourceId != 0) ContextCompat.getColorStateList(this, tv.resourceId)!!
    else ColorStateList.valueOf(tv.data)
}

/**
 * Einstellungsbildschirm. Änderungen werden erst mit „Bestätigen“ übernommen;
 * „Abbrechen“, der Zurückpfeil und die Zurück-Geste verwerfen sie.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var grid: GridLayout
    private lateinit var langGroup: RadioGroup
    private lateinit var btnConfirm: Button
    private lateinit var cbTuning: CheckBox
    private lateinit var cbTempo: CheckBox
    private lateinit var cbKey: CheckBox
    private var pendingAccent = 0

    private val langButtons = mapOf("" to R.id.langSystem, "de" to R.id.langDe, "en" to R.id.langEn)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        Settings.load(this)
        pendingAccent = savedInstanceState?.getInt(KEY_ACCENT, Settings.accent) ?: Settings.accent

        grid = findViewById(R.id.swatches)
        langGroup = findViewById(R.id.languageGroup)
        btnConfirm = findViewById(R.id.btnConfirm)
        cbTuning = findViewById(R.id.analysisTuning)
        cbTempo = findViewById(R.id.analysisTempo)
        cbKey = findViewById(R.id.analysisKey)

        if (savedInstanceState == null) {
            langGroup.check(langButtons[Lang.current()] ?: R.id.langSystem)
            // Nach einer Drehung stellen die Ankreuzfelder ihren Zustand selbst wieder her
            cbTuning.isChecked = Settings.analyzeTuning
            cbTempo.isChecked = Settings.analyzeTempo
            cbKey.isChecked = Settings.analyzeKey
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { finish() }
        btnConfirm.setOnClickListener { confirm() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })
        build()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACCENT, pendingAccent)
    }

    private fun confirm() {
        Settings.setAccent(this, pendingAccent)
        Settings.setAnalyses(this, cbTuning.isChecked, cbTempo.isChecked, cbKey.isChecked)
        val chosen = langButtons.entries.firstOrNull { it.value == langGroup.checkedRadioButtonId }?.key ?: ""
        val changed = chosen != Lang.current()
        finish()
        // Erst nach finish(): Die Activities der App werden in der neuen Sprache neu aufgebaut;
        // die Wiedergabe läuft in der Engine ungestört weiter.
        if (changed) Lang.set(chosen)
    }

    /** Farbfelder, Auswahlringe, Radiobuttons und Bestätigen-Schaltfläche in der gewählten Farbe zeigen. */
    private fun build() {
        grid.removeAllViews()
        val dp = resources.displayMetrics.density
        val ring = primaryTextCsl().defaultColor
        for (c in Settings.PALETTE) {
            val v = View(this)
            val lp = GridLayout.LayoutParams()
            lp.width = (68 * dp).toInt()
            lp.height = (68 * dp).toInt()
            lp.setMargins((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            v.layoutParams = lp
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                if (c == pendingAccent) setStroke((6 * dp).toInt(), ring)
            }
            v.setOnClickListener {
                pendingAccent = c
                build()
            }
            grid.addView(v)
        }
        val tint = ColorStateList.valueOf(pendingAccent)
        for (id in langButtons.values) {
            CompoundButtonCompat.setButtonTintList(findViewById<RadioButton>(id), tint)
        }
        for (cb in listOf(cbTuning, cbTempo, cbKey)) CompoundButtonCompat.setButtonTintList(cb, tint)
        btnConfirm.setTextColor(pendingAccent)
    }

    companion object {
        private const val KEY_ACCENT = "pendingAccent"
    }
}
