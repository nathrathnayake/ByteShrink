#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
./build.sh
mkdir -p build/test-classes
if command -v javac >/dev/null 2>&1; then
  find src/test/java -name '*.java' -print0 | xargs -0 javac --release 17 -encoding UTF-8 -cp build/classes -d build/test-classes
else
  find src/test/java -name '*.java' -print0 | xargs -0 java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -cp build/classes -d build/test-classes
fi
java -ea -cp build/classes:build/test-classes dev.nath.byteshrink.ArchiveServiceSmokeTest
