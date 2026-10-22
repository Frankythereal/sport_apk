# EnduroFit

Eigene Android-Fitness-App für Hard Enduro und Gym.

**Funktionen**
- **Suunto-Trainings als FIT-Datei** importieren (in der Suunto-App „Teilen → EnduroFit“): Puls, Kalorien, Strecke
- **Karte** (OpenStreetMap) mit der Strecke nach Pulszone eingefärbt und nummerierten Extrempunkten
- **Essensfotos erkennen** mit Claude (eigener API-Schlüssel nötig), auch Nährwerttabellen
- **Blutsauerstoff** aus Health Connect oder von Hand
- Puls direkt von der **Polar Verity Sense** (Bluetooth), auch bei ausgeschaltetem Bildschirm
- Pulszonen, **Extrempunkte** (Abschnitte über 90 % HFmax) und härteste Minute je Training
- Markierungen während der Fahrt setzen (⚑), die später im Diagramm erscheinen
- **Gym**: Übungen, Sätze, Wiederholungen, Gewicht, mit „Letztes Mal“-Anzeige
- **Essen** eintragen und **Kalorienbilanz** (Grundumsatz + Training)
- **Suunto-Import** über Health Connect: Trainings, **Schritte** (Gehen zählt beim Verbrauch mit) und **Schlaf** mit Phasen
- **Trinken** protokollieren
- **Ziele** mit gesunden Standardwerten (Schritte, Wasser, Eiweiß, Schlaf, Kalorienbilanz, 150 aktive Minuten + 2× Kraft pro Woche)
- **Analyse** für Woche, Monat und 3 Monate: Kaloriendiagramm, Aufteilung des Verbrauchs, Aktivitäten, Ziele, Schlaf und Tagesliste
- Export als CSV (Trainings, Pulsdaten, Ernährung)

**Installation**
Unter *Releases → latest* die Datei `EnduroFit.apk` auf dem Handy herunterladen und öffnen.
Beim ersten Mal muss Chrome erlaubt werden, Apps zu installieren.

Jeder Push baut automatisch eine neue APK (GitHub Actions).
