## Mitfahrt 0.6.0

- **Busse auf der Karte** öffnet die offizielle NEW-Livekarte direkt in Mitfahrt, auch ohne aktive Fahrt.
- Fahrzeugmarker und Linienauswahl kommen von `livebus.new.de`. Die Karte ordnet keinen Marker ungeprüft der gewählten VRR-Fahrt zu.
- Eigene Kartenfelder für die Now Bar auf Android 17 / One UI 9: „Mitfahrt“, Linie, Richtung und Fahrtstatus. Das soll den gemeldeten Gemini-Titel ersetzen.
- Unter **Einstellungen → Auf dem Sperrbildschirm → Eigenen Titel verwenden** lässt sich die neue Darstellung ausschalten, um zum bereits funktionierenden Modus zurückzukehren.
- Die Karte erhält keinen Zugriff auf Berichte, Ankünfte, WLAN oder den Gerätestandort. Beim Schließen oder Verlassen der App wird die Webansicht beendet.
- Zusätzliche Tests für Samsung-Titelfelder und die Navigation der eingebetteten Karte.

Die Now Bar aus Version 0.5 wurde auf dem Galaxy A56 / One UI 9 vom Nutzer bestätigt. Die neue Titeldarstellung muss auf diesem Gerät geprüft werden; Samsungs Felder sind keine offizielle API. Android-/Samsung-Sicherheitsprüfungen bei der APK-Installation werden vom System gesteuert und bleiben bestehen. Updates verwenden denselben Signaturschlüssel wie bisher.
