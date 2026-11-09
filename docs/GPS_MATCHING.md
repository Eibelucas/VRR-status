# Fahrzeugerkennung, Streckenverlauf und GPS-Haltestellen

Stand: 09.11.2026, Branch `claude/gps-bus-matching`.

## 1. Untersuchte Datenquellen

### NEW-Livepositionen (`livegeomatching/busses`, Firebase, öffentlich wie livebus.new.de)
| Feld | Bedeutung | Verwendung |
|---|---|---|
| `raw.lat` / `raw.lon` | gemessene Fahrzeugposition | Position, Bewegung, Vergleich mit Handy |
| `raw.time` / `send_time` | Messzeit | Alter, zeitliche Paarung mit Handy-Fixes |
| `raw.route_translated`, `raw.route`, `geomatching.LI_NR` | Linie | Kandidatenfilter |
| `raw.terminus`, `geomatching.LIDNAME` | Zielanzeige | Richtung (nur wenn eindeutig) |
| `geomatching.NEXT_ORT_REF_ORT_NAME` | nächster Halt laut NEW | Plausibilität beim Warten (+10 / −20) |
| `geomatching.PLAN_ABWEICHNG(_MIN)` | Verspätung laut NEW | Vergleich mit VRR-Verspätung |
| `geomatching.FAHRTWEG[]` | **nur Haltepunkte** (`ORT_POS_BREITE_GPS`, `ORT_POS_LAENGE_GPS`, Name, Zeit) | Fahrplanabgleich (`NewVehicleMatcher.confirmed`), **keine Straßengeometrie** |
| `geomatching.FRT_START`, `SCHEDULE_ID` | Fahrtbeginn, Umlauf | Fahrplanabgleich |

NEW liefert **keine Linien- oder Fahrtgeometrie**. `FAHRTWEG` sind Haltestellenkoordinaten.

### VRR EFA (`openservice.vrr.de/static03`)
| Abfrage | Geometrie | Befund |
|---|---|---|
| `XML_TRIPSTOPTIMES_REQUEST` (bisher genutzt) | `leg.stopSequence[].coord` = Haltepunkte | Die aufgezeichnete Antwort (`src/test/resources/ride.json`) enthält **kein** `leg.coords` (Test vorhanden). |
| Fahrtdetails mit `leg.coords` | Polylinie `[[lat, lon], …]`, teils einmal zusätzlich verschachtelt | Wird vom EFA-Client *Travel::Status::DE::EFA* (VRR) als Fahrtverlauf gelesen; dort ausdrücklich: „wenn das Backend keine Koordinaten liefert, leer“. |
| `XML_STOPSEQCOORD_REQUEST` | wie oben (gleiche Parameter wie TRIPSTOPTIMES) | Wird jetzt höchstens einmal je Fahrt und 20 Minuten versucht. **Live geprüft am 09.11.2026:** Eine aktuelle NEW-Fahrt ab Hehn lieferte 830 Linienpunkte in `leg.coords` (Reihenfolge Breite/Länge). |
| `XML_LINESTOP_REQUEST` | nur Haltepunkte (`includeCoords`) | ungeeignet |

Zuordnung: Eine EFA-Polylinie wird nur akzeptiert, wenn `transportation.id` und `properties.tripCode` exakt zur gewählten Fahrt passen **und** die Haltestellen der Fahrt in ihrer Reihenfolge innerhalb von 60 m auf der Linie liegen (Einstieg und Ziel Pflicht, ≥ 80 % der Halte dazwischen).

### OpenStreetMap
Geprüft, **nicht umgesetzt**: Busrouten-Relationen (PTv2) über Overpass würden einen neuen externen Dienst mit eigener Nutzungsrichtlinie einführen. Qualität, Richtungs- und Variantentreue für NEW-Linien in MG konnte hier nicht überprüft werden. Ein Auto-Router kennt weder Busspuren noch Umleitungen, deshalb wird er nicht genutzt.

## 2. Quellenreihenfolge für die Karte
1. `leg.coords` dieser Fahrt (VRR) → „Linienverlauf dieser Fahrt laut VRR“
2. Geprüfte Geometrie einer früheren VRR-Fahrt mit gleicher Linie, Zielanzeige und Stationsfolge (Cache im App-Cache, max. 40 Einträge, 60 Tage) → „Linienverlauf einer früheren VRR-Fahrt mit gleicher Haltestellenfolge“
3. Sonst **keine Linie**, nur Haltestellen → „Kein Streckenverlauf verfügbar – nur Haltestellen“

Ursache der bisherigen geraden Linien: `LiveBusCard` gab `current.routeStops.map { it.point }` (NEW-Haltepunkte) bzw. `ride.stops.mapNotNull { it.point }` als `Polyline` an die Karte; `BusMapOverview` ebenso. Beides ist entfernt.

Projektion auf die Linie: nur bis 35 m seitlich und bei passender Fahrtrichtung (≤ 60°). Bei wiederholt > 80 m Abstand gilt das Fahrzeug als abweichend (mögliche Umleitung) und wird an seiner gemeldeten Position gezeigt. Keine Animation, Positionsalter bleibt sichtbar.

## 3. Fahrzeugerkennung (`VehicleMatching.kt`)
Punktebewertung, **keine Wahrscheinlichkeit**. Zuordnung nur bei Score ≥ Schwelle, Abstand ≥ 25 Punkte zum Zweitbesten und zwei Auswertungen mit **neuen** NEW-Messungen im Abstand ≥ 15 s.

Beim Warten (Schwelle 50): Richtung +15, auf der Linie +15/+5, am Einstieg +10, Annäherung entlang der Strecke +20, Gegenrichtung −45, NEW-„nächster Halt“ ±, Position passt zur Live-Zeit +30…−30, NEW-Verspätung passt zur VRR-Zeit +15/−25. Ausschluss: abseits der Linie oder > 150 m hinter dem Einstieg.

Im Bus (Schwelle 60, Nachfrage ab 40): Paarung jeder NEW-Messung mit dem zeitlich nächsten Handy-Fix (≤ 20 s); Toleranz = Handygenauigkeit + 35 m + Geschwindigkeit × Zeitversatz. Gemeinsame Strecke, Streckenverhältnis, Bewegungsrichtung; Handy ≤ 2,2 m/s bei fahrendem Bus = zu Fuß (−50).

Halten/Verlust: Zuordnung bleibt ab 20/25 Punkten. Bei Verlust 2 Auswertungen und ≥ 60 s „wird erneut geprüft“, erst dann darf ein anderes Fahrzeug (mit voller Bestätigung) übernommen werden. Manuelle Auswahl hat immer Vorrang; „Nein“ schließt ein Fahrzeug für diese Fahrt aus.

Die Schwellen beruhen auf typischen MG-Werten (Haltabstand 250–600 m, Takt 10–20 min, GPS 5–30 m). Sie sind **nicht** an echten Fahrten kalibriert.

## 4. Nächster Halt per GPS (`GpsStopProgress.kt`)
Erst nach „Bin im Bus“. Fortschritt nur vorwärts entlang der Linie (oder intern der Haltestellenkette, nie gezeichnet). Ein Halt gilt als passiert, wenn **zwei** akzeptierte Fixes ≥ 25 m (Geometrie) bzw. ≥ 40 m (Kette, plus tatsächliche Annäherung) dahinter liegen. Sprünge über ≥ 2 Halte oder > 30 m/s brauchen einen zweiten bestätigenden Fix; Rückwärtssprünge werden ignoriert. Kein Fortschritt nach Uhrzeit. Ein Aufenthalt wird nur bei gemessenem Stillstand (≥ 10 s) vermerkt.

Anzeige: „Live-Daten“ (frische VRR-Prognosen, nur dann Restminuten), „Per GPS erkannt“ (ohne Restzeit), „Laut Fahrplan“ (Uhrzeit, wenn kein Standort genutzt wird), sonst „Nicht sicher erkannt“ mit letzter bestätigter Position.

Ausstiegsalarm ohne frische VRR-Daten nur bei frischem (≤ 45 s), genauem (≤ 50 m) GPS-Fortschritt: vorletzter bedienter Halt passiert oder Ziel ≤ 400 m (Geometrie) bzw. ≤ 300 m (Kette) und kommt näher. Gleiche Ereignis-ID wie bisher, daher keine Wiederholung.

## 5. Datenschutz
Handypositionen nur im Arbeitsspeicher des Dienstes (max. 5 Minuten, 120 Fixes), nicht gespeichert, nicht übertragen. Bei abgeschaltetem Standort wird der Puffer geleert. Die Geometrie im Cache ist öffentliche Liniengeometrie.

## 6. Einschränkungen / Praxistest nötig
- Für eine aktuelle NEW-Fahrt wurde STOPSEQCOORD mit 830 Punkten bestätigt; TRIPSTOPTIMES enthielt keine Geometrie. Andere Fahrten können weiterhin ohne Geometrie bleiben.
- Schwellen sind aus typischen Werten abgeleitet, nicht aus aufgezeichneten Fahrten.
- Der Standort-Listener liefert erst nach 12 m Bewegung neue Fixes; ein stehender Bus erscheint nach 45 s als „Letzte bestätigte Position“.
- Android-Kompilierung ist in der Integrationsumgebung erfolgreich; der vollständige Test- und Release-Durchlauf erfolgt vor Veröffentlichung.
- Widget und Watch zeigen den GPS-erkannten Halt über `gpsPassedIndex`, aber noch ohne Herkunftsangabe.
