package app.uebungsplayer

/**
 * Minimaler YAML-Leser/-Schreiber für genau dieses Format (keine Fremdbibliothek nötig):
 *
 * version: 2
 * segments:
 *   - name: "Ausschnitt 1"
 *     start_ms: 12000
 *     end_ms: 24000
 *     speed_percent: 85
 * markers:
 *   - name: "Marker 1"
 *     ms: 5000
 *
 * Dateien ohne speed_percent (Version 1) werden mit 100 % gelesen.
 */
object Yaml {

    fun write(a: Annotations): String {
        val sb = StringBuilder("version: 2\n")
        if (a.segments.isEmpty()) {
            sb.append("segments: []\n")
        } else {
            sb.append("segments:\n")
            for (s in a.segments) {
                sb.append("  - name: ").append(quote(s.name)).append('\n')
                sb.append("    start_ms: ").append(s.startMs).append('\n')
                sb.append("    end_ms: ").append(s.endMs).append('\n')
                sb.append("    speed_percent: ").append(s.speed).append('\n')
            }
        }
        if (a.markers.isEmpty()) {
            sb.append("markers: []\n")
        } else {
            sb.append("markers:\n")
            for (m in a.markers) {
                sb.append("  - name: ").append(quote(m.name)).append('\n')
                sb.append("    ms: ").append(m.ms).append('\n')
            }
        }
        return sb.toString()
    }

    fun parse(text: String): Annotations {
        val a = Annotations()
        var section = ""
        var name: String? = null
        var start: Int? = null
        var end: Int? = null
        var ms: Int? = null
        var speed: Int? = null

        fun flush() {
            val n = name
            val st = start
            val en = end
            val m = ms
            val sp = (speed ?: 100).coerceIn(50, 200)
            if (section == "segments" && st != null && en != null && en > st) {
                a.segments.add(Segment(n ?: "Ausschnitt ${a.segments.size + 1}", st, en, sp))
            } else if (section == "markers" && m != null) {
                a.markers.add(Marker(n ?: "Marker ${a.markers.size + 1}", m))
            }
            name = null; start = null; end = null; ms = null; speed = null
        }

        for (raw in text.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (!line.startsWith(" ") && !line.startsWith("-")) {
                flush()
                section = line.substringBefore(':').trim()
                continue
            }
            var body = line.trim()
            if (body.startsWith("- ") || body == "-") {
                flush()
                body = body.removePrefix("-").trim()
                if (body.isEmpty()) continue
            }
            val key = body.substringBefore(':').trim()
            val value = body.substringAfter(':', "").trim()
            when (key) {
                "name" -> name = unquote(value)
                "start_ms" -> start = value.toIntOrNull()
                "end_ms" -> end = value.toIntOrNull()
                "ms" -> ms = value.toIntOrNull()
                "speed_percent" -> speed = value.toIntOrNull()
            }
        }
        flush()
        a.segments.sortBy { it.startMs }
        a.markers.sortBy { it.ms }
        return a
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ") + "\""

    private fun unquote(v: String): String {
        val t = v.trim()
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            val inner = t.substring(1, t.length - 1)
            val sb = StringBuilder()
            var i = 0
            while (i < inner.length) {
                val c = inner[i]
                if (c == '\\' && i + 1 < inner.length) {
                    sb.append(inner[i + 1]); i += 2
                } else {
                    sb.append(c); i++
                }
            }
            return sb.toString()
        }
        if (t.length >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return t.substring(1, t.length - 1).replace("''", "'")
        }
        return t
    }
}
