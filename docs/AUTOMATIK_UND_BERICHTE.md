# Busautomatik, Einstiegserkennung und Fahrtberichte

Stand von Claudes Erstimplementierung: 13.11.2026. Integrationsänderungen für 0.19 stehen unten. Alle Szenarien in den Tests sind synthetisch. Eine Erkennungsquote wurde nicht gemessen.

## 1. Neue Bedienung in Kürze
- **Begleiten** wie bisher starten (Route, Verbindung oder Abfahrt). Suche und Vorschau starten weiterhin nichts.
- **Bus**: Mitfahrt ordnet den Bus selbst zu und hält die Position aktuell. Passen mehrere, erscheint „Welcher Bus ist deiner?“. Eine eigene Wahl bleibt, bis du „Wieder automatisch zuordnen“ tippst.
- **Einstieg**: Mitfahrt wechselt selbst von Warten zu „Im Bus“. „Bin im Bus“ bleibt als Schaltfläche, „Doch nicht im Bus“ nimmt es zurück. Nach einer Korrektur pausiert die Automatik fünf Minuten.
- **Ausstieg**: Am gewählten Ausstieg endet die Begleitung selbst, wenn du erkennbar zu Fuß weitergehst. Anderswo fragt die App „Ausgestiegen?“ mit „Noch im Bus“ und „Fahrt beenden“.
- **Bericht**: „Bericht“ öffnet sofort den Bericht der ganzen Verbindung, ohne Haltestellenauswahl. Er entsteht bei jeder Begleitung von selbst (abschaltbar) und liegt unter „Berichte“. „Als PDF teilen“ oder „Speichern“ erzeugt erst auf Tippen eine PDF.
- **Einstellungen**: oben die Schalter „Einstieg und Ausstieg erkennen“, „Bus automatisch zuordnen“, „Fahrtbericht vorbereiten“; Seltenes unter „Mehr“.

## 2. NEW-Livedaten (geprüft am 13.11.2026, 01:07 Uhr)
Abruf von `livegeomatching/busses.json` (dieselbe Quelle wie livebus.new.de): 230 Einträge, 228 geortet, 35 online,
30 mit einer Position jünger als 90 s, 6 mit echter Liniennummer, 1 mit `FAHRTWEG`. Nachts fährt fast nichts; die Zahlen
zeigen aber, dass ein frischer Fahrplanweg selten vorliegt. Die sichere Zuordnung über `NewVehicleMatcher.confirmed`
ist deshalb die Ausnahme, die Bewegungsauswertung der Normalfall. Felder wie `raw.door` und `geomatching.LI_RI_NR`
sind vorhanden, werden aber nicht genutzt, weil ihre Bedeutung nicht belegt ist. Tagsüber wurde nicht gemessen.

## 3. Buszuordnung
Die vorhandene Bewertung (`VehicleMatching.kt`, siehe `GPS_MATCHING.md`) bleibt die einzige Implementierung.
Geändert: Texte der Buskarte, „Welcher Bus ist deiner?“ bei mehreren Treffern, manuelle Wahl wird klar als solche
gezeigt und nie überschrieben (bestehendes Verhalten, bestehende Tests). Die Einstiegserkennung nutzt nur das
Fahrzeug, das dieser Fahrt zugeordnet ist.

## 4. Einstiegs- und Ausstiegserkennung (`BoardingDetector.kt`)
Zustände: Warten → möglicher Einstieg → im Bus → möglicher Ausstieg → beendet. In der Oberfläche erscheint davon
nur „Einstieg wird geprüft.“, „Im Bus · seit … · erkannt“ und „Ausgestiegen?“.

Einstieg braucht zwei Auswertungen mit neuen Messungen im Abstand von mindestens 10 s:
- mit Busposition: Handy und zugeordneter Bus liegen bei mindestens zwei NEW-Messungen beieinander, beide legen
  eine ähnliche Strecke (≥ 80 m) vorwärts entlang der Linie zurück, schneller als zu Fuß (Spitze ≥ 4,5 m/s);
- ohne frische Busposition: mindestens 250 m vorwärts entlang der Linie, mindestens 200 m hinter dem Einstieg,
  zeitlich um die erwartete Abfahrt (−2 bis +45 Min).
Nähe, Geschwindigkeit, ein einzelner Punkt, die Abfahrtszeit oder das Bus-WLAN allein ändern nichts.

Ausstieg braucht drei Auswertungen über mindestens 30 s: Der Bus fährt ohne das Handy weiter, oder das Handy
entfernt sich zu Fuß von der Linie, oder es geht am Ausstieg zu Fuß weiter. Ein stehender Bus und ein
Standortausfall liefern keinen Hinweis.

Standortmessung je Lage (`LocationProfile`): weit vor der Abfahrt alle 20 s / 30 m, kurz davor und bei möglichem
Ein- oder Ausstieg alle 3 s, im Bus alle 5 s / 10 m. Positionen bleiben im Arbeitsspeicher (höchstens 5 Minuten).

Schwellen stammen aus typischen Werten (Haltabstand, Gehtempo) und sind nicht an echten Fahrten kalibriert.

## 5. Fahrtbericht
- `JourneyReport` (Version 2): ein Bericht je Begleitung mit allen Fahrzeugen, Fußwegen und Umstiegen, den Halten
  meines Abschnitts mit Fahrplan- und Prognosezeit, Ereignissen mit Zeit, Quelle und Erfassungszeit.
  Version 1 (`DelayEvidence`, ein Halt) bleibt lesbar und steht unter „Ältere Einzelbelege“.
- Quellen sind getrennt: „VRR-Angabe“, „GPS-Erkennung der App“, „Eigene Angabe“, „In der App erfasst“.
  Eine Prognose ist nie eine tatsächliche Ankunft. Fehlende Zeiten stehen als „–“ bzw. „nicht erfasst“.
- Ohne Prognose, ohne Störung und bei ausgefallenen Live-Daten entsteht derselbe Bericht mit dem Hinweis
  „Eine VRR-Prognose lag nicht vor“. Warten ohne Einstieg wird mit Zeitraum dokumentiert; „kein Fahrzeug
  erkannt“ ist ausdrücklich keine Ausfallmeldung. „Bus ist nicht gekommen“ erscheint als eigene Angabe.
- Keine Duplikate: Der Bericht hängt am Schlüssel der Begleitung; Aktualisierung, Neustart und Abschnittswechsel
  finden denselben Bericht. Ein beendeter Bericht wird nicht wieder geöffnet.
- Export: Jede PDF ist eine eigene Datei mit dem Datenstand, aus dem sie entstand (`export-N.json`), und ändert
  sich nicht mehr. Ein Export während der Fahrt trägt „Vorläufiger Stand“. Geteilt wird nur auf Tippen.
- Speicherung: `files/reports/journeys/`, gelöscht über „Berichte löschen“ und die Frist „Belege nach Tagen löschen“.
  Es werden Ereignisse gespeichert, keine GPS-Spur.

## 6. Durchgeführte Prüfungen (lokal, 13.11.2026)
- `:shared:test :app:testDebugUnitTest :wear:testDebugUnitTest`: 672 Tests, 0 Fehler.
- `:app:lintDebug :wear:lintDebug`: 0 Fehler (84 bzw. 14 Warnungen, überwiegend Versionshinweise, keine aus neuen Dateien geprüft auffällig).
- `:app:assembleDebug :wear:assembleDebug`: erfolgreich.
- `BoardingDetectorTest` (synthetisch): richtiger Einstieg, stehen bleiben, Vorbeifahrt, Gegenrichtung, falscher Bus,
  Fußweg neben langsamem Bus, Stau, GPS-Sprung, Standortausfall, alte Liveposition, schlechte Genauigkeit,
  manuelle Korrektur in beide Richtungen, WLAN allein, Halt an der Ampel, Ausstieg am Ziel und davor.
- `JourneyReportTest` (synthetisch): Bericht ohne Prognose, keine Duplikate bei Aktualisierung und Neustart,
  erhaltene frühere Prognose, Warten ohne Bus, gemeldeter Ausfall, Umstieg in einem Bericht, Automatik aus,
  Demofahrt, eingefrorener Export, Seitenumbruch, altes Format, Archivfahrt, Aufbewahrung, Ride-Codec alt/neu.
- Bilder: `PlannerScreenshotTest` rendert hell, dunkel, 320 dp Breite mit 150 % Schrift; die Bilder wurden angesehen
  und danach korrigiert (abgeschnittene Filterzeile, abgeschnittene Schaltflächen bei großer Schrift, falsche Quelle
  bei „Warten beendet“). Übersichten in `docs/bilder/`.
- PDF-Layout: derselbe Zeichencode wird im Test auf Bitmaps gezeichnet (Robolectric kann keine PDF schreiben).

## 7. Offen, nicht geprüft
- Keine Bedienung auf Emulator, Galaxy oder Watch. TalkBack wurde nicht gehört; geprüft sind nur Beschriftungen,
  Überschriften-Semantik und zusammengefasste Zeilen im Code.
- Keine echte Mitfahrt: Einstieg, Ausstieg und Buszuordnung sind nur synthetisch getestet.
- Das Schreiben der echten PDF-Datei (`PdfDocument`) lief nicht; geprüft ist das Layout als Bild.
- Anschlusswarnungen aus der Begleitung werden noch nicht in den Bericht übernommen (das Feld ist vorbereitet).
- Im Fahrtenarchiv gibt es noch keinen eigenen „Bericht“-Knopf; die letzte Fahrt erreicht man unter „Berichte“.
- Ersteinrichtung, Dialoge, Routen-Editor und Widgets haben nur die gemeinsamen Änderungen bekommen
  (Schaltflächenform, Farben, Kopfzeile), keine eigene Überarbeitung.
- NEW-Daten wurden nur nachts abgerufen.

## 8. Anti-Slop Delivery Gate (Modus „during“)
Design Read: Nahverkehrs-App für Schüler und Pendler in Mönchengladbach, ruhig und beschildert. ENERGY 1 / RHYTHM 2 / MOTION 1.

**Block 1, Hard Gate**
- R-02 PASS: Suche nach „—“ in `app/src/main` und `wear/src/main`: zwei Treffer in der Watch-Komplikation ersetzt, sonst keine.
- R-03 PASS: 393 dp und 320 dp gerendert; Filterzeile bricht um, Schaltflächen stapeln bei großer Schrift; Tippflächen ≥ 48 dp.
- R-17, R-18, R-36, R-38 PASS: keine Kennzahlen, Stimmen oder Behauptungen; Testdaten sind als synthetisch benannt.
- R-23 PASS: keine neuen Logos, Bilder oder Avatare; Navigation unverändert.
- R-24, R-26 PASS: alle neuen Bedienelemente haben eine Funktion (Bericht öffnen/teilen/speichern/löschen, Beobachtung, Korrekturen, „Bus folgen“).
- R-25 PASS: 18 Text-Farbpaare mit `contrast-check.py` gerechnet, niedrigster Wert 5,33:1 (Nebentext auf hellem Grund). Rahmenfarben 3,21:1 und 4,37:1 werden nur für Linien genutzt.
- R-27 PASS: Berichte leer („Noch keine Berichte“), PDF-Erstellung mit Zustand und Fehlermeldung, Buskarte mit Lade-, Leer- und Fehlerzustand.
- R-28 PASS: keine FAQ.
- R-32, R-39 TEILWEISE GEPRÜFT: Compose-Standardelemente mit Rollen und Beschriftungen, Überschriften als `heading`; Tastatur und TalkBack wurden nicht am Gerät bedient (siehe 7).
- R-33 PASS: keine Patch-Skripte im Repository; Änderungen stehen im Quelltext.
- R-34 PASS: hell und dunkel gerendert und angesehen; der im Dunkeln unlesbare Seitentitel ist behoben.
- R-35 EINGESCHRÄNKT: gebaut, getestet und gerendert, aber nicht am Gerät durchgeklickt (siehe 7). Kein vollständiges PASS.
- R-37 PASS: Richtung aus dem Auftrag, festgehalten in `DESIGN.md`.
- R-40 nicht anwendbar (keine Webseite); keine Leistungszahlen behauptet.

**Block 2, Purpose-Gate**
- R-01 PASS: kein Verlauf, kein Glow. R-04 PASS: Material-Symbole nur mit Bezug (Bus, Bahn, Teilen, Speichern); Zier-Symbole der Einstellungskarten entfernt.
- R-06 PASS: Systemschrift, Grund in `DESIGN.md`; gesperrte Großbuchstaben entfernt. R-07, R-08, R-10, R-13, R-22 PASS: nicht verwendet.
- R-09 PASS: einziges Schild ist die Liniennummer (echte Angabe). R-12 PASS: keine Schatten.
- R-14 PASS: Karten nur für antippbare Einheiten; Einstellungen, Berichte und Bericht ohne Karten. R-19 PASS: nur Ladeanzeige.

**Block 3, Lebendigkeit**
- Dials gesetzt und eingehalten; Fokus je Bildschirm (Wohin?, Fahrtkarte, Zusammenfassung); Abstand gliedert die Listen;
  ein Akzent (Beere); Motiv Linienschild; Design Read steht oben.

**Block 4, Handwerk**
- C-1 bis C-5 PASS mit der Einschränkung aus R-35. R-11 PASS: drei Radien statt durchgehend runder Formen.
- R-15, R-16 PASS: Beschriftungen nennen die Handlung („Begleiten“, „Als PDF teilen“, „Bus folgen“).
- R-20, R-30 PASS: Linienschild und Beere sind NEW-spezifisch. R-21 PASS: Umschalter vorhanden. R-29 PASS: Beere, Bernstein, Neutral. R-31 PASS: Gründe in `DESIGN.md`.

Ergebnis: kein FAIL. R-32/R-39 und R-35 sind nur so weit bestätigt, wie es ohne Gerät möglich war.

## Integration 0.19.0

- Langsames Fahren am Ziel ohne Fahrzeugposition ist kein Ausstiegsnachweis. Erneutes Auswerten derselben Handyposition zählt nicht als neue Bestätigung.
- „Doch nicht im Bus“ entfernt den zurückgenommenen Einstieg aus dem aktuellen Bericht und vermerkt die Korrektur; vorhandene Exporte bleiben unverändert.
- Laufende gelöschte Berichte werden anhand eines gespeicherten Begleitungsschlüssels nicht erneut automatisch erzeugt. Neue Begleitungen bleiben möglich.
- Anschlusswarnungen werden für die passende, frische Anschlussfahrt aufgenommen. Berechnete Risiken stammen aus der App, gemeldete Ausfälle vom Betreiber; Daten- und Erfassungszeit bleiben getrennt.
- Das Fahrtenarchiv verlinkt den Bericht. Schreibfehler erscheinen in der Oberfläche; ein fehlgeschlagener Export gilt nicht als gespeichert. Alte PDF-Stände werden nicht überschrieben.
- `ReportPdfInstrumentation` prüft den tatsächlichen PDF-Writer auf Android, mehrseitiges Öffnen und Rendern über FileProvider, unveränderte frühere Exporte und den Teilen-Dialog. Der erforderliche CI-Job heißt `pdf-device-test`; sein Ergebnis steht im jeweiligen GitHub-Actions-Lauf. Lokale Emulatorstarts scheiterten an der Speicherprüfung der Umgebung.
- Echte Mitfahrten, TalkBack und physische Galaxy-/Watch-Bedienung sind weiterhin nicht geprüft.

### Nachweis Android-PDF-Test

GitHub Actions [43381319255](https://github.com/Eibelucas/VRR-status/actions/runs/43381319255), Job `pdf-device-test`, Android 10/API 29: erfolgreich. Eine echte PDF mit drei Seiten wurde geschrieben und über FileProvider mit `PdfRenderer` geöffnet und gerendert; der alte Export blieb unverändert, der Teilen-Dialog wurde geöffnet. PDF-Dateien, Testprotokoll und Bildschirmfoto liegen im Artefakt `android-pdf-device-test`. Die Testdaten sind synthetisch.
