Du arbeitest zusätzlich zum bereits laufenden Widget-Auftrag an Mitfahrt, einer nativen Android- und Wear-OS-App für NEW-/VRR-Busfahrten in Mönchengladbach.

Repository: https://github.com/Eibelucas/VRR-status

Der Widget-Auftrag bleibt bestehen. Diese Aufgaben ergänzen ihn. Arbeite die Pakete einzeln ab und liefere getrennte Commits, sodass wir sie unabhängig übernehmen können. Nutze für die Zusatzpakete einen eigenen Branch `claude/mitfahrt-extras` und bei paralleler Arbeit getrennte Worktrees. Verändere keine ungesicherten Arbeiten eines anderen Prozesses.

KONTEXT UND ZIEL

Die App muss von Mitschülern ohne technisches Wissen bedienbar sein. Nutzer: Galaxy A56, Galaxy Watch5 Pro; andere Android-/Wear-OS-Geräte sollen ebenfalls funktionieren. Deutsch, modern, einfach, an NEW-Farben angelehnt. Keine langen Erklärtexte in der Oberfläche, keine erfundenen Live-Angaben, keine Platzhalterfunktionen.

Parallel werden zentrale Einstellungen und Profile, Routeneditor, Live-Karte, Benachrichtigungen, Watch, Belege und Datenschutz integriert. Bearbeite diese bestehenden Bereiche nicht direkt. Auf GitHub kann der große Umbau noch fehlen. Lies den tatsächlich verfügbaren Quellstand und dokumentiere Abweichungen. Rate keine APIs zusammen.

DEIN ZUSÄTZLICHER AUFTRAG

Paket A: Ausnahmen für einzelne Routentage

Baue ein separates Modul für zeitlich begrenzte Ausnahmen gespeicherter Routen:
- Eine Route nur heute ausblenden.
- Eine Route an einem bestimmten Datum ausblenden.
- An einem bestimmten Datum andere Uhrzeiten verwenden, etwa Schule erst ab 9 Uhr.
- Mehrere Zeitfenster an einem Ausnahmetag.
- Ausnahme bearbeiten, entfernen und wieder normale Wochenregeln verwenden.
- Übersicht kommender Ausnahmen mit verständlichen Datumsangaben.
- Bereits vergangene Ausnahmen müssen keine späteren Tage beeinflussen.
- Hin- und Rückroute werden über ihre eigenen Routen-IDs getrennt behandelt.

Eigene Dateien:
- data/RouteDayOverrides.kt
- ui/RouteDayOverridesEditor.kt
- dazugehörige neue Tests

Nutze vorhandene RoutePlan-/RouteWindow-Typen, soweit der gelesene Stand sie bereitstellt. Ergänze keine Felder in bestehenden Modelldateien. Verwende einen eigenen validierten Datenspeicher mit StateFlow und stabilen IDs.

Gewünschte Integrationsschnittstelle:
- RouteDayOverridesStore(context).state
- Speichern und Entfernen einer Ausnahme
- Eine reine Policy-Funktion, die für Routen-ID und Instant einen optionalen Zeitregel-Override liefert: null bedeutet normale Regel, false bedeutet ausgeblendet, true bedeutet in einem gültigen Ausnahmezeitfenster.
- Die übrigen Regeln wie deaktivierte Route, Nähe zur Haltestelle und manuelle Anzeige bleiben beim aufrufenden Code.

Definiere die Reihenfolge der Regeln eindeutig. Rechne mit Europe/Berlin. Berücksichtige Nachtintervalle, Monats-/Jahreswechsel und Sommer-/Winterzeit. Bei über Mitternacht reichenden Intervallen muss klar sein, zu welchem Startdatum sie gehören. Ein ausdrücklich ausgeblendeter aktueller Tag darf nicht versehentlich durch einen Übertrag der vergangenen Nacht wieder sichtbar werden.

Die Oberfläche soll Datumsauswahl und Zeitpicker verwenden. Keine ISO-Datumseingabe oder internen IDs für normale Nutzer.

Paket B: Schulkalender für Nordrhein-Westfalen

Baue ein unabhängiges Kalendermodul:
- Gesetzliche NRW-Feiertage.
- Schulferien mit belegbarer Datenquelle und Datenstand.
- Eigene freie Tage hinzufügen und entfernen.
- Einzelne Tage ausdrücklich als Schultag markieren, etwa trotz Ferien.
- Kalenderfunktion komplett abschalten.
- Anzeige, weshalb ein Datum als frei gilt: Feiertag, Ferien oder eigene Ausnahme.
- Nutzbarkeit ohne Internet mit vorhandenem Datenstand.
- Fehlende Ferientermine ehrlich anzeigen; keine geratenen Ferienzeiträume.

Eigene Dateien:
- data/SchoolCalendar.kt
- ui/SchoolCalendarScreen.kt
- neue Tests

Feste und bewegliche NRW-Feiertage dürfen mit einer getesteten Kalenderberechnung ermittelt werden. Ferientermine benötigen eine verlässliche veröffentlichte Quelle; dokumentiere Link, Geltungsbereich und Abrufdatum. Liefert eine Quelle keine nutzbaren Daten, behalte manuelle freie Tage und kennzeichne die Lücke. Kein eigener Server, kein Konto und keine Standortübermittlung.

Biete eine reine Abfrage für LocalDate an, die Status und Begründung zurückgibt. Die Verbindung zu globalen Profilen und Schulrouten erfolgt später durch den Hauptentwickler. Bestehende AppSettings-, RoutePlans- und Root-UI-Dateien bleiben unangetastet.

Paket C: Archiv tatsächlich verfolgter Fahrten

Baue ein lokales Fahrtenarchiv mit übersichtlicher Anzeige:
- Liste nach Tagen, mit Linie, Start, Ziel und Zeit.
- Einzelne Fahrt löschen, mit Rückgängig-Aktion.
- Mehrere Fahrten auswählen und löschen.
- Fahrtenarchiv komplett leeren, nach ausdrücklicher Bestätigung.
- Speicherung abschalten.
- Einstellbare Löschfrist; ohne gewählte Frist keine automatische Löschung.
- Suche und einfache Filter nach Datum und Linie.
- Historische Fahrt als Vorschau ansehen, ohne sie erneut zu verfolgen.
- Kein historischer Countdown, der wie eine aktuelle Prognose aussieht.

Eigene Dateien:
- data/JourneyArchive.kt
- ui/JourneyArchiveScreen.kt
- neue Tests

WICHTIG: Ein bloßer Abfahrtsaufruf, eine Routenvorschau oder eine Hintergrundabfrage darf niemals einen Archiveintrag erzeugen. Erfasst wird nur eine ausdrücklich gestartete Fahrt. Wiederholte Aktualisierungen derselben Fahrt ersetzen deren Eintrag und erzeugen keine Duplikate.

Nutze vorhandene Ride-/RideCodec-Typen. Biete explizite Methoden zum Erfassen bzw. Aktualisieren, Entfernen, Wiederherstellen und Bereinigen an. Verwende eine begrenzte Zahl von Einträgen und begrenzte Import-/Dateigrößen. Speicherung ausschließlich privat auf dem Gerät. Keine Aufnahme in Einstellungen-/Routenvorlagen und kein automatisches Teilen.

Die UI bekommt Daten und Callbacks von außen. Ändere weder RideStore, RideService, RideViewModel noch die zentrale Navigation. Schreibe genau auf, an welchen Stellen der Hauptentwickler später die Aufnahme tatsächlich gestarteter Fahrten anschließt.

Eine alte Rückgängig-Aktion darf keinen inzwischen aktualisierten Eintrag oder eine spätere Löschung überschreiben. Teste dieses Verhalten.

Paket D: Übersicht der selbst festgehaltenen Ankünfte

Baue eine eigenständige Ansicht für vorhandene persönliche Ankunftsmarkierungen:
- Nach Datum gruppieren.
- Haltestelle und erfasste Uhrzeit anzeigen.
- Herkunft anzeigen, wenn verfügbar: manuell oder über Standort erkannt.
- Nach Haltestelle filtern.
- Einzelne Einträge löschen.
- Verständlicher leerer Zustand ohne Werbe- oder Erklärungstexte.

Eigene Datei:
- ui/ArrivalHistoryScreen.kt

Verwende vorhandene ArrivalMark-Typen. Falls dein Quellstand das Herkunftsfeld noch nicht hat, akzeptiere die Herkunft als vom aufrufenden Code bereitgestelltes Label. Erfinde keine Erkennung und ändere ArrivalStore/ArrivalMark nicht.

Eigene Ankunft ist eine persönliche Aufzeichnung. Sie darf niemals als offiziell bestätigte Busankunft dargestellt werden. Erfasste Zeiten werden in dieser Übersicht nicht nachträglich bearbeitet.

GESTALTUNG UND BEDIENUNG

- Bestehende Compose-/Material-Komponenten verwenden.
- Vorhandenes MitfahrtTheme berücksichtigen; keine zweite vollständige Designsprache.
- Deutsch, kurze Beschriftungen, sinnvolle Standardwerte.
- Mindestens 48 dp große wichtige Bedienziele.
- Große Systemschrift und TalkBack berücksichtigen.
- Hell und dunkel unterstützen.
- Lange Haltestellen- und Routennamen dürfen die Bedienung nicht beschädigen.
- Tastatur darf keine Speicher- oder Bestätigungsschaltflächen verdecken.
- Löschaktionen klar benennen; Undo nur bei sauberer Wiederherstellung anbieten.
- Seltene Optionen unter Erweitert; Hauptaufgabe sofort erkennbar.
- UI-Komponenten über Daten und Callbacks integrierbar halten.

ZUSAMMENARBEIT UND DATEIGRENZEN

Du darfst zusätzlich zu deinem bestehenden Widget-Bereich nur die oben neu genannten Dateien, direkt dafür erforderliche neue Hilfsdateien und deren Tests anlegen. Teile eine notwendige Änderung an einer bestehenden Datei als separaten Integrationsvorschlag mit.

Nicht direkt bearbeiten:
- MainActivity, MitfahrtApp, RideViewModel, MitfahrtApplication
- AppSettings und CustomizationScreen
- RoutePlans und RoutePlansScreen
- RideStore, RideService, Benachrichtigungs- und Alarmcode
- Live-Karte und NEW-Fahrzeugzuordnung
- bestehende Beleg-/Ankunftsspeicher und Update-Code
- Wear-/Watch-Protokolle
- Manifest-, Gradle-, Versions- und Release-Dateien

Nutze keine zusätzliche Bibliothek, wenn Standard-Android, Kotlin, java.time, vorhandene Coroutines und Compose reichen. Begründe zwingend nötige zusätzliche Abhängigkeiten als Integrationshinweis. Keine neuen Server oder Konten.

Keine Versionsnummer erhöhen, keine APK veröffentlichen, keinen Release erstellen und nicht nach main mergen. Nimm keine Signierschlüssel, Zugangsdaten oder privaten Beispieldaten in Commits auf.

VALIDIERUNG

Lies vorhandene Projektanweisungen und Tests. Prüfe die neuen Module mit passenden Tests:
- Tagesausnahmen: Vorrang, Löschen, normale Regeln nach Ablauf, mehrere Fenster, Nachtintervalle und Zeitumstellung.
- Kalender: bekannte NRW-Feiertage, Jahreswechsel, eigene Überschreibung und fehlende Ferientermine.
- Archiv: Wiederholungsaktualisierung ohne Duplikat, Vorschau erzeugt keine Aufnahme, Löschfrist, beschädigte Speicherung und sichere Wiederherstellung.
- Daten: ungültige IDs, leere oder überlange Namen, ungültige Datums-/Zeitwerte und Größenbegrenzungen.
- Oberfläche: kompakte Breite, große Schrift, leere Listen und lange Namen, soweit tatsächlich testbar.

Führe passende Gradle-Prüfungen durch. Wenn die Umgebung etwas verhindert, benenne die genaue Ursache und behaupte keine Geräteprüfung. Behebe echte Fehler und dokumentiere Einschränkungen offen.

ABGABE

Für jedes Paket separat liefern:
1. Branch und Commit oder anwendbaren Patch.
2. Kurze Erklärung des fertigen Verhaltens.
3. Öffentliche Schnittstellen der neuen Module.
4. Genaue, kleine Integrationshinweise mit betreffenden Stellen.
5. Tatsächlich ausgeführte Prüfungen und deren Ergebnisse.
6. Konkrete Restpunkte.

Beginne mit Paket A. Beende jedes Paket mit einem überprüfbaren Ergebnis und gehe danach zu B, C und D über. Fasse keine bereits parallel bearbeiteten Bereiche an. Liefere funktionierenden Code statt bloßer Beschreibungen oder Platzhalter.
