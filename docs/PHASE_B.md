# Phase B – Schritt 1 (v1.4.0)

## Bugfix
`gradle clean build` scheiterte mit "test task did not discover any tests": Der Test-Runner lag in `src/test/java`, dem Standardordner
von Gradles `test`-Task (erwartet JUnit). Jetzt: `src/coreTest/java` + eigener Task `coreTest`, eingehängt in `check`.
`test` hat zusätzlich `failOnNoDiscoveredTests = false`.

## Neu
| Funktion | Details |
|---|---|
| Resmon: CPU pro Mod (geschätzt) | Tabs *Resources* (Client/Render-Thread), *Server* (Server-Thread, falls in dieser JVM), *Threads*. Spalten: CPU ms/s, Time %, CPU incl. ms/s, Memory (`n/a`). Sortierbar per Klick auf den Spaltenkopf. Farben: grün/gelb/rot nach Anteil. |
| F5 / X | F5 schaltet Resmon um; X in der Titelleiste schließt (Konsole offen). Sampler laufen **nur**, solange Resmon oder Profiler offen/aktiv sind. |
| `profiler start [name]`, `stop`, `status`, `spikes`, `save [name]`, `list`, `load <file>`, `compare <a> <b>` | 20 Samples/s, schlechtester Frame je Sample (kein Wegmitteln von Spikes), Spike-Erkennung mit Hinweis `~client thread: modA 7/16`. Dateien: `<Spielordner>/streetresmon/profiler/*.srec`; I/O im Hintergrund-Thread. |
| `memory` | Heap, GC-Zähler (JVM), Trend nach GC (nur Hinweis), Sprünge ohne GC. |
| `crashlog` / `crashlog log` | Neuester Crash-Report bzw. Fehlergruppen in `latest.log` (letzte ~2 MB). Ursache immer "unconfirmed". |
| Menü | Launch: Profiler start/stop; Tools: Memory, Crash analyzer. |
| Core | Mod-Zuordnung per Package-Heuristik (Package-Segment == Mod-ID), 2 neue Tests. |

## Einschränkungen
* CPU pro Mod = **Schätzung** (Sampling). Nicht erfasst: GPU/Treiber/nativer Code. Mods, deren Package die Mod-ID nicht enthält, erscheinen als `(unattributed)`.
* Resmon-X und Spaltensortierung sind nur bei offener Konsole klickbar (HUD hat keinen Mauszeiger).
* **F5-Konflikt** mit Vanillas Kamera-Umschaltung.
* Profiler zeichnet nur auf dem Client auf (Dedicated-Server-Profiling folgt). Sampler/Memory-Hub laufen aber auch auf dem Dedicated Server.
* Dateipfade nutzen das JVM-Arbeitsverzeichnis (`StreetPaths`), nicht eine NeoForge-API.
* Noch offen aus der Prioritätenliste: Netgraph (Datenquelle), Event Monitor (Messpunkt), Startup, Live-Log-Viewer-UI, Pool, Input Viewer, Dashboard (F6), Diagramme.

## Teststatus
| Was | Status |
|---|---|
| Core-Tests (26) | ✅ hier ausgeführt, 26/26 |
| Neue Minecraft-seitige Dateien (`perf/*`, `command/DiagCommands`, `ResmonWindow`, `ClientEvents` ...) | ⚠️ nur gegen **selbst geschriebene Signatur-Stubs** typgeprüft (fehlerfrei), **nicht** gegen die echte 26.3-API kompiliert |
| `gradle clean build` mit dem Fix | ❌ von mir nicht ausführbar (kein Netz); bitte lokal ausführen |
| Spieltest (F5, Resmon-Werte, Profiler, Befehle) | ❌ nicht möglich |
