# Erweiterungen nach 0.20: Karten, Audio, Uhr, Akku, Archiv

Stand 18.11.2026. Alle Szenarien in den neuen Tests sind synthetisch, außer den gekennzeichneten
VRR-Antworten vom 18.11.2026. Nichts davon wurde auf einem Galaxy oder einer Watch bedient.

| Nr. | Stand | Datenquelle | Grenzen | Tests |
|---|---|---|---|---|
| 1 Livekarte | Filter nach Linie und Richtung aus den gerade gemeldeten Bussen, Übersicht oder „Bus folgen“, Abrufalter, Busse ab 45 s blass und markiert, ab 90 s entfernt, Ausschnitt bleibt in der Übersicht beim Aktualisieren | NEW-Livedaten | Kartenbild nicht im Robolectric-Rendering geprüft | `MapFeaturesTest` |
| 3 Busse vergleichen | Auswahlliste mit Richtung, Fahrzeugnummer, Abstand, Stand und Zuordnungsstatus; „gleiche Linie“ ist ausdrücklich keine Zuordnung | NEW, eigene Auswahl, Erkennung | – | `MapFeaturesTest` |
| 4 Sperrbildschirm | Buskarten-Widget wird bei kleiner Fläche/auf dem Sperrbildschirm kompakt (ohne Skizze), Tippen öffnet Fahrt bzw. Karte; Positionshinweis in der Fahrtbenachrichtigung (abschaltbar) | NEW (nur zugeordneter, frischer Bus) | Ob Samsung Widgets oder Hinweise dort zeigt, entscheidet das System; ungeprüft | `MapFeaturesTest` |
| 5 Fahrtrichtung | Pfeile auf geprüfter Streckengeometrie; Pfeil am Bus nur bei Fahrplanbestätigung oder ≥ 50 m Bewegung in 3 Min, sonst neutral | VRR-Geometrie, NEW-Positionen | – | `MapFeaturesTest` |
| 7 Bussteige | MG Hbf und Rheydt Hbf: Steige mit Linien, Richtungen, nächster Abfahrt; auf der Karte nur bei übereinstimmender VRR-Koordinate | VRR-Abfahrtstafel (`XML_DM_REQUEST`, Steig-IDs mit Koordinaten), Stand wird angezeigt | Steige ohne Abfahrt in den nächsten ~120 Fahrten fehlen | `StationPlatformsTest` mit echten Antworten |
| 9 Offline-Karte | **Nicht als Download umgesetzt.** Kein Datensatz gefunden, der ohne neue Abhängigkeit (Vektor-/Mapsforge-Renderer) und ausdrücklich für Offline-Nutzung freigegeben ins bestehende osmdroid passt; Massendownload von tile.openstreetmap.org ist untersagt. Umgesetzt: deutlicher Offline-Hinweis, Strecke und Halte aus App-Daten bleiben sichtbar, zwischengespeicherte Kacheln wie bisher | – | Blockade, siehe oben | – |
| 11 Liniennetz | Suche nach Liniennummer oder Haltestelle, Richtung wählen, Haltestellenfolge mit Positionen; Strecke nur aus einer aktuellen Fahrt genau dieser Linie und Richtung, geprüft gegen die Halte | VRR `XML_SERVINGLINES_REQUEST`, `XML_LINESTOP_REQUEST`, Fahrtverlauf | Ohne aktuelle Fahrt keine Strecke (wird gesagt) | `StationPlatformsTest` |
| 28 Ansagen | Optional „Nächster Halt: …“ einmal je Fortschritt, nur bei Live- oder GPS-Fortschritt; pausierbar; Korrektur zurück setzt still zurück | TextToSpeech des Geräts | Sprachausgabe nicht am Gerät gehört | `SpeechPolicyTest` |
| 29 Kopfhörer | Ausstieg zusätzlich gesprochen, nur zusammen mit dem Vibrationshinweis; „Nur über Kopfhörer“ bleibt ohne Kopfhörer stumm; nie im Telefonat; Lautsprecher nur ohne „Nicht stören“; Abbruch beim Trennen | Audiofokus (kurzzeitig, leiser stellen) | ungeprüft am Gerät | `SpeechPolicyTest` |
| 31 Uhr offline | Letzter Stand 30 Min sichtbar als „Offline · Stand …“, danach ausgeblendet; „Beim VRR prüfen“ fragt auf Wunsch genau diese Abfahrt (Linie, Fahrt, Planzeit) über das Internet der Uhr ab | VRR `XML_DM_REQUEST` | Watch-Internet ungeprüft; neue Berechtigung INTERNET in der Watch-App | `WatchOfflineTest` |
| 32 Complication | Live: Countdown, den das Zifferblatt selbst weiterzählt; offline: „Stand hh:mm“; sonst „–“; ohne Fahrt die Kachel-Abfahrt; Tippen öffnet die App | Handy-Daten | ungeprüft auf Zifferblättern | `WatchOfflineTest` |
| 33 Routenwechsel | Wechsel auf der Uhr wird erst als erledigt angezeigt, wenn das Handy die neue Fahrt meldet; Ablehnung (z. B. veraltete Auswahl) bleibt sichtbar; Handy prüft weiter Fahrtschlüssel und 120-s-Frist | Companion-Kanal | ungeprüft auf Hardware | `WatchOfflineTest`, bestehende Policy-Tests |
| 45 Pünktlichkeit | Zeitraum und Strecke wählbar, Stichprobe sichtbar, ohne Daten nicht mitgezählt; VRR-Prognose und eigene Ankunft getrennt; „pünktlich“ = unter 3 Min | Fahrtenarchiv, eigene Ankünfte | VRR-Wert ist die letzte gesehene Prognose, keine gemessene Ankunft | `ArchiveAndBackupTest` |
| 46 Kalender | Monatsansicht im Fahrtenarchiv, Tag antippen öffnet die Liste dieses Tages mit Löschen/Rückgängig und Bericht | Fahrtenarchiv | – | `ArchiveAndBackupTest`, Bilder |
| 48 Schullayouts | Lokale Layouts mit Absender, Schule, Klasse, Abschnitten; Vorschau im Bericht, gilt für neue PDFs; Hinweis „keine offizielle Bescheinigung“ immer enthalten | – | – | `ArchiveAndBackupTest`, Bilder |
| 49 Sicherung | Siehe `docs/SICHERUNG.md` | – | Große Sicherungen werden im Speicher geprüft (max. 60 MB) | `ArchiveAndBackupTest` |
| 50 Akku | „Akku sparen“: aus / Fahrten über 20 Min / immer; seltener Abruf (90 s) und GPS (20 s, 50 m), normal 5 Min vor Ein-/Ausstieg, bei möglichem Ein-/Ausstieg und Ausfall; Hinweis auf der Fahrtseite | – | Verbrauch nicht gemessen | `PowerPolicyTest` |

## Ausgeführte Prüfungen
Siehe PR-Beschreibung; dort stehen die tatsächlich gelaufenen Gradle-Aufgaben und Ergebnisse.
