# OBD Monitor

Android-App für Echtzeit-Fahrzeugdiagnose über Bluetooth OBD2-Adapter.  
Auslesen verschiedener Daten und Langzeit-Datenanalyse.

---

## Voraussetzungen

- Android-Gerät (min. API 24 / Android 7.0)
- ELM327-kompatibler OBD2-Bluetooth-Adapter
- Fahrzeug mit OBD2-Schnittstelle
- Android Studio oder fertige APK

---

## Installation

1. Repository klonen oder APK aus `app/build/outputs/apk/debug/app-debug.apk` auf das Gerät übertragen.
2. In den Android-Einstellungen „Unbekannte Quellen" aktivieren.
3. App installieren und starten.

---

## Verbindung herstellen

1. OBD2-Adapter in die Diagnosebuchse des Fahrzeugs stecken.
2. Zündung einschalten.
3. Im Android-Bluetooth-Menü den Adapter koppeln (PIN ist meist `1234` oder `0000`).
4. App öffnen -> Seitenmenü -> Connect antippen -> Adapter auswählen.
5. Die Statuszeile zeigt nach erfolgreicher Verbindung „Connected".

---

## Menü

| Menüpunkt | Sensoren |
|---|---|
| Speed | Geschwindigkeit |
| Speed / RPM | Geschwindigkeit, Drehzahl |
| Temp / Pressure | Kühlwasser, Ladedruck, Ladelufttemperatur |
| Electrical | Batteriespannung, ECU-Spannung, Drosselklappe, Motorbetriebsdauer |
| Thermal Control | Kühlwasser, Ladelufttemperatur, Ansaugluft, Außentemperatur |
| Engine Control | Drehzahl, MAF, Ladedruck, Drosselklappe, Ist-Drehmoment, Fahrerwunsch-Drehmoment, Gaspedalstellung |
| Fuel Control | Kraftstoffdruck, -stand, Motorlast, Lambda, Kurzzeit-/Langzeit-Kraftstoffkorrektur, Soll-Lambda |
| Performance Dynamic | Kraftstoffverbrauch, Zündwinkel, O2, Einspritzzeitpunkt, Relative Drosselklappe |
| Turbo Monitoring | Ladedruck, Ladelufttemperatur, Barometerdruck, Ist-Drehmoment, Fahrerwunsch-Drehmoment |
| Coolant / Oil | Kühlwasser, Öltemperatur, O2 |
| Daten-Analyse | Drehzahl, Geschwindigkeit, Ladedruck, Drosselklappe, Motorlast, MAF, Lambda, Kühlwasser, Ansaugluft, Zündwinkel – Graphen |
| 0–100 Timer | Geschwindigkeit – Zeitmessung + Graph |

---

## Daten-Analyse 

Die Analyse-Ansicht zeichnet Zeitreihen aller motorsportrelevanten Sensoren auf und stellt sie als farbige Linien dar.

### Motorsport-Sensoren

- **Drehzahl (RPM)** – Motordrehzahl
- **Geschwindigkeit (km/h)** – Fahrzeuggeschwindigkeit
- **Ladedruck (bar)** – Turboladerdruck (PID 010B)
- **Gaspedalstellung (%)** – Throttle Position
- **Motorlast (%)** – Berechnete Motorlast
- **Luftmassenmesser (g/s)** – Mass Air Flow
- **Lambda (λ)** – Luft-Kraftstoff-Verhältnis
- **Kühlwassertemperatur (°C)** – Überhitzungsschutz
- **Ansauglufttemperatur (°C)** – Ladeluftkühler-Effizienz
- **Zündwinkel (°)** – Timing Advance / Klopfregelung

### Bedienung

1. Menü → **Daten-Analyse** öffnen.
2. **Intervall** wählen (0,2 s – 5 s): bestimmt, wie oft ein Datenpunkt gespeichert wird.
3. **„Aufnahme starten"** drücken – der Button wird rot und zeigt „Stop".
4. Fahren / Testlauf durchführen.
5. **„Stop"** drücken, um die Aufnahme zu beenden.
6. Im Chart können die Linien mit **Pinch-Zoom** und **Drag** analysiert werden.
7. Mit den **Checkboxen** am unteren Rand einzelne Sensoren ein-/ausblenden.
8. **„Clear"** löscht alle aufgezeichneten Daten.

---

## 0–100 km/h Timer

Misst exakt die Zeit vom ersten Anrollen bis zum Erreichen von 100 km/h. Ideal für Rennstrecken-Vergleiche.

### Anzeige

| Element | Bedeutung |
|---|---|
| Große Zahl (türkis) | Aktuelle / letzte gemessene Zeit in Sekunden |
| Status-Text | Zustand des Timers |
| Bestzeit (gold) | Schnellster Lauf seit App-Start |
| Geschwindigkeit | Aktuelle OBD-Geschwindigkeit |
| Graph | Geschwindigkeitsverlauf des letzten Laufs |
| Letzte Läufe | Bis zu 5 gespeicherte Zeiten |

### Bedienung

1. Menü → **0–100 Timer** öffnen.
2. **„BEREIT MACHEN"** drücken – App wartet auf Anfahrt.
3. Fahrzeug aus dem Stand anfahren – **Timer startet automatisch** sobald Geschwindigkeit > 1 km/h erkannt wird.
4. Bei **100 km/h** stoppt der Timer automatisch.
5. Ergebnis + Geschwindigkeitsgraph werden angezeigt.
6. **„NOCHMAL"** für den nächsten Lauf.
7. **„ABBRECHEN"** bricht eine laufende Messung ab.

### Hinweise zur Genauigkeit

- Die Genauigkeit hängt von der OBD-Polling-Rate ab (ca. 150 ms bei Speed-only).
- ELM327 v1.5 liefert bessere Latenzen als v2.x-Clone-Adapter.
- Für maximale Genauigkeit nur den Sprint-Timer aktiv haben (kein anderes Menü).
- Die Bestzeit wird nur für die aktuelle Sitzung gespeichert (kein persistenter Speicher).

---

## Technische Details

### Architektur

```
MainActivity
 ├── ObdManagerFast      – Polling-Thread (Bluetooth → OBD-Befehle)
 ├── DataLogger          – Thread-sicherer Zeitreihen-Puffer (max. 600 Punkte/Sensor)
 ├── AnalyticsController – Chart-Logik für Daten-Analyse-View
 └── SprintController    – Zustandsmaschine + Chart für 0–100-View
```

### OBD-PIDs

| Sensor | PID | Einheit |
|---|---|---|
| Geschwindigkeit | 010D | km/h |
| Drehzahl | 010C | RPM |
| Motorlast | 0104 | % |
| Luftmasse (MAF) | 0110 | g/s |
| Ist-Drehmoment | 0162 | % |
| Fahrerwunsch-Drehmoment | 0161 | % |
| Drosselklappenstellung | 0111 | % |
| Relative Drosselklappe | 0145 | % |
| Gaspedalstellung | 0149 | % |
| Ladedruck (MAP) | 010B | bar |
| Barometerdruck | 0133 | kPa |
| Kraftstoffdruck | 010A | kPa |
| Kühlwassertemperatur | 0105 | °C |
| Ladelufttemperatur | 010F | °C |
| Umgebungstemperatur | 0146 | °C |
| Öltemperatur | 015C | °C |
| Lambda (Breitband) | 0134 | λ |
| Soll-Lambda | 0144 | λ |
| Zündwinkel | 010E | ° |
| Einspritzzeitpunkt | 015D | ° |
| Kurzzeit-Kraftstoffkorrektur | 0106 | % |
| Langzeit-Kraftstoffkorrektur | 0107 | % |
| Kraftstoffstand | 012F | % |
| Kraftstoffverbrauch | 015E | L/h |
| O2-Sensor | 0114 | V |
| Batteriespannung (ELM327) | ATRV | V |
| ECU-Spannung | 0142 | V |
| Motorbetriebsdauer | 011F | s |

---

## Bekannte Einschränkungen

- Nicht alle Fahrzeuge unterstützen alle PIDs (z. B. fehlt Lambda bei Diesel-Fahrzeugen).
- Bei schlechter Bluetooth-Verbindung können Werte kurz ausfallen (Error-Toast erscheint).
- Die App speichert keine Daten dauerhaft – nach App-Neustart sind Aufnahmen gelöscht.