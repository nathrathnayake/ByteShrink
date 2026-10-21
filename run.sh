#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
./build.sh
exec java -jar build/byteshrink.jar "$@"
