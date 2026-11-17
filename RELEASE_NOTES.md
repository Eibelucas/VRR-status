## In Arbeit nach 0.19.0 (noch nicht veröffentlicht)

- **Restzeit:** Aus der Position deines gewählten Busses, im Bus auch per GPS, berechnet Mitfahrt die verbleibende Fahrzeit und kennzeichnet sie als berechnet.
- **Karte:** Vollbild-Ansicht der Buskarte und ein neues Widget „Mitfahrt Buskarte“ mit Streckenskizze.
- **Eigene Texte:** Now Bar, Benachrichtigung und Buskarten-Widget mit Platzhaltern wie `{linie}` und `{minuten}` selbst formulieren, mit Vorschau.

Nicht am Gerät geprüft. Einzelheiten: `docs/AUTOMATIK_UND_BERICHTE.md`, Abschnitt 5a.

## Mitfahrt 0.19.0

- Neue, ruhigere Oberfläche in NEW-Farben, übersichtlichere Einstellungen und überarbeitete Watch-Anzeige.
- Einstieg automatisch anhand mehrerer Messungen erkennen; „Bin im Bus“, „Doch nicht im Bus“ und „Noch im Bus“ bleiben als Korrekturen erreichbar. Langsames Fahren nahe dem Ziel beendet die Fahrt nicht automatisch.
- Ein vollständiger Fahrtbericht pro begleiteter Verbindung, ohne Haltestellenauswahl und auch ohne Live-Prognose oder gemeldete Störung. Wartezeiten ohne erkannten Bus werden als eigene Aufzeichnung dokumentiert.
- Anschlusswarnungen im Bericht und direkter Berichtsaufruf aus dem Fahrtenarchiv. Falsche Einstiegserkennung lässt sich auch im Bericht korrigieren.
- Gelöschte laufende Berichte bleiben über Aktualisierungen und Neustarts hinweg gelöscht. Jeder PDF-Export behält seinen eigenen eingefrorenen Stand. Teilen erfolgt ausschließlich auf Wunsch.
- Speicher- und Exportfehler werden angezeigt. Alte Einzelbelege bleiben lesbar.
- Echte mehrseitige PDF-Erzeugung, Öffnen über FileProvider und Teilen-Dialog auf Android 10 automatisch geprüft.

Automatische Bus- und Einstiegserkennung ist abschaltbar. Die Schwellen sind mit synthetischen Szenarien geprüft; echte Mitfahrten und die Bedienung auf Galaxy/Watch bleiben zu prüfen. App-Erkennung und eigene Angaben sind keine offizielle Bestätigung des Verkehrsunternehmens.

Handy und Watch auf 0.19.0 aktualisieren; bisheriger Signaturschlüssel und gespeicherte Daten bleiben erhalten.
