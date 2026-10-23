# Spezifikation RepIO (Übungsplayer, Android-App)

App-Name: **RepIO**, Paket-ID (`applicationId`): `io.github.chrakts.repio`; der Code-Namespace bleibt `app.uebungsplayer`.

Oct 20, 2026 · @Chris

## Zweck und Überblick

Der Übungsplayer spielt lokal gespeicherte Musikstücke ab und lässt beliebig viele Ausschnitte und Marker festlegen, damit schwierige Stellen gezielt, einmalig oder in Schleife und mit angepasstem Tempo geübt werden können.

Zielgruppe sind Musizierende, die ein Stück einstudieren: Passagen wiederholen, verlangsamen und Einsatzpunkte direkt anspringen.

**Kernfunktionen**

- Ausschnitte: Start und Ende setzen, automatischer Name, nachträglich umbenennen und zeitlich bearbeiten, einmalig oder in Schleife abspielen, eigenes Tempo je Ausschnitt.
- Marker: benannte Zeitpunkte, die sich direkt anspringen lassen.
- Tempo: 50 bis 200 % in 2-%-Schritten, die Tonhöhe bleibt gleich, schnelles Zurücksetzen auf 100 %.
- Begleitdatei: Ausschnitte und Marker liegen als YAML-Datei im selben Ordner wie das Musikstück.
- Historie: die letzten 20 Stücke; das zuletzt gespielte ist beim Start vorgewählt.
- Wiedergabe läuft bei gesperrtem Smartphone weiter.

**Abgrenzung:** Die App hat keine Cloud-Anbindung, keine Konten, keine Werbung und keine Tracker. Sie schneidet oder exportiert keine Audiodaten und streamt nichts aus dem Internet.

## Plattform, Technik und Rahmenbedingungen

Die App läuft ab Android 7.0 (API 24), ist für API 35 gebaut, in Kotlin geschrieben und nutzt nur AndroidX.

| Thema | Festlegung |
| --- | --- |
| Plattform | Android 7.0 (API 24) und neuer; compileSdk und targetSdk 35 |
| Sprache | Kotlin 2.0.21 (Apache 2.0), JVM-Ziel 17 |
| Oberfläche | Klassische Android-Views (XML und eigene Views), kein Jetpack Compose; nur Hochformat |
| Abhängigkeiten | Nur AndroidX: core-ktx 1.15.0 und appcompat 1.7.0; keine Google-Play-Dienste, kein Firebase, keine Werbung, keine Tracker |
| Build | Gradle 8.10.2 mit Android-Gradle-Plugin 8.7.3, Anwendungs-ID app.uebungsplayer |
| Entwicklungsumgebung | VSCodium und Kommandozeile; weder Android Studio noch IntelliJ IDEA, kein Android-Emulator, keine Flatpak- oder Snap-Pakete |
| Test | Auf einem echten Gerät per USB |
| Verteilung | Google Play (App Bundle) und F-Droid (Bau aus dem Quelltext) |
| Sprache der Oberfläche | Englisch (Standard) und Deutsch; wählbar in den Einstellungen (Systemsprache, Deutsch, English) |
| Version | 1.6 (versionCode 7) |

## Architektur

Die Oberfläche steuert nur; Player, Tempo, Ausschnitte und Marker leben in der Engine, die auch bei geschlossener Oberfläche weiterläuft.

&#91;embedded content: Architektur · 9 Bausteine, Pfeile = Aufrufe\]

Die Pfeile zeigen, wer wen aufruft: Die Oberfläche gibt Befehle an die Engine und wird von ihr über Änderungen informiert; Dateibrowser und Einstellungen öffnet sie als eigene Bildschirme. Der Dateibrowser und die Engine greifen beide über `Saf` auf den freigegebenen Ordner zu. Lokale Einstellungen (Akzentfarbe, Schleife, Historie, zuletzt geöffneter Ordner) liegen in den SharedPreferences.

| Datei | Aufgabe |
| --- | --- |
| `Engine.kt` | Player, Tempo, Schleife, Ausschnitte, Marker, Sprünge, Speichern |
| `PlaybackService.kt` | Vordergrunddienst mit Benachrichtigung und Pause-Knopf |
| `MainActivity.kt` | Hauptbildschirm, Liste, Popups, Anzeige-Aktualisierung alle 100 ms |
| `TimelineView.kt` | Zeitleiste mit Touch-Bedienung; kleine Variante in der Liste |
| `FileBrowserActivity.kt` | Dateibrowser im freigegebenen Ordner |
| `Settings.kt` | Akzentfarbe und Einstellungsbildschirm |
| `Lang.kt` | Sprachwahl (AppCompat-Spracheinstellung pro App) |
| `Model.kt` | Segment, Marker, Track, Historie, Zeitformate |
| `Yaml.kt` | Lesen und Schreiben der Begleitdatei |
| `Saf.kt` | Zugriff auf Ordner und Dateien über das Storage Access Framework |

## Datenmodell und Dateiformat

Jedes Musikstück hat höchstens eine Begleitdatei mit seinen Ausschnitten und Markern. Sie liegt im selben Ordner wie das Stück und heißt `<Musikdateiname>.yaml`, zum Beispiel `stueck.flac.yaml`. Der volle Name samt Endung verhindert, dass `stueck.mp3` und `stueck.flac` kollidieren.

| Objekt | Felder | Regeln |
| --- | --- | --- |
| Ausschnitt (Segment) | Name, Anfang (ms), Ende (ms), Tempo (%) | Ende liegt mindestens 100 ms nach dem Anfang; beides innerhalb der Stücklänge; Tempo 50 bis 200, Standard 100 |
| Marker | Name, Zeitpunkt (ms) | Zeitpunkt innerhalb der Stücklänge |
| Musikstück (Track) | Ordnerbaum, Dokument-ID, Ordner-ID des Stücks, Dateiname | Wird in der Historie gespeichert; der Ordner bestimmt, wo die YAML-Datei liegt |
| Historie | Liste von Tracks, neueste zuerst | Höchstens 20 Einträge, ohne Duplikate (gleicher Ordnerbaum und gleiche Dokument-ID) |

Standardnamen lauten „Ausschnitt N“ und „Marker N“, wobei N die kleinste noch freie Zahl ist.

**YAML-Format (Version 2)**

```yaml
version: 2
segments:
  - name: "Ausschnitt 1"
    start_ms: 12000
    end_ms: 24000
    speed_percent: 85
markers:
  - name: "Marker 1"
    ms: 5000
```

- Leere Listen stehen als `segments: []` beziehungsweise `markers: []`.
- Namen stehen in doppelten Anführungszeichen; `"` und `\` werden mit `\` maskiert, Zeilenumbrüche werden zu Leerzeichen.
- Beim Lesen werden Einträge nach Anfangszeit sortiert, Ausschnitte mit Ende vor oder gleich Anfang verworfen, fehlende Namen durch Standardnamen und das Tempo auf 50 bis 200 begrenzt.
- Dateien der Version 1 ohne `speed_percent` werden mit 100 % gelesen.
- Gespeichert wird nach jeder Änderung im Hintergrund; die Datei wird überschrieben. Schlägt das Speichern fehl, erscheint eine Meldung.

**Lokale Einstellungen** (SharedPreferences der App): Akzentfarbe, Schleife an/aus, Historie, zuletzt gewählter Musikordner und der zuletzt geöffnete Unterordner im Dateibrowser.

## Funktionale Anforderungen

Die Anforderungen sind nummeriert, damit sich Tests und Änderungswünsche darauf beziehen lassen.

### Musikstück wählen und Historie

| ID | Anforderung |
| --- | --- |
| F-01 | Der Nutzer wählt ein Musikstück aus dem Dateiverzeichnis des Smartphones (eigener Dateibrowser innerhalb eines einmalig freigegebenen Ordners). |
| F-02 | Der Dateibrowser zeigt nur Ordner und Audiodateien, Ordner zuerst, alphabetisch; er kann eine Ebene hoch und den freigegebenen Ordner wechseln. |
| F-03 | Abspielbar sind die vom Gerät unterstützten Formate, mindestens MP3, FLAC, OGG, Opus, WAV, M4A/AAC. |
| F-04 | Die Historie enthält die letzten 20 geöffneten Stücke, das zuletzt geöffnete steht oben; Auswahl aus der Historie öffnet das Stück und rückt es nach oben. |
| F-05 | Beim Start ist das zuletzt geöffnete Stück vorgewählt, sofern es noch erreichbar ist. |
| F-06 | Alle Stücke liegen ausschließlich lokal; die App benötigt keinen Netzwerkzugriff. |

### Wiedergabe und Navigation

| ID | Anforderung |
| --- | --- |
| F-10 | Eine Taste in der Mitte der Steuerleiste schaltet zwischen Abspielen und Pause um. |
| F-11 | Ein langer Tipp auf diese Taste stoppt, setzt das Stück auf den Anfang und hebt die Ausschnittauswahl auf. |
| F-12 | Zwei Tasten springen 5 Sekunden zurück beziehungsweise vor. |
| F-13 | Zwei Tasten springen zum vorherigen beziehungsweise nächsten Marker oder Ausschnittanfang. „Zurück“ springt zum letzten Ziel, das mehr als 1 s vor der Position liegt, sonst an den Anfang; „vor“ zum ersten Ziel, das mehr als 0,3 s dahinter liegt, sonst erscheint ein Hinweis. |
| F-14 | Die Zeitleiste zeigt Stücklänge, aktuelle Position, Ausschnitte und Marker; ein Touch irgendwo auf Schieberegler oder Markerleiste setzt die Position. |
| F-15 | Die Zeit wird als „aktuell / gesamt“ angezeigt; der Titel stammt aus dem Titel-Tag, sonst aus dem Dateinamen ohne Endung. |

### Ausschnitte

| ID | Anforderung |
| --- | --- |
| F-20 | „Ausschnitt setzen“ legt beim ersten Tipp den Anfang an der aktuellen Position fest. Bis zum Ende kann kein weiterer Ausschnitt begonnen werden. |
| F-21 | Der zweite Tipp setzt das Ende, legt den Ausschnitt an und öffnet sofort das Bearbeiten-Popup mit dem automatischen Namen. |
| F-22 | Liegt das Ende weniger als 200 ms hinter dem Anfang, wird kein Ausschnitt angelegt und ein Hinweis erscheint. |
| F-23 | Ein langer Tipp auf „Ausschnitt setzen“ verwirft einen begonnenen Ausschnitt. |
| F-24 | Solange ein Anfang gesetzt ist, wächst der Ausschnitt in der Zeitleiste mit der Position mit. |
| F-25 | Ein Tipp auf einen Ausschnitt in der Liste wählt ihn aus, springt an seinen Anfang, übernimmt sein Tempo und spielt ihn ab. |
| F-26 | Ein gewählter Ausschnitt endet am Ausschnittende: ohne Schleife stoppt die Wiedergabe und springt an den Anfang des Ausschnitts, mit Schleife beginnt er von vorn. |
| F-27 | Springt der Nutzer aus dem gewählten Ausschnitt heraus (Schieberegler, Zeitleiste, Sprungtasten, Marker), wird die Auswahl aufgehoben. |
| F-28 | Der Schleifenschalter gilt für gewählte Ausschnitte und bleibt über Neustarts erhalten. |

### Marker

| ID | Anforderung |
| --- | --- |
| F-30 | „Marker setzen“ legt jederzeit einen Marker an der aktuellen Position an, auch während der Wiedergabe, und öffnet das Bearbeiten-Popup mit dem automatischen Namen. |
| F-31 | Ein Tipp auf einen Marker in der Liste springt dorthin; die Wiedergabe läuft weiter oder bleibt pausiert, wie sie war. |

### Tempo

| ID | Anforderung |
| --- | --- |
| F-40 | Das Tempo ist von 50 bis 200 % einstellbar, per Regler und per −/+ in 2-%-Schritten; die Tonhöhe bleibt unverändert. |
| F-41 | Ein Tipp auf die Rücksetztaste mit Prozentanzeige setzt das Tempo sofort auf 100 %. |
| F-42 | Jeder Ausschnitt speichert ein eigenes Tempo; ein neuer Ausschnitt übernimmt das aktuell eingestellte Tempo. |
| F-43 | Die Liste zeigt bei jedem Ausschnitt sein Tempo; ein Button in der Ausschnittzeile übernimmt das aktuell eingestellte Tempo in diesen Ausschnitt. |

### Liste und Bearbeiten

| ID | Anforderung |
| --- | --- |
| F-50 | Ausschnitte und Marker stehen in einer gemeinsamen, scrollbaren Liste, sortiert nach Anfangszeit (bei Gleichstand Ausschnitt vor Marker). |
| F-51 | Jeder Eintrag zeigt Symbol (Ausschnitt oder Marker), Name, Zeit(en), bei Ausschnitten das Tempo, und eine kleine Zeitleiste mit der Lage im Stück. |
| F-52 | Das Bearbeiten-Popup ändert Name, Zeitpunkt beziehungsweise Anfang und Ende (Format m:ss.mmm) und bei Ausschnitten das Tempo (50 bis 200). Ungültige Eingaben werden mit Hinweis abgelehnt, das Popup bleibt offen. |
| F-53 | Solange ein Popup offen ist, pausiert die Musik und läuft danach weiter, wenn sie vorher spielte. |
| F-54 | Löschen eines Eintrags verlangt eine Bestätigung. |
| F-55 | Jede Änderung wird sofort in die YAML-Datei geschrieben. |

## Bedienoberfläche

Alle Bedienelemente sind groß und übersichtlich. Die Hauptbedienung erfolgt über Symbolschaltflächen. Texte gibt es nur für die Überschriften der Tempo- und der Setzen-Zeile, für Titel, Zeiten und Listeninhalte sowie auf den Textschaltflächen der Dialoge (OK, Abbrechen, Löschen).

### Hauptbildschirm (von oben nach unten)

1. **Kopfzeile** (72 dp): Stück wählen (Ordner), Historie, Einstellungen; drei gleich breite Schaltflächen.
2. **Titel** des Stücks, eine Zeile, bei Überlänge gekürzt.
3. **Zeitleiste**: Schieberegler mit kräftiger Linie (8 dp) und große Markerleiste (32 dp) darunter; beide stellen per Touch die Position ein. Darunter die Zeit „aktuell / gesamt“.
4. **Steuerleiste** (94 dp): fünf gleich breite Schaltflächen: vorheriges Ziel, 5 s zurück, Abspielen/Pause (genau in der Mitte), 5 s vor, nächstes Ziel.
5. **Tempozeile** (94 dp) mit der Überschrift „Geschwindigkeitsmanipulation“: −, Regler, +, Rücksetzen mit Prozentanzeige.
6. **Setzen-Zeile** (94 dp) mit der Überschrift „Marker und Ausschnitte setzen“: Ausschnitt setzen, Marker setzen, Schleife; drei gleich breite Schaltflächen.
7. **Liste** der Ausschnitte und Marker; sie nimmt den restlichen Platz ein und scrollt.

**Maße und Ausrichtung:** Die drei Bedienzeilen (Steuerleiste, Tempozeile, Setzen-Zeile) sind exakt gleich hoch, 94 dp (72 dp × 1,3). Jede Schaltfläche füllt die volle Zeilenhöhe, sodass alle Elemente einer Zeile vertikal auf einer Linie sitzen. Die Kopfzeile ist 72 dp hoch. Das Maß steht einmal zentral in den Ressourcen.

**Listenzeilen:** mindestens 68 dp hoch (20 % mehr als die ursprünglichen 56 dp). Von links: Symbol (Strich oder Punkt), Name, darunter Zeiten und Tempo, darunter die kleine Zeitleiste (14 dp); rechts die Buttons Tempo übernehmen (nur Ausschnitte), Bearbeiten und Löschen. Der gewählte Ausschnitt ist mit der abgeschwächten Akzentfarbe hinterlegt.

### Weitere Bildschirme und Dialoge

| Element | Aufbau |
| --- | --- |
| Bearbeiten-Popup | Felder Name, Anfang/Zeitpunkt, Ende (nur Ausschnitt), Tempo (nur Ausschnitt); OK prüft die Eingaben, Abbrechen verwirft die Änderung (ein neu angelegter Eintrag bleibt mit seinem Standardnamen bestehen). Die Zeitfelder lassen nur Ziffern, Doppelpunkt, Punkt und Komma zu. |
| Löschbestätigung | Frage mit dem Namen des Eintrags, Löschen oder Abbrechen. |
| Historie | Dialog mit Liste in 22 sp; jeder Eintrag ist ein umrandeter Kasten (Linie 2 dp) mit 6 dp Außenrand, dadurch bleibt der Abstand zwischen den Einträgen konstant und gut sichtbar, auch bei Titeln mit Zeilenumbruch. |
| Dateibrowser | Kopfzeile (72 dp) mit „Eine Ebene hoch“ und „Ordner wechseln“, Pfadanzeige, Liste mit Zeilen von mindestens 60 dp in 18 sp; Ordner und Dateien mit Symbol vor dem Namen; Hinweis bei leerem Ordner. |
| Einstellungen | Zurückpfeil und Titel in einer 72-dp-Zeile; darunter „Akzentfarbe“ mit acht runden Farbfeldern (68 dp), das gewählte Feld trägt einen Ring; darunter „Sprache“ mit Systemsprache, Deutsch, English. Unten „Abbrechen“ und „Bestätigen“ (gleich breit, ohne Hinterlegung, Bestätigen fett in der gewählten Farbe). Änderungen gelten erst nach „Bestätigen“; Abbrechen, Zurückpfeil und Zurück-Geste verwerfen sie. Ein Sprachwechsel baut die Oberfläche neu auf, die Wiedergabe läuft weiter. |

**Systemleisten:** Die Statusleiste bleibt sichtbar. Ihre Farbe folgt dem Hell- oder Dunkelmodus, die Symbolfarbe ist darauf abgestimmt, und der Inhalt wird unter Status- und Navigationsleiste eingerückt.

## Symbole und visuelle Gestaltung

Das Symbol ist die Schaltfläche: keine farbige Hinterlegung, beim Drücken nur ein Ripple. Jede Schaltfläche hat eine Beschreibung für Bedienhilfen.

**Gestaltungsregeln**

- Eigene Vektorsymbole (24 × 24 Einheiten) aus kräftigen Linien und gefüllten Formen; alle Ecken und Linienenden sind leicht abgerundet.
- Die Symbole der Steuerleiste sind außen exakt gleich hoch (20 von 24 Einheiten) und füllen ihre Schaltfläche weitgehend aus.
- Normale Symbole und Texte sind dunkles Anthrazit (#3D444B); im Dunkelmodus gelten die hellen Systemfarben. Deaktivierte Symbole sind abgeschwächt.
- **Akzentfarbe** (Standard Orange #FF9800, einstellbar): Abspielen/Pause immer, Ausschnittbalken, gewählte Listenzeile (abgeschwächt), Schleife wenn an, Ende-Zustand des Ausschnittsymbols, Fortschritt und Daumen der Regler.
- Marker sind immer hellblau (#03A9F4); der Positionsstrich ist anthrazit (im Dunkelmodus weiß).

**Symbolsprache**

| Symbol | Bedeutung |
| --- | --- |
| Ordner | Musikstück wählen |
| Ring mit Zeigern | Historie |
| Zahnrad | Einstellungen |
| Balken mit Dreieck (links / rechts) | Zum vorherigen / nächsten Marker oder Ausschnittanfang |
| Doppeldreieck (links / rechts) | 5 Sekunden zurück / vor |
| Dreieck / zwei Balken | Abspielen / Pause, immer in Akzentfarbe |
| Minus / Plus | Tempo um 2 % verringern / erhöhen |
| Kreispfeil mit Prozentwert | Tempo auf 100 % zurücksetzen |
| Strich mit zwei Begrenzungsstrichen und Plus darüber | Ausschnitt setzen; wartet er auf sein Ende, sind der waagerechte Strich und der rechte Begrenzungsstrich in Akzentfarbe |
| Outline-Kreis mit Plus | Marker setzen |
| Zwei Pfeile im Kreislauf | Schleife; an in Akzentfarbe, aus abgeschwächt |
| Strich mit zwei Begrenzungsstrichen (ohne Plus) | Ausschnitt in der Liste, Akzentfarbe |
| Punkt | Marker in der Liste, blau |
| Tachometer | Aktuelles Tempo in den Ausschnitt übernehmen |
| Bleistift | Eintrag bearbeiten |
| Kreuz | Eintrag löschen |
| Pfeil nach oben / links | Eine Ebene hoch / zurück |
| Ordner mit Plus | Ordner wechseln |

Beim Ausschnittsymbol sind Begrenzungs- und Querstrich 3 Einheiten breit, das Plus hat 2,6 Einheiten Strichbreite bei 10 Einheiten Armlänge.

**Zeitleiste:** Eine Grundlinie (6 dp) zeigt die Stücklänge. Ausschnitte erscheinen als abgerundete Balken in Akzentfarbe, Marker als blaue Striche über die volle Höhe, die Position als Strich. Ein begonnener Ausschnitt wird halbtransparent von seinem Anfang bis zur Position gezeichnet, mit einem Begrenzungsstrich am Anfang, und wächst so beim Abspielen. Dieselbe Darstellung dient in kleiner Form (14 dp) in jeder Listenzeile für genau diesen einen Eintrag.

## Wiedergabeverhalten im Detail

| Ereignis | Verhalten |
| --- | --- |
| Abspielen | Ist ein Ausschnitt gewählt und die Position liegt außerhalb, springt die App an dessen Anfang. Ohne Auswahl springt sie an den Stückanfang, wenn das Stück zu Ende war. Danach startet der Vordergrunddienst. |
| Pause | Die Wiedergabe hält an, der Vordergrunddienst endet. |
| Ende des gewählten Ausschnitts | Ohne Schleife: Pause und Sprung an den Ausschnittanfang. Mit Schleife: sofortiger Sprung an den Anfang und Weiterspielen. |
| Stückende | Mit gewähltem Ausschnitt und Schleife von vorn, sonst endet die Wiedergabe. |
| Bearbeiten-Popup offen | Die Musik pausiert und läuft danach weiter, wenn sie vorher spielte. |
| Bildschirm sperren oder App in den Hintergrund | Die Wiedergabe läuft weiter. Eine Benachrichtigung zeigt den Titel und einen Pause-Knopf; ein Tipp darauf öffnet die App. |
| App aus der Übersicht wegwischen | Die Wiedergabe stoppt. |
| Neues Stück öffnen | Die bisherige Wiedergabe stoppt, Ausschnittauswahl und begonnener Ausschnitt werden verworfen; das eingestellte Tempo und der Schleifenschalter bleiben. |
| Stück nicht lesbar | Ein Hinweis erscheint, es ist kein Stück geladen und alle Bedienelemente sind deaktiviert. |

**Tempo ohne Tonhöhenänderung:** Die App setzt die Wiedergabegeschwindigkeit des Players auf den Prozentwert geteilt durch 100 und hält die Tonhöhe bei 1,0. Das Tempo wird nur bei laufender Wiedergabe übernommen, weil das Setzen im Pausenzustand auf manchen Geräten die Wiedergabe von selbst startet; ein in der Pause geänderter Wert gilt ab dem nächsten Start.

**Positionsgenauigkeit:** Ab Android 8 (API 26) springt der Player auf den nächstliegenden Zeitpunkt, darunter auf einen Stützpunkt im Stück; bei MP3 kann das ungenauer sein. Das Ausschnittende wird alle 30 ms geprüft, nach einem Schleifensprung 250 ms lang nicht erneut. Die Anzeige aktualisiert sich alle 100 ms; unmittelbar nach einem Sprung zeigt sie 150 ms lang das Sprungziel.

**Lebensdauer:** Player, Tempo, Schleife, Ausschnitte und Marker leben im Prozess, nicht in der Oberfläche. Wird die Oberfläche geschlossen und wieder geöffnet, während die Wiedergabe läuft, zeigt sie die laufende Sitzung weiter an. Während der Wiedergabe hält ein CPU-Wake-Lock das Gerät wach; der Bildschirm bleibt nicht an.

## Berechtigungen und Dateizugriff

Die App braucht keine Speicherberechtigung und keinen Netzwerkzugriff. Dateien liest und schreibt sie über das Storage Access Framework, das auch das Anlegen der YAML-Datei neben dem Musikstück erlaubt.

| Berechtigung oder Zugriff | Zweck | Wann |
| --- | --- | --- |
| Ordnerfreigabe (Storage Access Framework) | Musik lesen, Begleitdatei im selben Ordner lesen und schreiben | Beim ersten Öffnen des Dateibrowsers; die Freigabe bleibt dauerhaft bestehen |
| POST\_NOTIFICATIONS (ab Android 13) | Benachrichtigung der laufenden Wiedergabe | Wird beim Start der App erfragt; bei Ablehnung läuft die Wiedergabe weiter, nur ohne sichtbare Benachrichtigung |
| FOREGROUND\_SERVICE und FOREGROUND\_SERVICE\_MEDIA\_PLAYBACK | Vordergrunddienst vom Typ mediaPlayback für Wiedergabe bei gesperrtem Gerät | Im Manifest deklariert, keine Nachfrage |
| WAKE\_LOCK | CPU bleibt während der Wiedergabe wach | Im Manifest deklariert, keine Nachfrage |

**Regeln zum Dateizugriff**

- Der Nutzer gibt einmal einen Ordner frei, zum Beispiel „Musik“. Auf Android 11 und neuer lassen sich die Wurzel des Speichers und Ordner wie „Download“ nicht freigeben.
- Der Dateibrowser merkt sich den freigegebenen Ordner und den zuletzt geöffneten Unterordner. Ist die Freigabe nicht mehr gültig, fragt die App erneut.
- Die Begleitdatei wird im Ordner des Stücks gesucht und beim ersten Speichern angelegt; vorhandene Dateien werden überschrieben.
- Die Historie speichert Ordnerbaum und Dokument-ID. Stücke aus einem nicht mehr freigegebenen Ordner lassen sich nicht öffnen; es erscheint eine Fehlermeldung.
- Es werden keine Daten an Dritte übertragen.

## Nichtfunktionale Anforderungen

| Bereich | Anforderung |
| --- | --- |
| Bedienbarkeit | Große Bedienelemente, Bedienzeilen gleich hoch und bündig, Symbole statt Text; Texteingabe nur beim Umbenennen und Bearbeiten. Hochformat. |
| Barrierefreiheit | Jede Symbolschaltfläche hat eine Inhaltsbeschreibung. Hell- und Dunkelmodus, ausreichender Kontrast durch Anthrazit auf Weiß. |
| Robustheit | Fehler beim Öffnen und Speichern werden gemeldet. Ungültige Einträge in der YAML-Datei werden beim Lesen verworfen, ohne dass die App abstürzt. Unterstützt das Gerät die Tempoänderung nicht, spielt es mit normaler Geschwindigkeit. |
| Reaktionsfähigkeit | Lesen und Schreiben der YAML-Datei und das Auflisten von Ordnern laufen im Hintergrund, die Oberfläche bleibt bedienbar. |
| Datenschutz | Keine Konten, keine Werbung, keine Tracker, keine Google-Play-Dienste, kein Firebase, kein Netzwerkzugriff. Alle Daten bleiben auf dem Gerät. |
| Lizenzen | Kotlin und AndroidX unter Apache 2.0; die Symbole sind eigene Vektorgrafiken, die Formen von Ordner, Stift und Schleife stammen aus den Material Icons (Apache 2.0). F-Droid-tauglich: nur freie Abhängigkeiten, keine Abhängigkeitsinformationen im Paket. |
| Sprache | Oberfläche, Meldungen und Benachrichtigung auf Englisch oder Deutsch (auch auf Android 7–12 einschließlich Dienst). |

## Build, Test und Verteilung

Voraussetzungen: JDK 17, Android SDK (Kommandozeilenwerkzeuge, Plattform 35, Build-Tools) und eine `local.properties` mit `sdk.dir=...`.

```bash
gradle wrapper --gradle-version 8.10.2   # einmalig, erzeugt ./gradlew
./gradlew assembleDebug                   # APK unter app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew bundleRelease                   # App Bundle für Google Play
```

Der Release-Build ist mit Code- und Ressourcenschrumpfung konfiguriert; die Signaturkonfiguration muss der Entwickler ergänzen.

**Prüfliste auf dem Gerät (USB)**

- [ ] Ordner freigeben; MP3, FLAC, OGG und M4A öffnen und abspielen
- [ ] Ausschnitt setzen: Popup erscheint, Musik pausiert, YAML-Datei liegt neben dem Stück
- [ ] Während ein Ausschnitt offen ist: Balken wächst in der Zeitleiste, Ende-Zustand des Symbols in Akzentfarbe
- [ ] Schleife an und aus; Ausschnitt wiederholt sich ohne störende Lücke
- [ ] Tempo 50 %, 100 % und 200 %: Tonhöhe bleibt gleich; Rücksetztaste; Tempo je Ausschnitt übernehmen
- [ ] Bildschirm sperren während der Wiedergabe: Musik läuft weiter, Benachrichtigung mit Pause-Knopf
- [ ] Berührung auf der Markerleiste setzt die Position
- [ ] Bearbeiten-Popup: ungültige Zeiten und Tempo werden abgelehnt
- [ ] Historie mit mehr als 20 Stücken; Neustart öffnet das letzte Stück
- [ ] YAML-Datei von Hand ändern, Stück neu öffnen
- [ ] Dunkelmodus, Statusleiste und Akzentfarben prüfen
- [ ] Benachrichtigungsberechtigung ablehnen: Wiedergabe läuft trotzdem

**Verteilung**

- Google Play: App Bundle (`bundleRelease`), Signierung vor dem Upload einrichten. Die App erhebt keine Daten.
- F-Droid: Bau aus dem Quelltext; dafür sind nur freie Abhängigkeiten im Projekt. Die Metadaten für F-Droid (Beschreibung, Screenshots, Lizenzangabe) sind noch zu erstellen.
- Die Anwendungs-ID `app.uebungsplayer` ist ein Platzhalter und lässt sich vor der Veröffentlichung ändern.

## Einschränkungen, offene Punkte und mögliche Erweiterungen

**Stand der Umsetzung:** Der Quelltext (Version 1.6) ist vollständig, wurde aber noch nicht gebaut und nicht auf einem Gerät getestet. Die Prüfliste im Abschnitt „Build, Test und Verteilung“ ist der nächste Schritt.

**Bekannte Einschränkungen**

- Nur Hochformat.
- Die Benachrichtigung bietet nur Pause; es gibt keine Steuerung über den Sperrbildschirm, keine Medientasten und keine Kopfhörersteuerung (keine MediaSession).
- Wegwischen der App stoppt die Wiedergabe. Aggressive Akkuoptimierung einzelner Hersteller kann die App trotz Vordergrunddienst drosseln; eine Ausnahme von der Akkuoptimierung fordert die App nicht an.
- Die Schleife hat an der Sprungstelle eine kurze Lücke, deren Länge vom Gerät und vom Format abhängt; die Klangqualität der Tempoänderung ebenfalls.
- Pro Stück gibt es genau eine YAML-Datei. Ausschnitte dürfen sich überlappen, Namen müssen nicht eindeutig sein.
- Ordnerfreigabe ist auf Android 11 und neuer auf Unterordner beschränkt (nicht Speicherwurzel, nicht „Download“).
- Nur deutsche Oberfläche.

**Offene Punkte vor der Veröffentlichung**

- Bauen und Testen auf einem Gerät, danach Korrektur der gefundenen Fehler.
- Signaturkonfiguration für den Release-Build.
- F-Droid-Metadaten und Screenshots; endgültige Anwendungs-ID.

**Mögliche Erweiterungen**

- MediaSession mit Steuerung über Sperrbildschirm und Kopfhörertasten.
- Layout für Querformat.
- Weitere Sprachen (neue `values-xx/strings.xml` und Eintrag in `Lang.SUPPORTED` sowie im Einstellungsbildschirm).
