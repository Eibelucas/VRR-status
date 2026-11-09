## Mitfahrt 0.17.0

- **Bus anhand von GPS erkennen:** Linie, Richtung, aktuelle NEW-Meldungen und mehrere Handypositionen werden gemeinsam bewertet. Beim Warten zählen Annäherung und Einstiegsrichtung; unterwegs die gemeinsame Bewegung. Mehrdeutige Treffer verlangen eine Auswahl, manuelle Auswahl bleibt erhalten.
- **Straßenverlauf statt gerader Haltestellen-Verbindungen:** Die Karte verwendet geprüfte VRR-Fahrtgeometrie. Die zusätzliche Schnittstelle wurde mit einer aktuellen NEW-Fahrt und 830 Streckenpunkten live geprüft. Ohne passende Geometrie werden nur Haltestellen gezeigt. Eine geprüfte frühere Linienvariante wird ausdrücklich als solche gekennzeichnet.
- **Nächster Halt per GPS:** Nach „Bin im Bus“ kann der Fortschritt entlang der geordneten Haltestellen auch ohne Live-Ankunftszeiten erkannt werden. Anzeige und Ausstiegsalarm unterscheiden aktuelle Prognosen, GPS-Erkennung und Fahrplan. Einzelne Sprünge dürfen keine Halte überspringen; veraltete Positionen zählen nicht weiter.
- **Standort und Herkunft:** Handypositionen bleiben im Arbeitsspeicher. Der Standortmodus „nur bei geöffneter App“ wird beachtet; ausgeschalteter Standort leert den Positionspuffer. Eigene GPS-Erkennung bestätigt keine offizielle Zuordnung zur VRR-Abfahrt und erzeugt keine erfundenen Ankunftszeiten.

Neue Optionen stehen unter Einstellungen → Alle Einstellungen & Profile. GPS-Erkennung ist eine Unterstützung; die Schwellen sind noch nicht anhand echter mitgefahrener Fahrten kalibriert. Eine Prüfung auf einem echten Galaxy und einer Watch ist weiterhin erforderlich. Die Streckengeometrie hängt von der jeweiligen VRR-Antwort ab.

Bitte Handy und Watch auf 0.17.0 aktualisieren. Bereits gespeicherte Daten und der Signaturschlüssel bleiben erhalten.
