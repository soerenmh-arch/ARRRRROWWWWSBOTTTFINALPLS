# Arrows Bot

Lokale Android-Automatisierung für **Arrows – Puzzle Escape**. Das Projekt
enthält keine Cloud-Komponente und verwendet MediaProjection zur Aufnahme,
AccessibilityService für Gesten und OpenCV für die visuelle Analyse.

## Build und Installation

1. Den Ordner `arrows-bot` in Android Studio öffnen.
2. Beim ersten Öffnen den vorgeschlagenen Gradle Wrapper und Android SDK
   (Android API 35, Build Tools 35.x) installieren bzw. auswählen.
3. Gradle Sync abwarten und ein Android-Gerät mit Android 10 oder neuer
   auswählen (minSdk 29, targetSdk 35).
4. `Build > Make Project` und anschließend auf dem Gerät installieren.
5. In der App zuerst die Bildschirmaufnahme erlauben und danach unter den
   Android-Einstellungen **Arrows Bot Steuerung** aktivieren.
6. Das Spiel öffnen, den Debug-Modus prüfen und erst danach `START` drücken.

## Sicherheitsverhalten

- Ohne aktive MediaProjection oder Accessibility-Verbindung startet die
  Automatisierung nicht.
- Jede Analyse hat eine Confidence. Bei unbekanntem Bildschirm, unklarer
  Richtung, fehlender Sichtbarkeit oder nicht bestätigtem Tap pausiert der Bot.
- Nach jedem Tap wird ein neuer Frame analysiert; der nächste Tap wird erst
  nach erfolgreicher Zustandsprüfung ausgeführt.
- Werbung wird nur abgewartet. Es wird kein Werbeinhalt angeklickt.

## Architektur

`capture/` nimmt Frames lokal auf, `accessibility/` führt ausschließlich
freigegebene Gesten aus, `vision/` segmentiert Board und Pfeilköpfe,
`mapping/` verbindet mehrere Viewports und entfernt Dubletten, `solver/`
berechnet eine Abhängigkeitsreihenfolge, `state/` orchestriert die State
Machine, `ui/` zeigt Status und Diagnose-Overlay.

## Grenzen und Kalibrierung

Das Spiel kann seine Scrollweite je nach Gerät oder Level ändern. Die aktuelle
Karte führt deshalb die erwartete Verschiebung der Swipes mit und verlangt vor
jedem Tap eine erneute sichtbare Pfeilbestätigung. Wenn die Spieloberfläche
stark von der Referenz abweicht, pausiert der Bot zur manuellen Prüfung statt
blind zu klicken. Die Erkennungsparameter sind bewusst in `vision/` isoliert.