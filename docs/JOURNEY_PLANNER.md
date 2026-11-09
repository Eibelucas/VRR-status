# Verbindungssuche, Startseite und Navigation

Stand: 09.11.2026, Branch `claude/home-and-journey-planner`. Teilumsetzung – siehe Abschnitt 6 „Lücken“.

## 1. Datenquelle

Öffentliche VRR-EFA-Schnittstelle `https://openservice.vrr.de/static03/<Endpunkt>`, dieselbe wie bisher.
Kein API-Schlüssel, keine Zugangsdaten. Jede Anfrage sendet `outputFormat=rapidJSON`, `language=de`,
`coordOutputFormat=WGS84[dd.ddddd]` (Koordinaten kommen als `[Breite, Länge]`).

Grundlage der Parameter: Mentz „EFA JSON API Training 2.9“ (veröffentlicht über MobiData BW) und die
für den VRR bekannten EFA-Clients. Eine Live-Prüfung der Verbindungssuche gegen `openservice.vrr.de`
war in der Entwicklungsumgebung **nicht möglich** (Netzwerk gesperrt). Die Tests arbeiten mit
synthetischen Antworten nach dokumentiertem Feldaufbau (`TripFixtures.kt`), nicht mit Aufzeichnungen.

### 1.1 Orte suchen – `XML_STOPFINDER_REQUEST`
| Parameter | Wert | Zweck |
|---|---|---|
| `type_sf` | `any` | Haltestellen, Adressen, Straßen, Orte, POIs |
| `name_sf` | Eingabe (max. 80 Zeichen) | Suchtext |
| `anyMaxSizeHitList` | `20` | Trefferzahl |
| `locationServerActive` | `1` | |

Gelesene Antwortfelder `locations[]`: `id`, `name`, `disassembledName`, `type`
(`stop`, `address`/`singlehouse`, `street`, `poi`, `locality`/`suburb`), `coord`, `parent.name` (Ort),
`isBest`, `matchQuality`. Einträge ohne `id` werden verworfen (außer Orte).

Die bisherige Haltestellensuche (`type_sf=stop`, `place_sf=Mönchengladbach`) bleibt für Haltestellen-Abfahrten
und „In der Nähe“ unverändert.

### 1.2 Verbindungen – `XML_TRIP_REQUEST2`
| Parameter | gesendet | Bedeutung |
|---|---|---|
| `type_origin`/`name_origin`, `type_destination`/`name_destination`, `type_via`/`name_via` | immer bzw. bei Zwischenziel | `any` + EFA-Kennung; ohne Kennung `coord` + `Länge:Breite:WGS84[dd.ddddd]:Name` |
| „Mein Standort“ | nur wenn gewählt **und** ein aktueller Standort vorliegt | `coord` + `Länge:Breite:WGS84[dd.ddddd]` (6 Nachkommastellen), sonst wird **keine** Anfrage gesendet |
| `itdDate` / `itdTime` | immer | Ortszeit Europe/Berlin, `yyyyMMdd` / `HHmm` |
| `itdTripDateTimeDepArr` | immer | `dep` (Jetzt/Abfahrt) oder `arr` (Ankunft) |
| `calcNumberOfTrips` | 5 (Aktualisieren bis 8) | |
| `calcOneDirection` | `1` | nur Verbindungen in Suchrichtung |
| `useRealtime` | `1` | Echtzeit einbeziehen |
| `routeType` | `leasttime` / `leastinterchange` / `leastwalking` | „Schnellste“, „Wenig Umstiege“ (= direkte bevorzugen), „Wenig Fußweg“ |
| `changeSpeed` | `slow` / `normal` / `fast` | Gehgeschwindigkeit |
| `maxChanges` | nur wenn gewählt, 0–3 | 0 = nur Direktverbindungen |
| `trITMOT=100` + `trITMOTvalue100` | nur wenn gewählt | maximaler Fußweg zu/von Haltestellen in Minuten |
| `ptOptionsActive=1`, `excludedMeans=checkbox`, `exclMOT_<n>=1` | nur bei abgewählten Verkehrsmitteln | Bahn 0,1,13–16 · U-/Stadtbahn/Tram 2–4 · Bus 5,6,7,19 · Rufbus/AST 10 · Sonstige 8,9,11 |
| `imparedOptionsActive=1` + `noSolidStairs`, `noEscalators`, `lowPlatformVhcl`, `wheelchair` | nur gewählte | EFA wertet **jeden** Wert als „an“, daher werden nicht gewählte Felder gar nicht gesendet |

Gelesene Antwortfelder:
- `journeys[].interchanges`, `journeys[].legs[]`
- `leg.origin` / `leg.destination` / `leg.stopSequence[]`: `id`, `name`, `parent.name` (Haltestelle),
  `properties.platformName`/`platform` (Gleis/Steig), `coord`, `departureTimePlanned`, `departureTimeEstimated`,
  `arrivalTimePlanned`, `arrivalTimeEstimated`, `isCancelled`, `realtimeStatus`
- `leg.transportation`: `id`, `number`/`disassembledName`, `name`, `product.class`, `product.name`,
  `destination.name` (Richtung), `operator.code`, `properties.tripCode`
- `leg.duration`, `leg.distance`, `leg.isRealtimeControlled`, `leg.realtimeStatus`
  (`CANCELLED`, `TRIP_CANCELLED`, `DELETED`, `NOT_SERVICED` = Ausfall; `MONITORED` = Echtzeit)
- `leg.coords` – echte Wegegeometrie, nur wenn geliefert; ungültige Punkte → keine Linie
- `leg.footPathInfo[].footPathElem[].type` – `STAIRS`, `ESCALATOR`, `ELEVATOR`, `RAMP`
- `leg.infos[]` (`subtitle`, `title`, `infoLinks[].subtitle`), `leg.hints[].content`, `leg.interchange.desc`
- `systemMessages[]` – EFA meldet „nichts gefunden“ als `error`; ohne `journeys` zeigt die App
  „Der VRR hat keine passende Verbindung geliefert“, ohne Ursache zu erfinden.

Abschnittsarten: Produktklasse 99/100 = Fußweg; 97 (Sitzenbleiben), 98 (gesicherter Anschluss), 105 (Taxi),
107 (Rad) werden **nicht** als Fahrt gezählt.

### 1.3 Trennung von NEW-Abfahrten
`EfaParser.departures` filtert weiterhin auf NEW (`operator.code = NEW` oder Linien-ID `new:`), damit die
Haltestellen-Abfahrten nur NEW-Busse zeigen. Die Verbindungssuche nutzt einen eigenen Parser
(`TripParser`) **ohne** Betreiberfilter: Züge (z. B. `ddb:…`, `DBNW`, Klasse 13) und fremde Busse bleiben
Teil der Reisekette (Test `NEW departure filtering is not applied to journeys`).
NEW-spezifische Funktionen (Livekarte, Fahrzeugerkennung) werden nur für Abschnitte mit `TripLeg.isNew` genutzt.

## 2. Zeiten, Live-Daten, Anschlüsse
- Planzeit und Prognose werden getrennt geführt (`TripStopTime.planned…` / `estimated…`). Eine Zeit gilt nur
  als live, wenn VRR eine `…Estimated`-Zeit liefert.
- Fußwege tragen meist nur Planzeiten. Erwartete Ab-/Ankunft der ganzen Verbindung = Live-Zeit des ersten/letzten
  Fahrzeugs minus/plus der von VRR gemeldeten Gehdauer.
- Anschlussbewertung (`TripTransfers`) **nur**, wenn ankommendes **und** abfahrendes Fahrzeug eine Prognose haben:
  Puffer = Abfahrt − Ankunft − Gehzeit; < 0 „Anschluss nicht erreichbar“, < 2 min „knapp“. Ohne Prognose keine Aussage.
  Ausfall des Anschlussfahrzeugs = nicht erreichbar.

## 3. Suche, Abbruch, Zwischenspeicher (`TripSearchEngine`)
- Jede neue Suche (Start, Ziel, Zeit, Optionen geändert) bricht laufende Such-, Blätter- und Aktualisierungsanfragen ab
  und erhöht eine Generationsnummer. Antworten älterer Generationen werden verworfen – auch wenn die HTTP-Anfrage
  selbst nicht abbrechbar war.
- Zwischenspeicher im Arbeitsspeicher: 12 Anfragen, je 60 s gültig; Anzeige „Stand“ zeigt dann die alte Abrufzeit.
- „Spätere“: Abfahrtssuche ab der spätesten geplanten Abfahrt (gleiche Minute, notfalls eine Minute später).
  „Frühere“: Ankunftssuche bis zur frühesten geplanten Ankunft. Doppelte Verbindungen (gleiche Fahrten, gleiche
  Planzeiten) werden zusammengeführt. Die Schnittstelle bietet **kein** eigenes Blättern; liefert sie nichts Neues,
  zeigt die App „keine weiteren Verbindungen“.
- Aktualisieren: Abfahrtssuche ab der ersten gezeigten Verbindung; nur gleiche Verbindungen werden ersetzt.
  Nicht mehr gelieferte Verbindungen behalten ihren alten Datenstand.

## 4. Gespeicherte Ziele und Suchverlauf (`TripPlacesStore`)
- Gespeicherte Ziele: nur ausdrücklich gespeichert, max. 20, mit optionalem Namen.
- Suchverlauf: nur nach tatsächlich gestarteter Suche, max. 12, einzeln löschbar, abschaltbar
  (Einstellung „Suchverlauf speichern“), Aufbewahrung nach „Fahrthistorie nach Tagen löschen“.
- „Mein Standort“ wird nur als Marker gespeichert, **nie mit Koordinaten**. Gespeichert werden höchstens die
  öffentlichen Koordinaten einer gewählten Haltestelle/Adresse.
- Eine Suche oder Vorschau erzeugt **keine** gespeicherte Route und **keinen** Archiveintrag.
