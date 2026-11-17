#!/usr/bin/env bash
# Compiles dev.streetresmon.core (no Minecraft dependency) and runs CoreTests. Needs only a JDK (17+).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
javac -Xlint:all -d "$OUT/main" $(find src/main/java/dev/streetresmon/core -name '*.java')
javac -Xlint:all -cp "$OUT/main" -d "$OUT/test" src/coreTest/java/dev/streetresmon/core/CoreTests.java
java -cp "$OUT/main:$OUT/test" dev.streetresmon.core.CoreTests
