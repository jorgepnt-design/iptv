# PintoTV – IPTV-Player für Fire TV & Android TV

Eigener IPTV-Player im Stil von TiviMate, optimiert für die Fernbedienung (Fire TV Stick/Cube, NVIDIA Shield, Android-TV-Boxen).

## Funktionen
- **Playlists:** Xtream-Codes (Server, Benutzer, Passwort) und M3U-Links, mehrere Playlists
- **Kanalliste wie TiviMate:** Menü | Gruppen | Kanäle | Sendungsdetails, mit Logo, laufender Sendung und Fortschrittsbalken
- **TV-Guide (EPG):** Raster mit Zeitleiste, rote Jetzt-Linie, XMLTV (auch .gz), bei Xtream automatisch
- **Multi-View:** 2 oder 4 Sender gleichzeitig, der Ton folgt dem ausgewählten Fenster
- **Favoriten** (OK lang drücken), **Zuletzt gesehen**, **Suche**, Ziffern-Direktwahl
- Info-Leiste beim Umschalten, automatische Wiederverbindung bei Abbrüchen
- Stream-Format für Xtream umschaltbar (TS / HLS)
- **Einrichtung vom Handy:** Auf dem Playlist-Bildschirm steht ein QR-Code. Mit dem iPhone/Handy scannen, Xtream- oder M3U-Daten bequem eintippen, an den Fernseher senden
- Touch-Bedienung für Android-Handys und Tablets

## Installieren
Direkter Download der aktuellen APK:
**https://github.com/jorgepnt-design/iptv/raw/main/PintoTV.apk**

Fire TV / Shield: App **Downloader** installieren, diese Adresse eingeben, herunterladen, installieren.
Handy: Link im Browser öffnen und die APK installieren (Touch: Tippen = Kanalliste, Wischen hoch/runter = umschalten, lang drücken = Menü).

## Bedienung
| Taste | Vollbild | Kanalliste |
|---|---|---|
| OK | Kanalliste öffnen | Sender abspielen |
| OK lang | – | Favorit an/aus |
| ▲ / ▼ | Sender wechseln | blättern |
| ◀ | Gruppen öffnen | zu Gruppen → Menü |
| ▶ / Info | Info-Leiste | – |
| Menü (≡) | Menü öffnen | Menü an/aus |
| 0–9 | Direktwahl | – |
| Zurück | 2× = Beenden | schließen |

**TV-Guide:** Pfeile bewegen, OK spielt den Sender, Taste 0 springt auf „Jetzt“.
**Multi-View:** Pfeile wählen das Fenster (Ton folgt), OK = Sender wählen/wechseln/Vollbild, Menü = 2 oder 4 Fenster, CH+/CH− schaltet im Fenster um.

## Bauen
`./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
Bei jedem Push auf `main` baut GitHub Actions die APK automatisch (Reiter „Actions“ → Artefakt „PintoTV-apk“).

Die App ist nur ein Player und enthält keine Sender. Bitte nur legale Quellen verwenden.
