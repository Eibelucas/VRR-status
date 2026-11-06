# Mitfahrt

Projekt: [Eibelucas/VRR-status](https://github.com/Eibelucas/VRR-status)

[APK herunterladen](https://github.com/Eibelucas/VRR-status/releases/latest) · [Build-Prüfungen](https://github.com/Eibelucas/VRR-status/actions)

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

## App-Updates

Unter **Einstellungen → App-Updates → Nach Updates suchen** prüft die App die neueste öffentliche GitHub-Version. Eine neue APK wird erst nach Antippen heruntergeladen. Danach prüft Mitfahrt SHA-256, Paketkennung, Versionsnummer und den Signaturschlüssel gegenüber der installierten App. **Installieren** öffnet den Android-Installer; falls erforderlich führt die App vorher zur Android-Freigabe für Installationen aus Mitfahrt. Eine stille Installation findet nicht statt. Berichte und Ankünfte bleiben bei einem regulären Update erhalten.

Die Update-Quelle ist ausschließlich `https://github.com/Eibelucas/VRR-status/releases/latest/download/update.json`. Es werden keine GitHub-Zugangsdaten in der App gespeichert. Solange noch kein Release mit `update.json` veröffentlicht ist, zeigt die App das an.

## Automatischer Build und Releases

GitHub Actions prüft Pushes und Pull Requests mit Unit-Tests, Android Lint und einem APK-Build. Ein Tag wie `v0.4.0` baut nach erfolgreichen Prüfungen eine signierte APK und veröffentlicht sie zusammen mit `update.json` und Prüfsumme. Tag und APK-Version müssen übereinstimmen; die `versionCode` muss für jedes Update steigen.

Für installierbare Folgeversionen muss immer derselbe private Signaturschlüssel verwendet werden. Der Release-Job benötigt das Repository-Secret `ANDROID_KEYSTORE_BASE64`. Für die bereits verteilte Vorschau gelten die Android-Debug-Standardwerte für Alias und Passwörter; bei einem eigenen Schlüssel können sie über `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS` und `ANDROID_KEY_PASSWORD` überschrieben werden. Die private Schlüsseldatei gehört niemals in Git oder einen öffentlichen Release. Ohne konfigurierten Schlüssel schlägt der Release-Job mit einem Hinweis fehl, statt eine anders signierte Update-APK zu veröffentlichen.

Alternativ kann der Workflow über **Run workflow → publish_prebuilt** eine bereits lokal signierte APK aus einem angegebenen Asset-Branch veröffentlichen. Dafür ist kein privater Schlüssel auf GitHub erforderlich. Vor dem Upload werden Zertifikat gegen `release-signing.sha256`, Paketkennung, Version und SHA-256 geprüft und das Update-Manifest neu erzeugt. Dieser Weg wird für die erste Veröffentlichung verwendet; automatische neue Signaturen für künftige Tags benötigen weiterhin das Secret.

Lokal kann ein vorhandener Schlüssel über `MITFAHRT_KEYSTORE`, `MITFAHRT_STORE_PASSWORD`, `MITFAHRT_KEY_ALIAS` und `MITFAHRT_KEY_PASSWORD` eingebunden werden. Ohne diese Variablen baut Gradle wie bisher eine Vorschau mit dem lokalen Debug-Schlüssel. Ein auf einem anderen Rechner neu erzeugter Debug-Schlüssel kann bestehende Installationen nicht aktualisieren.

```sh
./gradlew :app:assembleRelease
python3 scripts/create_update_manifest.py --apk app/build/outputs/apk/release/app-release.apk --tag v0.4.0 --output dist
git tag v0.4.0
git push origin main v0.4.0
```

## Lokal entwickeln

Voraussetzungen: JDK 17 oder 21, Android SDK mit Plattform 36 und Build Tools 36.0.0. `local.properties` bei Bedarf mit `sdk.dir=/pfad/zum/android-sdk` anlegen.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Die Debug-APK liegt in `app/build/outputs/apk/debug/app-debug.apk`. `./gradlew :app:assembleRelease` erstellt eine verkleinerte Vorschau-APK mit Debug-Signatur in `app/build/outputs/apk/release/`. Eine private Release-Signierung und Play-Store-Veröffentlichung sind nicht eingerichtet.

Die Unit-Tests verwenden echte öffentliche VRR-Antworten vom 06.11.2026 als Fixtures und prüfen Zuordnung, Ausfälle, Zeitzonen, Offlinezustand, Zeitberechnung und Wiederherstellung. Weitere Tests prüfen Berichts-Snapshots, originale Abrufzeiten, fehlende Prognosen, Abfahrt versus Ankunft und den Ausschluss von Demofahrten und hochgerechneten Zeiten als Belegdaten. WLAN und Now Bar benötigen zusätzlich einen Test während einer realen Busfahrt auf dem Zielgerät.

Version 0.4: Release-Build, 53 Unit-Tests und Android Lint ohne Fehler bestanden. Davon prüfen 14 Tests Update-Metadaten, Download-Ziele, Paketkennungen, Versionen und Signaturen. APK-Prüfsumme und dieselbe Signatur wie Version 0.3 wurden zusätzlich geprüft. VRR-Endpunkte wurden mit echten NEW-Abfahrten und Fahrtverläufen geprüft. Ein Bildschirm- und Installationstest im Android-36-Emulator konnte in der Build-Umgebung nicht abgeschlossen werden: Ohne Hardwarebeschleunigung wurde der Emulator während des ersten Starts wegen der Ressourcenbegrenzung beendet. Die App einschließlich Update-Installation, PDF-Rendering und Android-Teilen ist auf einem realen Gerät noch nicht getestet.

## Quellen

- [Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)
- [Android WLAN-Scans und Drosselung](https://developer.android.com/develop/connectivity/wifi/wifi-scan)
- [Samsung Now Bar](https://www.samsung.com/us/support/answer/ANS10004605/)
- [EFA-Schnittstellenreferenz](https://efa.ips.codes/)
