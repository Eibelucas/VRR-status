# Mitfahrt: Gestaltung

Grundlage ist der Auftrag des Projektinhabers: eine alltägliche Nahverkehrs-App für Mönchengladbach, auch für
Mitschüler, ohne Erklärung verständlich, unterwegs schnell bedienbar, modern, an NEW-Farben angelehnt, gut lesbar
draußen, im Dunkeln und mit großer Schrift. Die Regeln unten setzen das um; sie wurden mit anti-slop im Modus
„during“ geprüft (Bericht in `docs/AUTOMATIK_UND_BERICHTE.md`).

Lesart: Nahverkehrs-App für Schüler und Pendler in Mönchengladbach, ruhig und beschildert wie eine Haltestelle.
Dial: ENERGY 1 / RHYTHM 2 / MOTION 1

## Farbe
- Ein Markenton: NEW-Beere `#B40055` (dunkel `#FF9BC7`). Dazu nur Neutraltöne und Bernstein `#925000` / `#FFCB83` für Abweichungen.
- Beere steht für genau drei Dinge: das Linienschild, die eine Hauptaktion eines Bildschirms und die Fahrtkarte der laufenden Fahrt.
- Zeiten sind neutral. Farbe bekommt eine Zeit nur, wenn sie vom Fahrplan abweicht (Bernstein), immer zusammen mit Text wie „+2 Min“.
- Hell und dunkel folgen dem System und sind in den Einstellungen umschaltbar. Alle Textpaare erfüllen WCAG AA (nachgerechnet, siehe Bericht).

## Form
- Drei Radien mit Bedeutung: Linienschild 6 dp, Bedienelemente 12 dp (`ControlShape`), Behälter 20 dp (`Shape`).
- Wiederkehrendes Motiv ist das Linienschild (`LineSign`): die Liniennummer als gefülltes Schild wie auf der Busanzeige.
- Karten nur, wo etwas zusammengehört und antippbar ist (Route, Verbindung, Abfahrt). Listen mit Text (Einstellungen, Berichte, Bericht selbst) stehen ohne Karte auf dem Hintergrund, getrennt durch Abstand oder eine feine Linie.

## Schrift und Text
- Systemschrift (Roboto): folgt der eingestellten Schriftgröße und ist auf Geräten im Freien erprobt. Keine gesperrten Großbuchstaben-Zeilen.
- Reihenfolge auf jedem Bildschirm: Ziel, Linie, Richtung, Wartezeit, nächste Handlung.
- Kurze deutsche Sätze in der Du-Form. Kein Geviertstrich, keine Werbesprache. Ein Zustand wird mit Wort benannt, nie nur mit Farbe.
- Herkunft jeder Angabe steht dabei: „VRR-Angabe“, „GPS-Erkennung der App“, „Eigene Angabe“.

## Bedienung
- Eine Hauptaktion je Bildschirm, gefüllt; alles andere umrandet oder als Textschaltfläche.
- Tippflächen mindestens 48 dp. Bei Schriftgröße über 125 % stehen nebeneinanderliegende Schaltflächen untereinander.
- Bewegung nur als Ladeanzeige; „Weniger Animationen“ ersetzt sie durch ein stehendes Zeichen.
- Uhr: zuerst die Fahrt und der nächste Halt, danach Aktionen, zuletzt der Wechsel zu Routen und Haltestellen.
