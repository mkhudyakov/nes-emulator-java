#!/usr/bin/env bash
# Build and run the NES emulator without Gradle, using only a JDK 21+.
# Usage: ./run.sh [path/to/rom.nes]
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/out"
mkdir -p "$OUT"
echo "Compiling..."
find "$DIR/src/main/java" -name '*.java' > "$OUT/sources.txt"
javac -d "$OUT" @"$OUT/sources.txt"
echo "Launching..."
java -cp "$OUT" com.nesemu.Main "$@"
