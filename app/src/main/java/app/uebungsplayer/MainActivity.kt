package app.uebungsplayer

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Reine Oberfläche; Wiedergabe und Daten liegen in [Engine] und überleben die Activity. */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private var shownSpeed = -1
    private var shownPlaying: Boolean? = null

    private lateinit var titleView: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var timeView: TextView
    private lateinit var timeline: TimelineView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnSegment: ImageButton
    private lateinit var btnLoop: ImageButton
    private lateinit var speedSeek: SeekBar
    private lateinit var btnSpeedReset: View
    private lateinit var speedValue: TextView
    private lateinit var controls: List<View>
    private lateinit var adapter: ItemAdapter

    private val pickTrack =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val d = r.data
            if (r.resultCode == RESULT_OK && d != null) {
                FileBrowserActivity.trackFromIntent(d)?.let { openTrack(it) }
            }
        }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* Dienst läuft auch ohne */ }

    // ---------------------------------------------------------------- Lebenszyklus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(this)
        setContentView(R.layout.activity_main)

        titleView = findViewById(R.id.title)
        seekBar = findViewById(R.id.seek)
        timeView = findViewById(R.id.time)
        timeline = findViewById(R.id.timeline)
        btnPlay = findViewById(R.id.btnPlay)
        btnSegment = findViewById(R.id.btnSegment)
        btnLoop = findViewById(R.id.btnLoop)
        speedSeek = findViewById(R.id.speedSeek)
        btnSpeedReset = findViewById(R.id.btnSpeedReset)
        speedValue = findViewById(R.id.speedValue)

        val btnPrev = findViewById<ImageButton>(R.id.btnPrev)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val btnFwd = findViewById<ImageButton>(R.id.btnFwd)
        val btnNext = findViewById<ImageButton>(R.id.btnNext)
        val btnMarker = findViewById<ImageButton>(R.id.btnMarker)
        val btnSpeedMinus = findViewById<ImageButton>(R.id.btnSpeedMinus)
        val btnSpeedPlus = findViewById<ImageButton>(R.id.btnSpeedPlus)
        controls = listOf(
            btnPlay, btnPrev, btnBack, btnFwd, btnNext, btnSegment, btnMarker,
            btnLoop, btnSpeedMinus, btnSpeedPlus, btnSpeedReset, speedSeek, seekBar
        )

        findViewById<View>(R.id.btnChoose).setOnClickListener {
            pickTrack.launch(Intent(this, FileBrowserActivity::class.java))
        }
        findViewById<View>(R.id.btnHistory).setOnClickListener { showHistory() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnPlay.setOnClickListener { Engine.togglePlay() }
        btnPlay.setOnLongClickListener {
            Engine.rewindToStart()
            true
        }
        btnPrev.setOnClickListener { Engine.jumpPrev() }
        btnNext.setOnClickListener {
            if (!Engine.jumpNext()) Toast.makeText(this, R.string.no_next, Toast.LENGTH_SHORT).show()
        }
        btnBack.setOnClickListener { Engine.jumpTo(Engine.position() - 5000) }
        btnFwd.setOnClickListener { Engine.jumpTo(Engine.position() + 5000) }
        btnSegment.setOnClickListener { onSegmentButton() }
        btnSegment.setOnLongClickListener {
            if (Engine.pendingStartMs != null) {
                Engine.cancelPending()
                true
            } else {
                false
            }
        }
        btnMarker.setOnClickListener {
            if (Engine.loaded) showEditDialog(Engine.addMarker(getString(R.string.default_marker)))
        }
        btnLoop.setOnClickListener {
            Engine.setLoop(!Engine.loop)
            updateLoopButton()
        }

        // Tempo in 2-%-Schritten
        btnSpeedMinus.setOnClickListener { Engine.setSpeed(Engine.speed - 2); updateSpeedUi() }
        btnSpeedPlus.setOnClickListener { Engine.setSpeed(Engine.speed + 2); updateSpeedUi() }
        btnSpeedReset.setOnClickListener { Engine.setSpeed(100); updateSpeedUi() }
        speedSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    Engine.setSpeed(50 + progress * 2)
                    shownSpeed = Engine.speed
                    speedValue.text = getString(R.string.speed_value, shownSpeed)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // Zeitleiste: Schieberegler und Marker-/Ausschnittsleiste stellen beide die Position ein
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    timeView.text = "${fmt(progress)} / ${fmt(sb.max)}"
                    timeline.setPosition(progress)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                userSeeking = false
                Engine.jumpTo(sb.progress)
            }
        })
        timeline.onSeek = { ms, done ->
            if (Engine.loaded) {
                userSeeking = !done
                seekBar.progress = ms
                timeView.text = "${fmt(ms)} / ${fmt(seekBar.max)}"
                timeline.setPosition(ms)
                if (done) Engine.jumpTo(ms)
            }
        }

        adapter = ItemAdapter()
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.emptyView = findViewById(R.id.emptyList)

        // Berechtigung für die Wiedergabe-Benachrichtigung (Android 13+) gleich beim Start erfragen
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Läuft die Sitzung noch (z. B. Wiedergabe im gesperrten Zustand), wird sie weiterverwendet;
        // sonst das zuletzt gewählte Stück aus der Historie vorwählen.
        if (!Engine.loaded) {
            History.load(this).firstOrNull()?.let { openTrack(it, addToHistory = false) }
        }
    }

    override fun onStart() {
        super.onStart()
        Settings.load(this) // Akzentfarbe kann in den Einstellungen geändert worden sein
        Engine.onChange = { refreshAll() }
        refreshAll()
        handler.removeCallbacks(uiTicker)
        handler.post(uiTicker)
    }

    override fun onStop() {
        super.onStop()
        Engine.onChange = null
        handler.removeCallbacks(uiTicker)
        // Die Wiedergabe läuft bewusst weiter (Vordergrunddienst).
    }

    private val uiTicker = object : Runnable {
        override fun run() {
            if (Engine.loaded) showPos(Engine.position())
            updatePlayButton()
            if (Engine.speed != shownSpeed) updateSpeedUi()
            handler.postDelayed(this, 100)
        }
    }

    // ---------------------------------------------------------------- Aktionen

    private fun openTrack(t: Track, addToHistory: Boolean = true) {
        if (!Engine.open(t, addToHistory)) {
            Toast.makeText(this, R.string.err_open, Toast.LENGTH_LONG).show()
        }
    }

    private fun showHistory() {
        val h = History.load(this)
        if (h.isEmpty()) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val names = ArrayAdapter(this, R.layout.row_history, R.id.historyText, h.map { it.name })
        val dlg = AlertDialog.Builder(this)
            .setTitle(R.string.history)
            .setAdapter(names) { _, i -> openTrack(h[i]) }
            .create()
        dlg.show()
        // Der Abstand steckt im Außenrand jeder Zeile und ist daher auch bei Zeilenumbrüchen konstant
        dlg.listView.divider = null
        dlg.listView.dividerHeight = 0
    }

    private fun onSegmentButton() {
        if (!Engine.loaded) return
        if (Engine.pendingStartMs == null) {
            Engine.beginSegment()
            return
        }
        val s = Engine.endSegment(getString(R.string.default_segment))
        if (s == null) {
            Toast.makeText(this, R.string.end_before_start, Toast.LENGTH_SHORT).show()
        } else {
            showEditDialog(s)
        }
    }

    /**
     * Popup zum Bearbeiten von Name, Zeitpunkten und (bei Ausschnitten) Tempo.
     * Die Musik pausiert, solange es offen ist, und läuft danach weiter.
     */
    private fun showEditDialog(item: Item) {
        val wasPlaying = Engine.isPlaying()
        if (wasPlaying) Engine.pause()
        val seg = item as? Segment
        val dp = resources.displayMetrics.density

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        fun field(label: String, value: String, numeric: Boolean, time: Boolean): EditText {
            box.addView(TextView(this).apply { text = label; textSize = 14f; setPadding(0, (8 * dp).toInt(), 0, 0) })
            val e = EditText(this).apply {
                setText(value)
                setSingleLine()
                textSize = 20f
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                if (time) keyListener = DigitsKeyListener.getInstance("0123456789:.,")
            }
            box.addView(e)
            return e
        }

        val nameF = field(getString(R.string.f_name), item.name, numeric = false, time = false)
        val startF = field(
            getString(if (seg != null) R.string.f_start else R.string.f_time),
            fmtFull(item.time), numeric = false, time = true
        )
        val endF = seg?.let { field(getString(R.string.f_end), fmtFull(it.endMs), numeric = false, time = true) }
        val speedF = seg?.let { field(getString(R.string.f_speed), it.speed.toString(), numeric = true, time = false) }

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (seg != null) R.string.edit_segment else R.string.edit_marker)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { if (wasPlaying) Engine.play() }
            .create()
        dlg.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dlg.setOnShowListener {
            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val dur = Engine.duration()
                val t0 = parseTime(startF.text.toString())
                if (t0 == null || t0 > dur) {
                    toast(R.string.err_time)
                    return@setOnClickListener
                }
                if (seg != null) {
                    val t1 = parseTime(endF!!.text.toString())
                    val sp = speedF!!.text.toString().toIntOrNull()
                    if (t1 == null || t1 > dur || t1 - t0 < 100) {
                        toast(R.string.err_range)
                        return@setOnClickListener
                    }
                    if (sp == null || sp !in 50..200) {
                        toast(R.string.err_speed)
                        return@setOnClickListener
                    }
                    seg.startMs = t0
                    seg.endMs = t1
                    seg.speed = sp
                } else if (item is Marker) {
                    item.ms = t0
                }
                val n = nameF.text.toString().trim()
                if (n.isNotEmpty()) item.name = n
                Engine.commit()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun confirmDelete(item: Item) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_msg, item.name))
            .setPositiveButton(R.string.delete) { _, _ -> Engine.deleteItem(item) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- Anzeige

    private fun refreshAll() {
        val has = Engine.loaded
        controls.forEach { it.isEnabled = has }
        btnSpeedReset.alpha = if (has) 1f else 0.4f
        titleView.text = if (has) Engine.title else getString(R.string.no_track)
        seekBar.max = Engine.duration()
        applyAccent()
        timeline.setPending(Engine.pendingStartMs ?: -1)
        timeline.setData(Engine.duration(), Engine.ann.segments.toList(), Engine.ann.markers.toList())
        showPos(if (has) Engine.position() else 0)
        updatePlayButton()
        updateSegmentButton()
        updateLoopButton()
        updateSpeedUi()
        adapter.refresh()
    }

    private fun applyAccent() {
        val tint = ColorStateList.valueOf(Settings.accent)
        seekBar.progressTintList = tint
        seekBar.thumbTintList = tint
        speedSeek.progressTintList = tint
        speedSeek.thumbTintList = tint
        // Das Play-/Pause-Symbol ist immer in Akzentfarbe
        btnPlay.imageTintList = tint
        btnPlay.alpha = if (Engine.loaded) 1f else 0.4f
    }

    private fun showPos(ms: Int) {
        if (!userSeeking) seekBar.progress = ms
        val txt = "${fmt(ms)} / ${fmt(seekBar.max)}"
        if (timeView.text.toString() != txt) timeView.text = txt
        timeline.setPosition(if (Engine.loaded) ms else -1)
    }

    private fun updatePlayButton() {
        val playing = Engine.isPlaying()
        if (playing != shownPlaying) {
            shownPlaying = playing
            btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            btnPlay.contentDescription = getString(if (playing) R.string.cd_pause else R.string.cd_play)
        }
    }

    /**
     * Ausschnitt setzen: immer der Strich mit zwei Begrenzungsstrichen und einem Plus darüber.
     * Wartet ein Ausschnitt auf sein Ende, sind der waagerechte Strich und der rechte
     * Begrenzungsstrich in Akzentfarbe.
     */
    private fun updateSegmentButton() {
        val pending = Engine.pendingStartMs != null
        val csl = primaryTextCsl()
        val neutral = csl.getColorForState(
            if (Engine.loaded) intArrayOf(android.R.attr.state_enabled) else intArrayOf(-android.R.attr.state_enabled),
            csl.defaultColor
        )
        // Untere Ebene: Strich + rechter Begrenzungsstrich; obere Ebene: linker Begrenzungsstrich + Plus
        val lower = ContextCompat.getDrawable(this, R.drawable.ic_seg_set_accent)!!
        val upper = ContextCompat.getDrawable(this, R.drawable.ic_seg_set_base)!!
        val layers = LayerDrawable(arrayOf(lower, upper))
        btnSegment.imageTintList = null
        btnSegment.setImageDrawable(layers)
        // Wichtig: ImageView ruft beim Setzen mutate() auf, und LayerDrawable.mutate() ersetzt dabei seine
        // Ebenen durch Kopien. Gefärbt werden müssen deshalb die Ebenen des Layers selbst, nicht
        // die ursprünglichen Objekte (sonst bleibt das Symbol ungefärbt, also schwarz).
        layers.getDrawable(0).setTint(if (pending) Settings.accent else neutral)
        layers.getDrawable(1).setTint(neutral)
        btnSegment.invalidate()
        btnSegment.contentDescription = getString(if (pending) R.string.cd_seg_end else R.string.cd_seg_start)
    }

    /** Schleife: Symbol allein; an = Akzentfarbe, aus = normale Farbe, abgeschwächt. */
    private fun updateLoopButton() {
        val on = Engine.loop
        btnLoop.imageTintList = if (on) ColorStateList.valueOf(Settings.accent) else primaryTextCsl()
        btnLoop.alpha = if (on) 1f else 0.5f
    }

    private fun updateSpeedUi() {
        shownSpeed = Engine.speed
        speedSeek.progress = (shownSpeed - 50) / 2
        speedValue.text = getString(R.string.speed_value, shownSpeed)
    }

    /** Gemeinsame Zeitliste aus Ausschnitten und Markern, nach Anfangszeit sortiert. */
    private inner class ItemAdapter : BaseAdapter() {
        private var data: List<Item> = emptyList()

        fun refresh() {
            data = Engine.items()
            notifyDataSetChanged()
        }

        override fun getCount() = data.size
        override fun getItem(position: Int): Any = data[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.row_item, parent, false)
            val item = data[position]
            val seg = item as? Segment
            val mark = item as? Marker

            // Ausschnitt = fetter Strich (Akzentfarbe), Marker = Punkt (blau)
            val icon = v.findViewById<ImageView>(R.id.rowIcon)
            icon.setImageResource(if (seg != null) R.drawable.ic_item_seg else R.drawable.ic_item_dot)
            icon.imageTintList = ColorStateList.valueOf(if (seg != null) Settings.accent else 0xFF03A9F4.toInt())

            v.findViewById<TextView>(R.id.rowTitle).text = item.name
            v.findViewById<TextView>(R.id.rowSub).text =
                if (seg != null) "${fmt(seg.startMs, true)}–${fmt(seg.endMs, true)} · ${seg.speed} %"
                else fmt(item.time, true)
            v.findViewById<TimelineView>(R.id.rowTimeline)
                .setData(Engine.duration(), listOfNotNull(seg), listOfNotNull(mark))

            v.setBackgroundColor(
                if (seg != null && seg === Engine.activeSegment) Settings.accentSoft() else Color.TRANSPARENT
            )
            v.setOnClickListener {
                if (seg != null) Engine.playSegment(seg) else Engine.jumpTo(item.time)
            }
            val speedBtn = v.findViewById<View>(R.id.rowSpeed)
            speedBtn.visibility = if (seg != null) View.VISIBLE else View.INVISIBLE
            speedBtn.setOnClickListener { if (seg != null) Engine.takeSpeed(seg) }
            v.findViewById<View>(R.id.rowEdit).setOnClickListener { showEditDialog(item) }
            v.findViewById<View>(R.id.rowDelete).setOnClickListener { confirmDelete(item) }
            return v
        }
    }
}
