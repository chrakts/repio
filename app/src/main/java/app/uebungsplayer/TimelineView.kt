package app.uebungsplayer

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Zeichnet die Stücklänge als Leiste: Ausschnitte als Balken in der Akzentfarbe, Marker als blaue
 * Striche, optional die aktuelle Position. Unter dem Schieberegler (alle Einträge, mit Touch-Bedienung
 * über die gesamte Höhe) und in jeder Listenzeile (nur dieser Eintrag, ohne Touch) verwendet.
 */
class TimelineView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private var duration = 0
    private var segs: List<Segment> = emptyList()
    private var marks: List<Marker> = emptyList()
    private var positionMs = -1
    private var pendingMs = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private val ink by lazy { context.getColor(R.color.anthracite) }

    /** Wenn gesetzt, reagiert die Leiste auf Touch: (Millisekunde, fertig?) */
    var onSeek: ((Int, Boolean) -> Unit)? = null

    fun setData(duration: Int, segs: List<Segment>, marks: List<Marker>) {
        this.duration = duration
        this.segs = segs
        this.marks = marks
        invalidate()
    }

    /** Startpunkt eines noch offenen Ausschnitts (-1 = keiner); der Ausschnitt "wächst" mit der Position. */
    fun setPending(ms: Int) {
        if (ms != pendingMs) {
            pendingMs = ms
            invalidate()
        }
    }

    fun setPosition(ms: Int) {
        if (ms != positionMs) {
            positionMs = ms
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (duration <= 0 || w <= 0f) return
        fun px(ms: Int) = ms.toFloat() / duration * w

        // Grundlinie
        paint.color = 0x66808080
        c.drawRoundRect(0f, h / 2 - 3 * d, w, h / 2 + 3 * d, 3 * d, 3 * d, paint)

        // Ausschnitte
        paint.color = Settings.accent
        for (s in segs) {
            val l = px(s.startMs)
            val r = maxOf(px(s.endMs), l + 5 * d)
            c.drawRoundRect(l, h * 0.15f, r, h * 0.85f, 3 * d, 3 * d, paint)
        }

        // Offener Ausschnitt: wächst von seinem Anfang bis zur aktuellen Position
        if (pendingMs >= 0 && positionMs >= 0) {
            val a = px(minOf(pendingMs, positionMs))
            val b = maxOf(px(maxOf(pendingMs, positionMs)), a + 5 * d)
            paint.color = Settings.accent
            paint.alpha = 150
            c.drawRoundRect(a, h * 0.15f, b, h * 0.85f, 3 * d, 3 * d, paint)
            paint.alpha = 255
            val sx = px(pendingMs) // Begrenzungsstrich am Anfang
            c.drawRoundRect(sx - 2.5f * d, 0f, sx + 2.5f * d, h, 2 * d, 2 * d, paint)
        }

        // Marker
        paint.color = 0xFF03A9F4.toInt()
        for (m in marks) {
            val x = px(m.ms)
            c.drawRoundRect(x - 2.5f * d, 0f, x + 2.5f * d, h, 2 * d, 2 * d, paint)
        }

        // aktuelle Position
        if (positionMs >= 0) {
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            paint.color = if (night) 0xFFFFFFFF.toInt() else ink
            val x = px(positionMs)
            c.drawRoundRect(x - 1.8f * d, 0f, x + 1.8f * d, h, 1.5f * d, 1.5f * d, paint)
        }
    }

    private fun msAt(x: Float): Int =
        (x.coerceIn(0f, width.toFloat()) / width * duration).toInt()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cb = onSeek ?: return super.onTouchEvent(e)
        if (duration <= 0 || width <= 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                cb(msAt(e.x), false)
            }
            MotionEvent.ACTION_MOVE -> cb(msAt(e.x), false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cb(msAt(e.x), true)
        }
        return true
    }
}
