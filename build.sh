#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
rm -rf build
mkdir -p build/classes
if command -v javac >/dev/null 2>&1; then
  find src/main/java -name '*.java' -print0 | xargs -0 javac --release 17 -encoding UTF-8 -d build/classes
else
  find src/main/java -name '*.java' -print0 | xargs -0 java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -d build/classes
fi
if command -v jar >/dev/null 2>&1; then
  jar --create --file build/byteshrink.jar --main-class dev.nath.byteshrink.ArchiveMateApp -C build/classes .
else
  java -m jdk.jartool/sun.tools.jar.Main --create --file build/byteshrink.jar --main-class dev.nath.byteshrink.ArchiveMateApp -C build/classes .
fi
echo "Built build/byteshrink.jar"
