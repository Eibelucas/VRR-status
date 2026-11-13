## In Arbeit nach 0.18.0 (noch nicht veröffentlicht)

- **Fahrtbericht:** „Bericht“ öffnet den Bericht der ganzen Verbindung, ohne Haltestellenauswahl, auch ohne Prognose oder Störung. Er entsteht bei jeder Begleitung auf dem Gerät; geteilt wird nur auf Tippen.
- **Einstieg und Ausstieg:** Mitfahrt erkennt beides selbst. „Bin im Bus“ und „Doch nicht im Bus“ bleiben als Korrektur.
- **Bus und Karte:** Der Bus wird automatisch zugeordnet; nach dem Verschieben der Karte gibt es „Bus folgen“. Ohne Busposition zeigt die Karte deine eigene GPS-Position, so benannt.
- **Oberfläche:** ruhigere Farben, Linienschild, gruppierte Einstellungen, Uhr zeigt zuerst die Fahrt.

Nicht am Gerät und nicht auf einer echten Fahrt geprüft. Einzelheiten: `docs/AUTOMATIK_UND_BERICHTE.md`, Gestaltung: `DESIGN.md`.

## Mitfahrt 0.18.0

- **Neue Startseite:** Große „Wohin?“-Suche mit Standort als Start, gespeicherten Zielen und passenden Routen darunter. Ohne passende Routen entfällt der ganze Abschnitt.
- **In der Nähe:** Eigener Tab für Haltestellen und Abfahrten statt einer vollen Startseite.
- **Verbindungssuche:** Bus und Bahn, Umstiege, Fußwege, Abfahrt oder Ankunft, Datum/Uhrzeit, Zwischenziel und Suchoptionen. Details zeigen Haltestellen, gemeldete Verspätungen, Ausfälle und Strecken.
- **Fahrt begleiten:** Erst nach eigener Auswahl startet die Begleitung. Umstiege wechseln auf ausdrücklichen Wunsch zum nächsten Fahrzeug. Reines Nachschauen speichert keine Route.
- **Aktuelle Daten:** Verbindungen lassen sich aktualisieren; alte Prognosen werden nicht als aktuelle Live-Zeiten oder Anschlusswarnungen angezeigt. Die öffentliche VRR-Schnittstelle wurde mit echten Orts-, Abfahrts- und Ankunftsantworten geprüft.

Bestehende NEW-Abfahrten, GPS-Zuordnung, Buskarte, Berichte und Watch-Übertragung bleiben angebunden. Die neue Suche ist kein vollständiger Ersatz aller VRR-App-Funktionen: kein Ticketkauf, keine eigene Fußweg-Navigation; die Uhr übernimmt weiterhin den jeweils begleiteten Fahrtabschnitt.

Bitte Handy und Watch auf 0.18.0 aktualisieren. Daten und Signaturschlüssel bleiben erhalten. Die Bedienung auf einem echten Galaxy und einer Watch muss weiterhin geprüft werden.
