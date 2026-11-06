# Mitfahrt

Projekt: [Eibelucas/VRR-status](https://github.com/Eibelucas/VRR-status)

[APKs herunterladen](https://github.com/Eibelucas/VRR-status/releases/latest) · [Watch-Anleitung](WATCH.md) · [Build-Prüfungen](https://github.com/Eibelucas/VRR-status/actions)

Eine native Android-App für die NEW-Busse in Mönchengladbach. Kotlin, Jetpack Compose und Material 3. Kein Konto und kein Backend nötig.

## Verwenden

1. APK auf dem Android-Handy installieren und die kurze Einführung durchgehen oder überspringen.
2. Auf der Startseite eine nahe Haltestelle, eine passende eigene Strecke oder **Haltestelle suchen** wählen.
3. Passenden Bus antippen. Nach dem Einsteigen **Bin im Bus** wählen; **Fahrt beenden** stoppt den Hintergrunddienst.

Nach Auswahl eines Busses zeigt die Warteansicht den Countdown in Minuten und Sekunden. Bei einer echten VRR-Ankunftsprognose steht dort **Dein Bus kommt in**, andernfalls ausdrücklich **Bis zur Abfahrt**. Nach Ablauf wird **Wird erwartet** angezeigt; der Timer behauptet keine tatsächliche Ankunft. **Bin im Bus** schaltet zur Fahrtenansicht um. Abfahrtslisten werden nur während der sichtbaren Haltestellenansicht alle 30 Sekunden aktualisiert.

Alternativ **Erkennung starten**: Mitfahrt sucht nach `NEW-Hotspot` und schlägt NEW-Fahrten an nahe gelegenen Haltestellen vor. Die konkrete Fahrt muss bestätigt werden. Ein sichtbarer Hotspot beweist weder, dass man im Bus sitzt, noch welche Linie fährt. Die App verbindet sich nicht selbst mit einem WLAN.

Unter **Einstellungen → Demofahrt ansehen** lassen sich ausdrücklich gekennzeichnete Beispieldaten ausprobieren.

## Startseite und Einführung

Version 0.8 zeigt einmalig eine Einführung mit drei kurzen Schritten: Funktionen, Standort für nahe Haltestellen und Benachrichtigungen für den Fahrtstatus. Berechtigungen werden erst nach dem jeweiligen Antippen über Android angefragt. Standort und Benachrichtigungen sind optional. Ohne Standort bleibt die manuelle Suche verfügbar; eine dauerhaft abgelehnte Freigabe kann über die App-Einstellungen erneut eingerichtet werden. **Einstellungen → Einführung ansehen** öffnet die Einführung später erneut, ohne Fahrtdaten zu löschen.

Bei freigegebenem Standort lädt die Startseite automatisch bis zu zwölf Haltestellen im Umkreis von 700 m, nach Entfernung sortiert. Die ersten vier erscheinen direkt, **Alle Haltestellen** zeigt die vollständige Liste. Für diese Suche läuft ein eigener Standortempfänger nur während der sichtbaren Start- oder Suchseite; eine aktive Buserkennung oder ein Hintergrunddienst sind nicht nötig. Beim Verlassen werden dessen Standortempfänger entfernt und keine weiteren Abfragen gestartet; noch eintreffende Ergebnisse werden verworfen. Eine gesondert gestartete Busfahrt kann wie bisher ihren sichtbaren Vordergrunddienst weiterführen. Alte oder sehr ungenaue Standortdaten werden nicht für neue Vorschläge verwendet; ein ungefährer Standort ist als solcher gekennzeichnet.

Unter **Deine Strecken** erscheinen häufig ausgewählte Linien und Richtungen, die aktuell an den nahen Haltestellen fahren. Die App prüft dafür echte NEW-Abfahrten mit passender Linienkennung und Richtung. Der Einstieg muss nicht derselbe wie bei der vergangenen Auswahl sein. Pro Strecke wird eine bevorstehende, aktuelle Abfahrt an der nächstgelegenen passenden Haltestelle angezeigt. Vergangene Abfahrten, Ausfälle, veraltete Prognosen und die Gegenrichtung werden nicht als Vorschlag angeboten. Die Abfahrten werden auf der sichtbaren Startseite alle 30 Sekunden aktualisiert; Anfragen für mehrere Haltestellen laufen begrenzt parallel. Fehler an einzelnen Haltestellen lassen die anderen Ergebnisse sichtbar.

**Alle** öffnet den gesamten Streckenverlauf. Von dort wird der bisherige Einstieg mit frisch geladenen Abfahrten der gewählten Linie und Richtung geöffnet; es wird niemals eine alte Fahrt mit ihren vergangenen Zeiten neu gestartet. Ohne Standort sind die häufigsten Strecken direkt erreichbar. Beim ersten Upgrade kann die zuletzt gespeicherte echte Fahrt als einzelner Eintrag übernommen werden. Weitere Auswahlen werden ab jetzt gelernt. Erneutes Aktualisieren oder Fortsetzen derselben Fahrt erhöht ihre Häufigkeit nicht; Demo und ausgefallene Fahrten werden nicht gezählt.

Der Streckenverlauf enthält maximal 200 gewählte Fahrten mit Linie, Richtung, Haltestellenkennung, Haltestellenname und Auswahlzeitpunkt. Für Vorschläge zählen die letzten 90 Tage. Es werden keine Gerätekoordinaten oder WLAN-Namen im Streckenverlauf gespeichert. **Alle → Streckenverlauf löschen** entfernt diesen Verlauf; Berichte und Ankünfte bleiben erhalten. Die Daten werden nicht mit Klassenkameraden oder der Watch-App geteilt und sind vom Android-Backup ausgeschlossen.

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

## Wear OS und Teilen

Ab Version 0.7 gibt es eine Begleit-App für die Galaxy Watch 5 Pro und andere Wear-OS-Uhren ab Wear OS 3. Sie übernimmt Linie, Richtung, Countdown, nächsten Halt, Restzeit und Datenstand vom gekoppelten Android-Handy. Jede Uhr erhält nur den Status ihres eigenen Handys. Die Fahrt wird auf dem Handy ausgewählt; die Uhr benötigt weder GPS- noch WLAN-Berechtigungen. Nach mehr als zwei Minuten ohne aktuelle Zeitdaten oder Übertragung sowie bei pausierter Fahrt bleibt der alte Stand ausdrücklich markiert.

[Installation und Verwendung auf der Uhr](WATCH.md). Die Veröffentlichung enthält getrennte APKs für Handy und Uhr mit identischer Paketkennung, Version und Signatur. Die Erstinstallation der Uhr-App und deren Updates erfolgen zunächst über einen Wear-OS-APK-Installer oder ADB; die Handy-App installiert keine Uhr-App automatisch. Ein Play-Store-Eintrag ist noch nicht vorhanden. Die bestehende In-App-Updatefunktion lädt ausschließlich die Handy-APK und lehnt Watch-APKs ab.

Unter **Einstellungen → App teilen** wird der öffentliche Download-Link über den normalen Android-Teilen-Dialog angeboten. Es werden keine Nachrichten ungefragt versendet. Jeder Klassenkamerad kann die App ohne Einladung oder eigenes App-Konto verwenden. Ankünfte, Berichte und gewählte Fahrten bleiben getrennt auf den jeweiligen Geräten.

Die Synchronisierung verwendet die Wear OS Data Layer von Google Play Services. Sie überträgt nur den aktuellen Fahrtstatus und Zeitstempel, keine Berichte, selbst erfassten Ankünfte, Standortkoordinaten oder WLAN-Namen. Google kann die verschlüsselte Übertragung über das Netz vermitteln, wenn keine Bluetooth-Verbindung besteht. Die Uhr speichert die letzte Momentaufnahme lokal ohne Android-Backup. Der gemeinsame, versionierte Binär-Codec begrenzt Payloads und lehnt unvollständige oder unbekannte Versionen ab.

## NEW-Livekarte

**Fahrt → Busse auf der Karte** öffnet `https://livebus.new.de/` in einem eigenen Bildschirm innerhalb der App. Das funktioniert mit und ohne aktive Fahrt. Die offizielle NEW-Webseite liefert Fahrzeugpositionen, Linienauswahl und Kartenbedienung. Die App greift nicht in deren Darstellung ein und ordnet Marker nicht ungeprüft einer EFA-Fahrt zu. Liniennummer allein würde bei mehreren Bussen derselben Linie keine eindeutige Zuordnung erlauben. Eine Markierung auf der Karte ist kein Beleg einer persönlichen Mitfahrt oder Ankunft und fließt nicht in die Schulberichte ein.

Die Webansicht lädt nur bei geöffneter Karte und sichtbarer App; beim Schließen oder Verlassen wird sie zerstört. Sie erlaubt JavaScript und den für die Website nötigen lokalen Webspeicher, aber keine Datei- oder Inhaltszugriffe, keine Standortfreigabe und keine JavaScript-Brücke zu App-Daten. Hauptnavigation bleibt auf dem HTTPS-Ursprung `livebus.new.de`; vom Nutzer angetippte externe Weblinks werden im Browser geöffnet. Unsichere TLS-Verbindungen werden abgebrochen. Bei Ladefehlern gibt es **Erneut versuchen** und **Im Browser öffnen**.

Die Karte ist ein eingebettetes Angebot von NEW mit eigenen Datenlieferanten und Verfügbarkeitsgrenzen. Die unter [NEW Fahrplan](https://www.new.de/oepnv/fahrplan) verlinkte Livekarte und deren öffentliche Fahrzeugdaten wurden beim Einbau geprüft. Ein Android-WebView-Test auf dem Zielgerät steht noch aus.

## Now Bar

Unter Android 16 verwendet die App eine `Notification.ProgressStyle` mit Fortschritt, Liniennummer, nächstem Halt, Restzeit und einer Beenden-Aktion. Die Promotion wird über die öffentliche Android-Extra `android.requestPromotedOngoing` angefragt und die Berechtigung `POST_PROMOTED_NOTIFICATIONS` deklariert. Die Promotion wird nur für eine vom Nutzer gestartete Fahrt angefragt, nicht für die allgemeine WLAN-Suche.

Version 0.5 öffnet die tatsächliche Android-Aktion `android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS`. Die Benachrichtigung wird mit `hasPromotableCharacteristics()` geprüft: Frühe Android-16-Implementierungen benötigten eine angefragte Einfärbung, während neuere Implementierungen den unveränderten Standardaufbau bevorzugen. Die App behält jeweils nur die vom laufenden Framework als geeignet bewertete Variante bei. Während des Wartens verwendet sie zusätzlich den System-Countdown.

Auf Samsung gibt es unter **Einstellungen → Auf dem Sperrbildschirm** einen abschaltbaren experimentellen Kompatibilitätsmodus. Nur bei tatsächlich freigegebenen Android-Live-Benachrichtigungen ergänzt er `android.ongoingActivityNoti.automation` und die eigene Paketkennung. Dieser Modus aus Version 0.5 wurde vom Nutzer auf dem Galaxy A56 / One UI 9 bestätigt, dort jedoch mit dem unerwünschten Samsung-Titel „Gemini Intelligence“.

Version 0.6 verwendet ab Android 17 / One UI 9 standardmäßig zusätzlich die Samsung-Kartenfelder `style=1`, `primaryInfo`, `nowbarPrimaryInfo`, `secondaryInfo`, Chiptext, eigene Bussymbole und Fortschritt. Sie enthalten „Mitfahrt“, die gewählte Linie und den aktuellen Fahrtstatus. Die Option **Eigenen Titel verwenden** schaltet sofort zum bisherigen Modus zurück. Auf Android 16 wird dieser Kartenweg nicht verwendet, weil er dort die funktionierende Promotion verhindern kann. Ob die neue Karte den systemseitigen Gemini-Titel auf dem A56 ersetzt, bleibt auf dem Gerät zu prüfen.

Es werden keine privaten Framework-Methoden aufgerufen, keine fremde Paketkennung verwendet und keine Systemeinstellungen automatisch verändert. Die zusätzlichen Felder sind kein offizieller Samsung-API-Vertrag; Firmware-Änderungen können ihre Wirkung ändern. Grundlage ist das öffentliche [Interoperabilitätstestprojekt](https://github.com/tigerduck-app/android-dynamic-island-test).

Die Statusanzeige unterscheidet Freigabe, geeignete Benachrichtigung und das von Android gesetzte Flag `FLAG_PROMOTED_ONGOING`. Dieses Flag ist keine Bestätigung, dass Samsungs Oberfläche die Now Bar tatsächlich gerendert hat. Zum Prüfen einen Bus verfolgen, Live-Benachrichtigungen freigeben, die App verlassen und den Bildschirm sperren.

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

Die App speichert die aktive und letzte echte Fahrt lokal. Beim Beenden wird die aktive Sitzung gelöscht; die letzte Fahrt bleibt für einen späteren Bericht erhalten und wird bei der nächsten Fahrt ersetzt. Selbst bestätigte Ankünfte enthalten nur gewählte Haltestelle und Gerätezeit, keine GPS-Koordinaten. Diese Einträge sowie erstellte PDFs und ihre unveränderlichen Datenstände bleiben im privaten App-Speicher, bis die App-Daten gelöscht oder die App deinstalliert wird. Beim Teilen wird nur die ausgewählte PDF-Datei über einen nicht öffentlichen Android FileProvider freigegeben. Standortkoordinaten werden für die Haltestellensuche an den VRR übertragen. WLAN-Namen und GPS-Verlauf werden nicht hochgeladen oder dauerhaft gespeichert. Keine Analyse-SDKs oder Werbung im nativen App-Code. Die optional geöffnete NEW-Livekarte verbindet sich selbst mit NEW und deren Karten-/Echtzeitdiensten, unter anderem der von NEW verwendeten Firebase-Datenbank. Dafür gelten zusätzlich die Datenschutzangaben der Webseite. Mitfahrt gibt ihr weder Standort noch WLAN-Daten, Ankünfte oder Berichte weiter. Kein Android-Cloud-Backup der Fahrtdaten und Berichte.

## App-Updates

Unter **Einstellungen → App-Updates → Nach Updates suchen** prüft die App die neueste öffentliche GitHub-Version. Eine neue APK wird erst nach Antippen heruntergeladen. Danach prüft Mitfahrt SHA-256, Paketkennung, Versionsnummer und den Signaturschlüssel gegenüber der installierten App. **Installieren** öffnet den Android-Installer; falls erforderlich führt die App vorher zur Android-Freigabe für Installationen aus Mitfahrt. Eine stille Installation findet nicht statt. Android-/Samsung-Sicherheitsprüfungen beim Installieren oder Aktualisieren werden vom System gesteuert und können von Mitfahrt nicht abgeschaltet werden. Die Paketkennung und derselbe Signaturschlüssel bleiben erhalten. Berichte und Ankünfte bleiben bei einem regulären Update erhalten.

Die Update-Quelle ist ausschließlich `https://github.com/Eibelucas/VRR-status/releases/latest/download/update.json`. Es werden keine GitHub-Zugangsdaten in der App gespeichert. Solange noch kein Release mit `update.json` veröffentlicht ist, zeigt die App das an.

## Automatischer Build und Releases

GitHub Actions prüft Pushes und Pull Requests mit Tests des gemeinsamen Übertragungsformats, Handy-Unit-Tests, Android Lint für beide Apps und Builds beider APKs. Ein Tag wie `v0.4.0` baut nach erfolgreichen Prüfungen eine signierte APK und veröffentlicht sie zusammen mit `update.json` und Prüfsumme. Tag und APK-Version müssen übereinstimmen; die `versionCode` muss für jedes Update steigen.

Für installierbare Folgeversionen muss immer derselbe private Signaturschlüssel verwendet werden. Der Release-Job benötigt das Repository-Secret `ANDROID_KEYSTORE_BASE64`. Für die bereits verteilte Vorschau gelten die Android-Debug-Standardwerte für Alias und Passwörter; bei einem eigenen Schlüssel können sie über `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS` und `ANDROID_KEY_PASSWORD` überschrieben werden. Die private Schlüsseldatei gehört niemals in Git oder einen öffentlichen Release. Ohne konfigurierten Schlüssel schlägt der Release-Job mit einem Hinweis fehl, statt eine anders signierte Update-APK zu veröffentlichen.

Alternativ kann der Workflow über **Run workflow → publish_prebuilt** eine bereits lokal signierte APK aus einem angegebenen Asset-Branch veröffentlichen. Dafür ist kein privater Schlüssel auf GitHub erforderlich. Vor dem Upload werden Zertifikat gegen `release-signing.sha256`, Paketkennung, Version und SHA-256 geprüft und das Update-Manifest neu erzeugt. Ist eine Watch-APK dabei, werden zusätzlich deren Geräteanforderung, Versionsgleichheit mit der Handy-App, Signatur und eigenes `watch-update.json` geprüft. Dieser Weg wird für die erste Veröffentlichung verwendet; automatische neue Signaturen für künftige Tags benötigen weiterhin das Secret.

Lokal kann ein vorhandener Schlüssel über `MITFAHRT_KEYSTORE`, `MITFAHRT_STORE_PASSWORD`, `MITFAHRT_KEY_ALIAS` und `MITFAHRT_KEY_PASSWORD` eingebunden werden. Ohne diese Variablen baut Gradle wie bisher eine Vorschau mit dem lokalen Debug-Schlüssel. Ein auf einem anderen Rechner neu erzeugter Debug-Schlüssel kann bestehende Installationen nicht aktualisieren.

```sh
./gradlew :app:assembleRelease
python3 scripts/create_update_manifest.py --apk app/build/outputs/apk/release/app-release.apk --tag v0.4.0 --output dist
git tag v0.4.0
git push origin main v0.4.0
```

## Lokal entwickeln

Voraussetzungen: JDK 21, Android SDK mit Plattform 36 und Build Tools 36.0.0. `local.properties` bei Bedarf mit `sdk.dir=/pfad/zum/android-sdk` anlegen.

```sh
./gradlew :shared:test :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :wear:assembleDebug :wear:lintDebug
```

Die Debug-APK liegt in `app/build/outputs/apk/debug/app-debug.apk`. `./gradlew :app:assembleRelease` erstellt eine verkleinerte Vorschau-APK mit Debug-Signatur in `app/build/outputs/apk/release/`. Eine private Release-Signierung und Play-Store-Veröffentlichung sind nicht eingerichtet.

Die Unit-Tests verwenden echte öffentliche VRR-Antworten vom 06.11.2026 als Fixtures und prüfen Zuordnung, Ausfälle, Zeitzonen, Offlinezustand, Zeitberechnung und Wiederherstellung. Weitere Tests prüfen Berichts-Snapshots, originale Abrufzeiten, fehlende Prognosen, Abfahrt versus Ankunft und den Ausschluss von Demofahrten und hochgerechneten Zeiten als Belegdaten. WLAN und Now Bar benötigen zusätzlich einen Test während einer realen Busfahrt auf dem Zielgerät.

Version 0.5 ergänzt Countdown- und Wartezustand-Tests sowie Robolectric-Tests gegen das tatsächliche Android-16-Framework. Sie prüfen unter anderem Live-Update-Eignung, Benachrichtigungsvorlage, Countdown, Einstellungsaktion und begrenzte Samsung-Zusätze. Robolectric benötigt dafür JDK 21 und lädt ein Android-Framework-Artefakt. Der Signaturschlüssel für installierbare Updates bleibt derselbe. VRR-Endpunkte wurden mit echten NEW-Abfahrten und Fahrtverläufen geprüft. Die Now Bar aus Version 0.5 ist auf dem A56 / One UI 9 bestätigt; die neue Titeldarstellung, die eingebettete Karte sowie Update-Installation und PDF-Ausgabe benötigen weiterhin einen Test auf einem echten Gerät. Version 0.6 ergänzt Tests für die Samsung-Kartenfelder und die Beschränkung der Webnavigation auf den offiziellen Ursprung.

## Quellen

- [Android Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)
- [Android WLAN-Scans und Drosselung](https://developer.android.com/develop/connectivity/wifi/wifi-scan)
- [Samsung Now Bar](https://www.samsung.com/us/support/answer/ANS10004605/)
- [EFA-Schnittstellenreferenz](https://efa.ips.codes/)

Version 0.7 ergänzt Tests des gemeinsamen Watch-Codecs, für beschädigte Payloads, das Altern von Prognose und Verbindung unabhängig voneinander, Fahrtende, Watch-Datenquelle und den Ausschluss der Watch-APK als Handy-Update. Beide Apps werden gebaut und statisch geprüft. Die tatsächliche Data-Layer-Verbindung und Bedienung auf der Galaxy Watch 5 Pro müssen auf dem Gerät bestätigt werden.

Version 0.8 prüft zusätzlich Streckenhäufigkeit ohne Doppelzählung, Zuordnung zur Richtung und zu anderen nahen Einstiegshaltestellen, Ausschluss alter Abfahrten und Positionen, Teilausfälle und begrenzte parallele Abfragen. Android-Framework-Tests prüfen die Standortfreigabe und das Entfernen der Empfänger beim Schließen ohne Start eines Hintergrunddienstes. Der erste Einrichtungsablauf sowie echte Standortwechsel müssen zusätzlich auf einem Gerät geprüft werden.

## Gestaltung

Ab 0.9 orientieren sich Beerenton `#B40055`, helle Flächen und Karten an [NEW](https://www.new.de/oepnv). Die Watch kombiniert den Beerenton mit dunklen OLED-Flächen und gut lesbaren hellen Akzenten. App-Symbole und Live-Benachrichtigungen verwenden dieselbe Farbfamilie. Eigenständiges Bussymbol, kein offizielles NEW-Logo; Mitfahrt bleibt inoffiziell.
