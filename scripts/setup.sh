#!/usr/bin/env bash
# Scarica whisper.cpp alla versione provata (una volta sola).
set -e
cd "$(dirname "$0")/.."
DEST=app/src/main/cpp/whisper.cpp
TAG=v1.7.6
if [ -f "$DEST/CMakeLists.txt" ]; then echo "whisper.cpp gia' presente"; exit 0; fi
git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/whisper.cpp "$DEST"
echo "Fatto."
