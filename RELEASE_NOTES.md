## Mitfahrt 0.15.0

- **Busposition beim Warten:** Native OpenStreetMap-Karte mit gemeldeter NEW-Fahrzeugposition, Einstiegshaltestelle und sichtbarem Datenstand. Zentrieren und Öffnen des aktuellen Punkts in Google Maps sind möglich; kein eigenes Google-Konto oder API-Schlüssel erforderlich.
- **Ehrliche Zuordnung:** Eine Abfahrt wird nur bei eindeutig passenden, aktuellen Fahrtdaten automatisch mit einem Fahrzeug verbunden. Fehlen diese Angaben, lassen sich aktuelle Linienfahrzeuge ausdrücklich auswählen. Die App kennzeichnet eine solche Auswahl als nicht bestätigte Abfahrtszuordnung.
- **Keine erfundene Position:** Offline-, veraltete, ungültige und vom Anbieter ausgeblendete Meldungen erscheinen nicht als aktuelle Position. GPS-Meldung, Routenabgleich und VRR-Ankunftsprognose behalten ihre eigenen Zeitstempel.
- **Live-Zeiten bevorzugt:** Ohne frische echte Prognose gibt es keinen Warte-Countdown und keinen Ankunftsalarm allein nach Fahrplan. Stattdessen steht **Keine Live-Zeit**; die absolute Fahrplanzeit bleibt zum Nachsehen sichtbar. Das gilt auch für Now Bar und Watch.
- **Abfahrten zunächst ansehen:** Ein Tipp auf eine Abfahrt öffnet eine Vorschau. Erst **Fahrt verfolgen** startet und speichert die aktive Fahrt. Explizite Verfolgen-Schaltflächen bei eigenen Routen starten weiterhin direkt.
- **Vorschläge nach Interesse:** Favoriten- und Streckenvorschläge entstehen erst nach drei bewussten Aufrufen. Aktualisieren und Hintergrundabfragen zählen nicht erneut. Favoriten werden weiterhin nur per Stern selbst gesetzt. Bei ausgeschaltetem automatischem Merken entstehen keine neuen Vorschläge.
- **Eigene Favoriten bleiben:** Bestehende manuelle Sterne werden erhalten; ältere einmalig erzeugte automatische Vorschläge werden verborgen. Vor der dritten Ansicht werden nur begrenzte lokale Hash-Zähler statt Haltestellen- oder Abfahrtsdetails gespeichert.

Für Mönchengladbacher Fahrzeuge liefert NEW häufig keine eindeutige Kennung der einzelnen VRR-Abfahrt. Eine manuelle Fahrzeugauswahl zeigt deshalb die tatsächliche Position dieses Linienfahrzeugs, bestätigt aber nicht dessen Zugehörigkeit zur gewählten Abfahrt. Positionen werden nicht in eine erfundene Ankunftsprognose umgerechnet. Schulberichte verwenden weiterhin die ausgewiesenen VRR-Quellen.

**Handy und Watch aktualisieren:** `Mitfahrt-0.15.0.apk` und `Mitfahrt-Wear-0.15.0.apk`. Handy-Update unter **Einstellungen → App-Updates**. Watch-App für die angepasste Anzeige fehlender Live-Zeiten ebenfalls aktualisieren. [Watch-Anleitung](https://github.com/Eibelucas/VRR-status/blob/main/WATCH.md).
