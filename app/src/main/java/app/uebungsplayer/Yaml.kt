package app.uebungsplayer

import java.util.Locale

/**
 * Minimaler YAML-Leser/-Schreiber für genau dieses Format (keine Fremdbibliothek nötig):
 *
 * version: 3
 * segments:
 *   - name: "Ausschnitt 1"
 *     start_ms: 12000
 *     end_ms: 24000
 *     speed_percent: 85
 *     analysis_start_ms: 12000          # ab hier optional: Ergebnis der Analyse
 *     analysis_end_ms: 24000
 *     tuning_hz: 442.80
 *     tuning_tol_hz: 1.35
 *     tempo_bpm: [96.10, 48.04, 191.40]
 *     tempo_confidence: [1.00, 0.51, 0.46]
 *     tempo_choice: 0
 *     key: ["D:maj", "A:maj", "F#:min"]
 *     key_confidence: [1.00, 0.55, 0.53]
 * markers:
 *   - name: "Marker 1"
 *     ms: 5000
 *
 * Dateien ohne speed_percent (Version 1) werden mit 100 % gelesen, Dateien ohne
 * Analysefelder (Version 1 und 2) ohne Analyse. Ältere App-Versionen überlesen die Analysefelder.
 */
object Yaml {

    /** Tonklassen für die Tonart-Codes (sprachneutral, z. B. "F#:min"). */
    private val PC_NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val PC_ALIASES = mapOf("Db" to 1, "D#" to 3, "Gb" to 6, "G#" to 8, "A#" to 10)

    private fun num(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    fun write(a: Annotations): String {
        val sb = StringBuilder("version: 3\n")
        if (a.segments.isEmpty()) {
            sb.append("segments: []\n")
        } else {
            sb.append("segments:\n")
            for (s in a.segments) {
                sb.append("  - name: ").append(quote(s.name)).append('\n')
                sb.append("    start_ms: ").append(s.startMs).append('\n')
                sb.append("    end_ms: ").append(s.endMs).append('\n')
                sb.append("    speed_percent: ").append(s.speed).append('\n')
                s.validAnalysis()?.takeUnless { it.isEmpty }?.let { writeAnalysis(sb, it) }
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

    private fun writeAnalysis(sb: StringBuilder, an: SegmentAnalysis) {
        sb.append("    analysis_start_ms: ").append(an.startMs).append('\n')
        sb.append("    analysis_end_ms: ").append(an.endMs).append('\n')
        if (an.tuningHz != null) {
            sb.append("    tuning_hz: ").append(num(an.tuningHz)).append('\n')
            if (an.tuningTolHz != null) sb.append("    tuning_tol_hz: ").append(num(an.tuningTolHz)).append('\n')
        }
        if (an.tempos.isNotEmpty()) {
            sb.append("    tempo_bpm: ").append(an.tempos.joinToString(", ", "[", "]") { num(it.bpm) }).append('\n')
            sb.append("    tempo_confidence: ")
                .append(an.tempos.joinToString(", ", "[", "]") { num(it.confidence) }).append('\n')
            sb.append("    tempo_choice: ").append(an.tempoChoice).append('\n')
        }
        if (an.keys.isNotEmpty()) {
            sb.append("    key: ").append(an.keys.joinToString(", ", "[", "]") {
                quote(PC_NAMES[it.tonic] + if (it.minor) ":min" else ":maj")
            }).append('\n')
            sb.append("    key_confidence: ")
                .append(an.keys.joinToString(", ", "[", "]") { num(it.confidence) }).append('\n')
        }
    }

    /** "[1.0, 2.5]" -> Liste der Einträge (Anführungszeichen entfernt). */
    private fun list(v: String): List<String> {
        val t = v.trim().removePrefix("[").removeSuffix("]").trim()
        if (t.isEmpty()) return emptyList()
        return t.split(',').map { unquote(it.trim()) }
    }

    private fun keyCode(code: String): Pair<Int, Boolean>? {
        val note = code.substringBefore(':').trim()
        val mode = code.substringAfter(':', "").trim().lowercase()
        val pc = PC_NAMES.indexOf(note).takeIf { it >= 0 } ?: PC_ALIASES[note] ?: return null
        return when (mode) {
            "maj" -> pc to false
            "min" -> pc to true
            else -> null
        }
    }

    /** Baut die Analyse aus den gelesenen Feldern; null, wenn nichts Brauchbares da ist. */
    private fun buildAnalysis(f: Map<String, String>): SegmentAnalysis? {
        val st = f["analysis_start_ms"]?.toIntOrNull() ?: return null
        val en = f["analysis_end_ms"]?.toIntOrNull() ?: return null
        val tuning = f["tuning_hz"]?.toDoubleOrNull()?.takeIf { it in 300.0..600.0 }
        val tol = f["tuning_tol_hz"]?.toDoubleOrNull()?.takeIf { tuning != null && it >= 0 }
        val bpms = list(f["tempo_bpm"] ?: "").mapNotNull { it.toDoubleOrNull()?.takeIf { b -> b in 10.0..400.0 } }
        val bpmConf = list(f["tempo_confidence"] ?: "").map { it.toDoubleOrNull() }
        val tempos = bpms.mapIndexed { i, b -> TempoEstimate(b, (bpmConf.getOrNull(i) ?: 0.5).coerceIn(0.0, 1.0)) }
        val choice = (f["tempo_choice"]?.toIntOrNull() ?: 0).takeIf { it in tempos.indices } ?: 0
        val keyConf = list(f["key_confidence"] ?: "").map { it.toDoubleOrNull() }
        val keys = list(f["key"] ?: "").mapIndexedNotNull { i, c ->
            keyCode(c)?.let { (pc, minor) -> KeyEstimate(pc, minor, (keyConf.getOrNull(i) ?: 0.5).coerceIn(0.0, 1.0)) }
        }
        val an = SegmentAnalysis(st, en, tuning, tol, tempos, choice, keys)
        return if (an.isEmpty) null else an
    }

    fun parse(text: String): Annotations {
        val a = Annotations()
        var section = ""
        var name: String? = null
        var start: Int? = null
        var end: Int? = null
        var ms: Int? = null
        var speed: Int? = null
        val extra = HashMap<String, String>() // Analysefelder des aktuellen Eintrags

        fun flush() {
            val n = name
            val st = start
            val en = end
            val m = ms
            val sp = (speed ?: 100).coerceIn(50, 200)
            if (section == "segments" && st != null && en != null && en > st) {
                a.segments.add(Segment(n ?: "Ausschnitt ${a.segments.size + 1}", st, en, sp, buildAnalysis(extra)))
            } else if (section == "markers" && m != null) {
                a.markers.add(Marker(n ?: "Marker ${a.markers.size + 1}", m))
            }
            name = null; start = null; end = null; ms = null; speed = null
            extra.clear()
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
                "analysis_start_ms", "analysis_end_ms", "tuning_hz", "tuning_tol_hz", "tempo_bpm",
                "tempo_confidence", "tempo_choice", "key", "key_confidence" -> extra[key] = value
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
