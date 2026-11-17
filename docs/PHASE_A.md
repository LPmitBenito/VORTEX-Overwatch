# Phase A – Bestandsaufnahme und Core-Logik

## 1. Bestand (v1.3.0, unverändert übernommen)
| Teil | Zustand |
|---|---|
| F8-Konsole (`ConsoleScreen`, `ConsoleLog`) | vorhanden, nie gegen echte 26.3-API kompiliert |
| Resmon-HUD (`ResmonWindow`) | vorhanden, nur über `resmon 1/0`, kein F5, kein X, keine Sortierung |
| `PerformanceMonitor` | FPS, Client-Tick, MSPT, JMX-CPU/Heap, Thread-CPU |
| Befehle | help, clear, resmon, fps, mods, version |
| Gradle-Wrapper | fehlt |

Keine bestehende Datei unter `client/`, `command/`, `perf/` wurde verändert. Neu: Paket `core/`, `src/test/`, `tools/`, `docs/`,
und ein zusätzlicher Task `coreTest` am Ende von `build.gradle`.

## 2. Neu in Phase A: `dev.streetresmon.core` (keinerlei Minecraft-Imports)
| Prio | Funktion | Klassen | Messart |
|---|---|---|---|
| 1 | Resmon CPU pro Mod | `resmon/StackSampler`, `ModSampleTable`, `owner/ModOwnerResolver` | **ESTIMATED** (Stack-Sampling) |
| 2 | Profiler + Lag-Spikes | `profiler/*` (Session, SpikeDetector, Recording, RecordingIO, RecordingComparer) | MEASURED (Eingaben) / Hint ESTIMATED |
| 3 | Netgraph | `net/NetStats` | zählt nur, was eine Quelle meldet |
| 4 | Event Monitor | `resmon/EventTimingTable` | exakt, aber nur für Code, den der Aufrufer umschließt |
| 5 | Memory | `memory/MemoryTrendDetector` | Trend = Hinweis, nie "Leak bestätigt" |
| 6 | Startup | `startup/StartupTracker` | gemeldete Phasen + JVM-Uptime |
| 7 | Crash Analyzer | `crash/CrashAnalyzer` | Heuristik, immer "Unconfirmed" |
| 8 | Live Log Viewer | `logs/*` (Tailer, Assembler, Buffer) | Datei-Offset, keine Duplikate |
| 9 | Pool Monitor | `pool/PoolRegistry` | nur Werte, die ein Supplier liefert |
| 10 | Input Viewer | `input/InputTracker` | gespeist von Spiel-Input-Events |
| – | Module/Settings | `module/*`, `settings/CoreSettings` | – |

## 3. Wichtige Einschränkungen (ehrlich)
* **Pro-Mod-CPU ist nicht exakt messbar.** Das Sampling zählt nur RUNNABLE-Samples, deren oberster Frame nicht nativ ist.
  Zeit im GPU-Treiber/nativem Code wird nicht zugeordnet. Sampling kostet den Ziel-Thread je Sample einen kurzen Safepoint (Default 25 ms).
* **Mod ↔ Package-Zuordnung:** `ModOwnerResolver` rät nie. Mod-Packages müssen registriert werden (Phase B, aus der Mod-Liste/Jars).
  Wie sich das mit den echten NeoForge-26.3-APIs sauber lösen lässt, ist **noch nicht geprüft**.
* **Event Monitor:** Wie man die Laufzeit einzelner NeoForge-Event-Listener ohne Eingriff in den Bus misst, ist offen (Phase B).
* **Netzwerk:** Die Datenquelle (Bytes/Pakete/Ping) wird in Phase B angebunden; bis dahin zeigt `NetStats` "n/a".
* **Memory:** Post-GC-Heap bei Young-Collections ist nur eine Näherung der Live-Menge.
* **Crash Analyzer:** Format echter NeoForge-26.3-Crashreports ist nicht gegen echte Dateien geprüft.

## 4. Teststatus
| Was | Status |
|---|---|
| `core/` kompiliert (JDK 21, `-Xlint:all`, 0 Warnungen) | ✅ ausgeführt |
| `CoreTests` (24 Tests) | ✅ ausgeführt, 24/24 bestanden (mehrfach wiederholt) |
| Kompilierung mit Java 25 | ❌ nicht ausgeführt (nur JDK 21 verfügbar; Code nutzt nichts ab Java 22) |
| Gradle-Task `coreTest` | ❌ nicht ausgeführt |
| `gradle clean build` / Minecraft-/NeoForge-Code / JAR | ❌ nicht möglich (kein Gradle, kein Netz, keine NeoForge-Jars) |
| Spieltest | ❌ nicht möglich |

Tests selbst ausführen: `tools/run-core-tests.sh` (nur JDK nötig).
