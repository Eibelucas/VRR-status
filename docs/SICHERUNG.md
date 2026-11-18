# Mitfahrt-Sicherung (Format 1)

Eine Sicherung ist eine ZIP-Datei, die der Nutzer selbst über Androids Dateiauswahl speichert
(Berichte → Sicherung → „Sicherung erstellen“) und wieder einliest. Es gibt keinen automatischen
Export, keine Cloud und keine Übertragung.

## Inhalt
| Pfad | Inhalt |
|---|---|
| `manifest.json` | `format` = `mitfahrt-sicherung`, `version` = 1, `createdAt` (ISO-8601), Zähler `rides`, `reports`, `exports`, `legacy` |
| `archive.json` | Fahrtenarchiv im bestehenden Archivformat (`JourneyArchive.encode`) |
| `reports/journeys/<id>.json` | Fahrtbericht Version 2 (`JourneyReport.encode`) |
| `reports/journeys/<id>/export-<n>.json` | eingefrorener Datenstand der n-ten PDF |
| `reports/journeys/<id>/<name>.pdf` | erstellte PDF |
| `reports/<id>.json`, `reports/Mitfahrt-<id>.pdf` | ältere Einzelbelege (Version 1) |

`<id>` ist eine UUID. Andere Pfade kommen nicht vor.

## Prüfung beim Einlesen
Die ganze Datei wird zuerst gelesen und geprüft; geschrieben wird erst danach.
- Nur die Pfade oben; `..`, absolute Pfade, leere oder `.`-Segmente und unbekannte Ordner lehnen die Datei ab.
- Höchstens 3000 Einträge, 60 MB gesamt, JSON je 4 MB, sonstige Dateien je 15 MB.
- Unbekanntes Format oder neuere Version: abgelehnt.
- Jede JSON muss mit den App-Decodern lesbar sein; Bericht-ID und Dateiname müssen übereinstimmen.
- PDFs müssen mit `%PDF-` beginnen; zu jeder PDF muss der Bericht in der Sicherung liegen.

## Zusammenführen
- Nichts auf dem Gerät wird gelöscht.
- Fahrten: neu oder mit neuerem Stand (`updatedAt`) werden übernommen, ältere ignoriert. Bei ausgeschaltetem Archiv wird nichts übernommen.
- Berichte: fehlende werden ergänzt; ein vorhandener wird nur durch einen neueren, abgeschlossenen ersetzt. Ein laufender Bericht wird nie ersetzt.
- PDFs und eingefrorene Stände werden nur ergänzt, nie überschrieben.
- Jede Datei wird unter einem temporären Namen geschrieben und dann umbenannt. Erneutes Einlesen derselben Datei ändert nichts.
