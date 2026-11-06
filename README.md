# Mitfahrt

Eine native Android-App für die NEW-Busse in Mönchengladbach. Kotlin, Jetpack Compose und Material 3. Kein Konto und kein Backend nötig.

## Verwenden

1. APK auf dem Android-Handy installieren und Mitfahrt öffnen.
2. **Fahrt wählen** → Haltestelle suchen → passende Linie und Richtung antippen.
3. Restzeit, nächste Haltestelle und Fahrtverlauf werden angezeigt. **Fahrt beenden** stoppt den Hintergrunddienst.

Alternativ **Erkennung starten**: Mitfahrt sucht nach `NEW-Hotspot` und schlägt NEW-Fahrten an nahe gelegenen Haltestellen vor. Die konkrete Fahrt muss bestätigt werden. Ein sichtbarer Hotspot beweist weder, dass man im Bus sitzt, noch welche Linie fährt. Die App verbindet sich nicht selbst mit einem WLAN.

Unter **Einstellungen → Demofahrt ansehen** lassen sich ausdrücklich gekennzeichnete Beispieldaten ausprobieren.

## Verspätungsbericht für die Schule

### Eigene Ankunft festhalten

Vor dem Einsteigen: **Haltestellen → Haltestelle auswählen → Bin angekommen**. Während der Fahrt: **Bin angekommen → Haltestelle wählen**. Die App speichert den Zeitpunkt des Tastendrucks nach der Geräteuhr. Dafür sind weder GPS noch eine zusätzliche Berechtigung erforderlich; die Angabe ist eine Selbstbestätigung, keine Standortprüfung.

Passende eigene Ankünfte erscheinen automatisch im neu erstellten Bericht. Sie stehen in einem eigenen Abschnitt, getrennt von den VRR-Zeiten des Busses. Zum Zuordnen müssen Haltestelle und Zeitfenster zur Fahrt passen: bis drei Stunden vor der geplanten Abfahrt bzw. nach dem errechneten Fahrtende. Zukünftige Einträge werden ausgeschlossen. Bei erneuter Erfassung am selben Halt verwendet ein neuer Bericht die jüngste passende Bestätigung. Frühere Datensätze und bereits erstellte PDFs werden dadurch nicht verändert.

### Bericht teilen

In der Fahrtansicht **Verspätung belegen** antippen und die gewünschte Haltestelle auswählen. Die App erstellt ein PDF und öffnet den Android-Dialog zum Teilen. Unter **Berichte** lassen sich gespeicherte PDFs erneut teilen und aus der letzten Fahrt auch nach dem Aussteigen Berichte erstellen.

Das PDF enthält Linie, Richtung, Haltestelle, Fahrttag, Fahrplanzeit, gemeldete Prognose, Abweichung einschließlich Sekunden, Abruf- und Erstellungszeit, Quellabfrage und Fahrtkennung. Vorhandene Verkehrsmeldungen und Ausfälle bleiben erkennbar. Der Bericht ist eine eingefrorene Momentaufnahme und wird durch spätere Fahrtdaten nicht verändert.

Die Ankunft des Busses am ausgewählten Halt wird ausdrücklich als Fahrplanzeit und VRR-Prognose ausgewiesen. Liefert der VRR nur eine Abfahrtsmeldung, wird diese nicht als Ankunft behandelt; fehlende Ankunftsangaben erscheinen als **Nicht verfügbar**. Für Ankunftsprognosen aus dem Fahrtverlauf bleibt auch deren eigene Abrufzeit und Quellabfrage erhalten. Die selbst erfasste persönliche Ankunft wird davon unabhängig mit Haltestellenname und Zeitpunkt dokumentiert.

Es werden ausschließlich tatsächlich vom VRR gelieferte Prognosen verwendet. Eine anhand der letzten Verspätung hochgerechnete Ankunft wird niemals als gemeldete Prognose exportiert. Liegen für einen Halt nur Fahrplanzeiten vor, ist er nicht auswählbar; häufig ist dann nur die Einstiegshaltestelle mit ihrer gemeldeten Abfahrtsprognose verfügbar. Fehlende oder alte Abrufzeiten werden ausdrücklich als gespeicherter Datenstand gekennzeichnet. Aus Demofahrten können keine Belege erstellt werden.

Der Bericht ist ein privater Nachweis aus VRR-Daten, keine offizielle Bescheinigung von NEW oder VRR. Eine Prognose bestätigt keine tatsächliche Ankunft oder persönliche Mitfahrt. Dies steht auch im PDF.

## Now Bar

Unter Android 16 verwendet die App eine `Notification.ProgressStyle` mit Fortschritt, Liniennummer, nächstem Halt, Restzeit und einer Beenden-Aktion. Die Promotion wird über die öffentliche Android-Extra `android.requestPromotedOngoing` angefragt und die Berechtigung `POST_PROMOTED_NOTIFICATIONS` deklariert. Die Promotion wird nur für eine vom Nutzer gestartete Fahrt angefragt, nicht für die allgemeine WLAN-Suche.

Samsung entscheidet je nach One-UI-Version, Gerät und Einstellungen über die tatsächliche Darstellung in der Now Bar. Die App bietet einen Link zu den Live-Benachrichtigungseinstellungen. Auf älteren Android-Versionen gibt es eine normale laufende Benachrichtigung. Die App benötigt mindestens Android 10. Eine tatsächliche Samsung-Now-Bar-Darstellung muss auf einem unterstützten Galaxy getestet werden.

## Daten und Erkennung

- VRR EFA, `https://openservice.vrr.de/static03/`, ohne API-Schlüssel: StopFinder, Coord, Departure Monitor und TripStopTimes mit `rapidJSON`.
- Suche ist auf Mönchengladbach ausgerichtet; Abfahrten werden auf den Operator NEW gefiltert.
- Ein Verlauf wird nur akzeptiert, wenn Linie, Fahrtcode, Einstiegshaltestelle und Abfahrtszeit zusammenpassen.
- Fahrten werden alle 30 Sekunden neu abgefragt, die Benachrichtigung spätestens alle zehn Sekunden aktualisiert. Im aktiven Vordergrunddienst helfen GPS-Updates bei der Erkennung passierter Halte.
- Einzelne Ankunftsprognosen des VRR haben Vorrang. Fehlen sie, wird die letzte bekannte Abfahrtsverspätung auf den Fahrplan aufgeschlagen und als **geschätzt** markiert. Ohne Verspätungsdaten gilt **laut Fahrplan**. Es gibt keine garantierte Fahrzeugposition.
- Nach fehlgeschlagener Abfrage oder mehr als zwei Minuten ohne neue Daten erscheint ein Hinweis auf den letzten Stand. Ausfälle und entfallende Halte werden berücksichtigt.
- WLAN-Erkennung prüft das verbundene Netz und aktuelle Scanergebnisse. Dafür sind genauer Standort, eingeschaltete Standortdienste und WLAN erforderlich. Ab Android 13 wird zusätzlich die WLAN-Geräteberechtigung angefragt. Android kann Scans drosseln, vor allem bei gesperrtem Bildschirm. Ein bereits verbundenes `NEW-Hotspot` ist deshalb zuverlässiger als reine Umgebungserkennung.
- Keine Startautomatik nach Neustart, keine dauerhafte Hintergrund-Standortberechtigung. Der Nutzer startet den sichtbaren Vordergrunddienst selbst und kann ihn jederzeit beenden. Eine Sitzung ist auf drei Stunden begrenzt. Ohne Standortberechtigung kann eine manuell gewählte Fahrt mit Zeitdaten weiterlaufen.
- Bei einem vom System beendeten Dienst kann die letzte Fahrt innerhalb von drei Stunden manuell fortgesetzt werden. Ein Wegwischen der Benachrichtigung beendet die Sitzung; sie wird nicht ungefragt erneut eingeblendet.

Mitfahrt ist keine offizielle App von NEW oder VRR.

## Datenschutz

Die App speichert die aktive und letzte echte Fahrt lokal. Beim Beenden wird die aktive Sitzung gelöscht; die letzte Fahrt bleibt für einen späteren Bericht erhalten und wird bei der nächsten Fahrt ersetzt. Selbst bestätigte Ankünfte enthalten nur gewählte Haltestelle und Gerätezeit, keine GPS-Koordinaten. Diese Einträge sowie erstellte PDFs und ihre unveränderlichen Datenstände bleiben im privaten App-Speicher, bis die App-Daten gelöscht oder die App deinstalliert wird. Beim Teilen wird nur die ausgewählte PDF-Datei über einen nicht öffentlichen Android FileProvider freigegeben. Standortkoordinaten werden für die Haltestellensuche an den VRR übertragen. WLAN-Namen und GPS-Verlauf werden nicht hochgeladen oder dauerhaft gespeichert. Keine Analyse-SDKs oder Werbung. Kein Android-Cloud-Backup der Fahrtdaten und Berichte.

## Entwickeln

Voraussetzungen: JDK 17 oder 21, Android SDK mit Plattform 36 und Build Tools 36.0.0. `local.properties` bei Bedarf mit `sdk.dir=/pfad/zum/android-sdk` anlegen.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Die Debug-APK liegt in `app/build/outputs/apk/debug/app-debug.apk`. `./gradlew :app:assembleRelease` erstellt eine verkleinerte Vorschau-APK mit Debug-Signatur in `app/build/outputs/apk/release/`. Eine private Release-Signierung und Play-Store-Veröffentlichung sind nicht eingerichtet.

Die Unit-Tests verwenden echte öffentliche VRR-Antworten vom 06.11.2026 als Fixtures und prüfen Zuordnung, Ausfälle, Zeitzonen, Offlinezustand, Zeitberechnung und Wiederherstellung. Weitere Tests prüfen Berichts-Snapshots, originale Abrufzeiten, fehlende Prognosen, Abfahrt versus Ankunft und den Ausschluss von Demofahrten und hochgerechneten Zeiten als Belegdaten. WLAN und Now Bar benötigen zusätzlich einen Test während einer realen Busfahrt auf dem Zielgerät.

Prüfstand Version 0.3: Release-Build, 39 Unit-Tests und Android Lint ohne Fehler bestanden. VRR-Endpunkte wurden mit echten NEW-Abfahrten und Fahrtverläufen geprüft. Ein Bildschirm- und Installationstest im Android-36-Emulator konnte in der Build-Umgebung nicht abgeschlossen werden: Ohne Hardwarebeschleunigung wurde der Emulator während des ersten Starts wegen der Ressourcenbegrenzung beendet. Die App einschließlich PDF-Rendering und Android-Teilen ist auf einem realen Gerät noch nicht getestet.

## Quellen

- [Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)
- [Android WLAN-Scans und Drosselung](https://developer.android.com/develop/connectivity/wifi/wifi-scan)
- [Samsung Now Bar](https://www.samsung.com/us/support/answer/ANS10004605/)
- [EFA-Schnittstellenreferenz](https://efa.ips.codes/)
