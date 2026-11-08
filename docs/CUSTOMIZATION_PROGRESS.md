# Konfigurierbare Mitfahrt: Arbeitsstand

Entwicklungsstand für die Erweiterung nach Version 0.15.0. Noch kein neues APK-Release. Die große Funktionsliste ist nicht vollständig abgeschlossen.

## Im Quellcode eingebunden

- Zentral validierte Einstellungen: Suche, Gruppen, erweiterte Optionen, zuletzt geändert, Profile mit Zeitregeln, Rücksetzen und Sicherung/Import eigener Routenvorlagen.
- Konfigurierbare Startkarten, Navigation, Suchradius, Linienfilter, Listenanzeige und Darstellung.
- Eigene Routen mit mehreren Zeitfenstern, Wochentagen, Linien, Farben/Symbolen, Fußweg/Puffer, Schulpausen und einzelnen ausgeblendeten Tagen.
- Native NEW-Buskarte als eigene Ansicht sowie bei aktiven Fahrten vor und nach dem Einstieg; Kamera, Ebenen, Datenstand und Kartenstil.
- Live-basierte Vorwarnungen, Losgeh-, Verspätungs-, Ausfall- und Ausstiegshinweise mit Ruhezeiten, Gerätezielen und Vibrationsmustern.
- Konfigurierbare Now-Bar-Inhalte und Aktionen; Standortmodi und optionale eigene Ankunftserkennung mit Bestätigung.
- Wear-OS-Routen-/Favoritenauswahl, ausdrücklicher Fahrtstart, sichere Aktionen, Kachel und Komplikation.
- Eigene Ankünfte, Belegvorschläge, anpassbare PDF-Darstellung und Dateiexport, Löschfristen und Datenverwaltung.
- Getrennte Lernschalter, Vorschlagsschwellen und Update-Einstellungen.

## Noch offen / parallel abgegeben

- Android-Homescreen-Widgets: eigener Claude-Auftrag. Widget-Einstellungen erscheinen erst, wenn ein Widget-Provider tatsächlich registriert ist.
- Tagesausnahmen mit abweichenden Uhrzeiten, NRW-Schulkalender, vollständiges Archiv tatsächlich verfolgter Fahrten und einzelne Ankünfte in einer Übersicht: Zusatzauftrag in CLAUDE_EXTRAS.md.
- Integration der gelieferten Claude-Module, Prüfung jeder Einstellung gegen die vollständige Funktionsliste und anschließender Release-Durchlauf.
- EFA-Abfahrtsparser liefert derzeit gezielt NEW-Verbindungen; eine allgemeine Verkehrsmittelauswahl benötigt vor Veröffentlichung noch einen vollständigen Abgleich mit der Datenquelle.
- Visuelle Prüfung und Praxistest auf Android/Samsung/Wear OS ausstehend. Keine Hardwareprüfung behauptet.

## Prüfung dieses Stands

Zentral ausgeführt: `:shared:test :app:testDebugUnitTest :wear:testDebugUnitTest :app:lintDebug :wear:lintDebug`.

Ergebnis: 372 App-, 20 gemeinsame und 10 Watch-Tests erfolgreich, insgesamt 402. Beide Lint-Prüfungen ohne Fehler. Release-Signierung und APK-Veröffentlichung sind in diesem Arbeitsstand noch nicht gelaufen.
