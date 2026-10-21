#!/bin/sh
# Télécharge la bibliothèque sherpa-onnx (moteur de voix Supertonic) dans app/libs.
set -e
VERSION=1.13.8
DIR="$(dirname "$0")/../app/libs"
mkdir -p "$DIR"
curl -L -o "$DIR/sherpa-onnx-$VERSION.aar" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/sherpa-onnx-$VERSION.aar"
