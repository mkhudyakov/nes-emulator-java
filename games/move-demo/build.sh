#!/usr/bin/env bash
# Build move-demo with the cc65 toolchain.  Produces build/move.nes
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build
python3 assets/gen_chr.py
ca65 -I src -g -o build/move.o src/move.s
ld65 -C nrom.cfg -o build/move.nes build/move.o
echo "built build/move.nes ($(wc -c < build/move.nes) bytes)"
