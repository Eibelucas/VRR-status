# Mitfahrt auf der Uhr

Für die Galaxy Watch 5 Pro und andere Wear-OS-Uhren ab Wear OS 3. Auf dem gekoppelten Android-Handy muss Mitfahrt ab Version 0.7 installiert sein. Es gibt kein zusätzliches Konto, keinen Einladungscode und keine gemeinsamen Fahrtdaten mit anderen Nutzern.

## Im Alltag

1. Am Handy in Mitfahrt eine Haltestelle und deinen Bus auswählen.
2. Auf der Uhr **Mitfahrt** öffnen. Linie und Countdown erscheinen automatisch.
3. Nach dem Einsteigen am Handy **Bin im Bus** antippen. Die Uhr zeigt nächsten Halt und Minuten bis zum Endhalt.

**Aktualisieren** fragt den Handy-Status erneut an. Bei fehlender Verbindung bleibt der letzte Stand sichtbar und wird nach spätestens zwei Minuten als veraltet markiert. Eine pausierte Handy-Fahrt wird auch auf der Uhr als pausiert angezeigt. Der Countdown bestätigt keine tatsächliche Ankunft. Die Uhr wählt in dieser ersten Version keine eigene Fahrt; Haltestellensuche, Karte und Schulberichte bleiben in der Handy-App.

## Einmal installieren

Die App ist noch nicht im Play Store. Auf dem Handy lässt sich die normale APK direkt installieren. Die Watch-APK muss einmal auf die Uhr übertragen werden; Android erlaubt der Handy-App keine automatische Erstinstallation auf einer Uhr, auf der Mitfahrt noch fehlt.

Die beiden APKs stehen öffentlich unter [Releases](https://github.com/Eibelucas/VRR-status/releases/latest):

- **Mitfahrt-0.8.0.apk** ist für Android-Handys.
- **Mitfahrt-Wear-0.8.0.apk** ist ausschließlich für die Uhr.

Die Watch-APK kannst du mit einem Wear-OS-APK-Installer deiner Wahl oder am Computer mit den offiziellen [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) übertragen. Für ADB über WLAN:

1. Auf der Uhr: **Einstellungen → Info zur Uhr → Softwareinformationen**. Mehrfach auf **Softwareversion** tippen, bis der Entwicklermodus aktiv ist.
2. Unter **Entwickleroptionen** ADB-Debugging und drahtloses Debugging einschalten. Uhr und Computer mit demselben WLAN verbinden.
3. Auf der Uhr **Neues Gerät koppeln** öffnen. Die dortige IP-Adresse, den Kopplungsport und den angezeigten Code für `adb pair` verwenden. Der Port für `adb connect` steht auf der vorherigen Debugging-Seite und kann anders sein.

```sh
adb pair UHR_IP:KOPPLUNGSPORT
adb connect UHR_IP:VERBINDUNGSPORT
adb -s UHR_IP:VERBINDUNGSPORT install -r Mitfahrt-Wear-0.8.0.apk
```

Danach Debugging wieder ausschalten und Mitfahrt aus der App-Liste der Uhr öffnen. Die alltägliche Nutzung benötigt kein Debugging. Für spätere Watch-Updates dieselbe APK-Variante mit `install -r` installieren. Die In-App-Updatefunktion der Handy-App aktualisiert zunächst nur die Handy-App.

## Für Klassenkameraden

Den öffentlichen [Download-Link](https://github.com/Eibelucas/VRR-status/releases/latest) teilen. Jeder installiert die Handy-App auf seinem Gerät, wählt seine eigene Haltestelle und Fahrt und kann eigene Ankünfte sowie Schulberichte lokal speichern. Eine Uhr ist optional. Now Bar setzt ein unterstütztes Samsung-Gerät voraus; die normale Fahrtansicht funktioniert auch auf anderen Android-Handys ab Android 10.

## Technik und Datenschutz

Die Uhr erhält nur den aktuellen Fahrtstatus einschließlich Linie, Ziel, nächstem Halt, Zeitprognosen und deren Abrufzeit. Keine Berichte, persönlichen Ankünfte, GPS-Koordinaten oder WLAN-Namen. Das Telefon sendet bei Zustandswechseln und während einer aktiven Fahrt spätestens alle 15 Sekunden. Die Uhr rechnet den Countdown lokal weiter, solange ihre Ansicht sichtbar ist.

Google Play Services verbindet die beiden Installationen über die [Wear OS Data Layer](https://developer.android.com/training/wearables/data/overview). Paketkennung und Signaturschlüssel müssen auf Handy und Uhr übereinstimmen; andere App-Installationen bei Klassenkameraden erhalten diese Daten nicht. Die Übertragung nutzt Bluetooth oder bei Bedarf Googles verschlüsselte Vermittlung über das Netz. Die zuletzt empfangene Momentaufnahme bleibt im privaten Uhr-Speicher. Es wird kein eigener Server betrieben.

Eine reale Kopplung mit der Galaxy Watch 5 Pro ist in der Entwicklungsumgebung nicht verfügbar. Installation, Runddisplay und Übertragung müssen auf der echten Uhr bestätigt werden.
