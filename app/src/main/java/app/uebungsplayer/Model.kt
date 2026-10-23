package app.uebungsplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Eintrag der Zeitliste: Ausschnitt oder Marker. */
sealed class Item {
    abstract var name: String
    abstract val time: Int
}

data class Segment(
    override var name: String,
    var startMs: Int,
    var endMs: Int,
    var speed: Int = 100
) : Item() {
    override val time: Int get() = startMs
}

data class Marker(override var name: String, var ms: Int) : Item() {
    override val time: Int get() = ms
}

class Annotations {
    val segments = mutableListOf<Segment>()
    val markers = mutableListOf<Marker>()

    /** Alle Einträge nach Anfangszeit sortiert (bei Gleichstand Ausschnitt vor Marker). */
    fun items(): List<Item> {
        val all = ArrayList<Item>()
        all.addAll(segments)
        all.addAll(markers)
        return all.sortedWith(compareBy<Item>({ it.time }, { if (it is Segment) 0 else 1 }))
    }
}

/** Ein Musikstück: Dokument im per SAF gewählten Ordnerbaum plus Elternordner (für die YAML-Datei). */
data class Track(val tree: String, val docId: String, val parentId: String, val name: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("tree", tree).put("doc", docId).put("parent", parentId).put("name", name)

    companion object {
        fun fromJson(o: JSONObject) =
            Track(o.getString("tree"), o.getString("doc"), o.getString("parent"), o.getString("name"))
    }
}

fun fmt(ms: Int, tenths: Boolean = false): String {
    val s = ms / 1000
    val base = String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    return if (tenths) base + "." + (ms % 1000) / 100 else base
}

/** Millisekundengenau, für das Bearbeiten-Popup: m:ss.mmm */
fun fmtFull(ms: Int): String =
    String.format(Locale.ROOT, "%d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000)

/** Akzeptiert "m:ss.mmm", "m:ss", "ss.mmm", "h:mm:ss" (Komma oder Punkt). */
fun parseTime(input: String): Int? {
    val t = input.trim().replace(',', '.')
    if (t.isEmpty()) return null
    val parts = t.split(':')
    if (parts.size > 3) return null
    var sec = 0.0
    try {
        for (p in parts) sec = sec * 60 + p.toDouble()
    } catch (e: NumberFormatException) {
        return null
    }
    if (sec.isNaN() || sec < 0 || sec > 10_000_000) return null
    return Math.round(sec * 1000).toInt()
}

object History {
    private const val KEY = "history"
    private const val MAX = 20

    fun load(ctx: Context): List<Track> {
        val s = ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { Track.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(ctx: Context, t: Track) {
        val list = load(ctx).filterNot { it.tree == t.tree && it.docId == t.docId }.toMutableList()
        list.add(0, t)
        val a = JSONArray()
        list.take(MAX).forEach { a.put(it.toJson()) }
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }
}
