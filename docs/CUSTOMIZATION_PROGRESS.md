# Konfigurierbare Mitfahrt: integrierter Stand 0.16.0

Die Entwicklung aus `feature/customization` und Claudes Branches `claude/android-widgets` sowie `claude/mitfahrt-extras` wurde zusammengeführt. Die Module sind an Oberfläche, Einstellungen und Fahrtverfolgung angeschlossen.

## Enthalten

- Suchbare Einstellungen, einfache/erweiterte Bereiche, Profile mit Zeitregeln, zuletzt geändert, Rücksetzen und sichere Routenvorlagen.
- Konfigurierbare Startkarten, Navigation, Suchradius, Linien, Anzeige, native Buskarte und Darstellung.
- Routen mit mehreren Zeitfenstern, Wochentagen, Linien, Farben/Symbolen, Fußweg/Puffer, Schulpausen und Ausnahmen für einzelne Tage.
- NRW-Schulkalender mit Feiertagen, veröffentlichten Ferien und eigenen freien bzw. ausdrücklich als Schultag gewählten Tagen.
- Archiv tatsächlich gestarteter Fahrten mit Suche, Einzel-/Mehrfachlöschung, sicherem Rückgängig und Aufbewahrungsfrist. Vorschauen und Hintergrundabfragen legen keine Einträge an.
- Persönliche Ankünfte mit Herkunftsangabe, Haltestellenfilter und einzelner Löschung. Standorterkennung und Speicherung sind abschaltbar.
- Native Android-Widgets für Haltestellen, Routen und die aktive Fahrt; globale Vorgaben oder einzelne Konfiguration. Uhrzeit und sichtbares Datenalter statt eines möglicherweise eingefrorenen Live-Countdowns.
- Live-basierte Alarme, Ruhezeiten, Geräteziel und Vibration; Now-Bar-Inhalte, Aktionen und Sperrbildschirm-Details.
- Wear-OS-Routen-/Favoritenauswahl, ausdrücklicher Start, sichere Fahrtaktionen, Kachel und Komplikation.
- PDF-Belege, eigene Freigabe persönlicher Angaben, Dateiexport, Belegvorschläge, Datenverwaltung und Update-Einstellungen.

## Grenzen

- Die Ferienabdeckung reicht vom 20.07.2026 bis 27.04.2030; schulinterne bewegliche Ferientage bleiben eigene Angaben. Fehlende Abdeckung wird angezeigt.
- Die Datenquelle ist auf NEW-Busabfahrten ausgerichtet. Die noch nicht unterstützte allgemeine Verkehrsmittelauswahl wird nicht als nutzbare Einstellung angeboten.
- Eindeutige Fahrzeugzuordnung hängt von den NEW-Quelldaten ab. Eine manuelle Auswahl bestätigt keine konkrete VRR-Abfahrt. GPS-Positionen werden nicht in erfundene Ankunftsprognosen umgerechnet.
- Widget-Aktualisierungen unterliegen Androids Energiesparregeln; Alter und Quelle bleiben sichtbar.
- Eine physische Samsung-/Wear-OS-Prüfung ist nicht erfolgt. Die gesamte frühere Wunschliste ist damit nicht als auf Hardware vollständig abgenommen erklärt.

## Validierung

Gemeinsame und Handy-/Watch-Unit-Tests, Android Lint und Release-Builds werden für den integrierten Quellstand ausgeführt. Der Testlauf umfasst 483 Handy-, 20 gemeinsame und 10 Watch-Tests. Die öffentlichen Update-Dateien und beide APK-Signaturen werden vor der Veröffentlichung geprüft.
