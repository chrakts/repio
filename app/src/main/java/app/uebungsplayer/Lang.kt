package app.uebungsplayer

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Sprache der App (Systemsprache, Deutsch oder Englisch).
 *
 * Gespeichert und angewendet wird sie über AppCompatDelegate.setApplicationLocales: ab Android 13
 * übernimmt das System die Sprache für die ganze App (auch in den Systemeinstellungen sichtbar),
 * auf älteren Geräten speichert AppCompat sie selbst (autoStoreLocales im Manifest) und wendet sie
 * auf alle AppCompat-Activities an.
 */
object Lang {
    /** "" = Systemsprache, sonst ein Sprach-Tag wie "de" oder "en". */
    val SUPPORTED = listOf("", "de", "en")

    fun current(): String {
        val l = AppCompatDelegate.getApplicationLocales()
        return if (l.isEmpty) "" else l[0]?.language ?: ""
    }

    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /**
     * Bis Android 12 gilt die gewählte Sprache nur in AppCompat-Activities. Für Dienst und
     * Anwendungskontext (Benachrichtigung, Meldungen der Engine) liefert diese Funktion einen
     * Kontext in der gewählten Sprache. Ab Android 13 erledigt das System das selbst.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val l = AppCompatDelegate.getApplicationLocales()
        if (l.isEmpty) return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList.forLanguageTags(l.toLanguageTags()))
        return base.createConfigurationContext(cfg)
    }
}
