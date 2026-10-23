# RepIO (Übungsplayer)

Paket-ID: `io.github.chrakts.repio` · Sprachen: Englisch, Deutsch (wählbar in den Einstellungen)

Android-App zum Einstudieren von Musikstücken: Ausschnitte (einmalig oder in Schleife), Marker und
Abspielgeschwindigkeit (50–200 %, Tonhöhe bleibt gleich), gespeichert als YAML neben der Musikdatei
(`<Dateiname>.yaml`, z. B. `stueck.flac.yaml`).

## Bauen (Linux, nur Kommandozeile)

Voraussetzungen: JDK 17, Android SDK (cmdline-tools, platform 35, build-tools), `local.properties` mit `sdk.dir=...`.

    gradle wrapper --gradle-version 8.10.2     # einmalig, erzeugt ./gradlew
    ./gradlew assembleDebug                     # APK: app/build/outputs/apk/debug/
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    ./gradlew bundleRelease                     # App Bundle für Google Play (Signatur-Konfiguration nötig)

## Aufbau

- `Engine.kt` – Wiedergabe, Tempo, Ausschnitte/Marker (prozessweit, überlebt die Activity)
- `PlaybackService.kt` – Vordergrunddienst (mediaPlayback) für Wiedergabe bei gesperrtem Gerät
- `MainActivity.kt` – Oberfläche, `TimelineView.kt` – Zeitleiste (mit Touch-Bedienung)
- `Settings.kt` – Einstellungen (Akzentfarbe, Sprache, Bestätigen/Abbrechen) und `SettingsActivity`
- `Lang.kt` – Sprachwahl (Systemsprache, Deutsch, Englisch)
- `FileBrowserActivity.kt` – Dateiwahl (Storage Access Framework)
- `Yaml.kt` – Format der Begleitdatei (Version 2 mit `speed_percent`; Version 1 wird weiter gelesen)
- Symbole: eigene Vektorgrafiken in `res/drawable/` (Material-Icons-Pfade für Ordner, Stift und Schleife: Apache 2.0)

## Store-Texte und Bilder

`fastlane/metadata/android/<de-DE|en-US>/`: Titel, Kurz- und Langbeschreibung, Changelog je `versionCode`,
Icon (512×512) und Screenshots unter `images/phoneScreenshots/1.png, 2.png, …` (F-Droid liest diese Ordner;
für Google Play dieselben Texte und Bilder in der Play Console hochladen).
