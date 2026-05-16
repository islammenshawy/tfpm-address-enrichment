#!/bin/bash
# Reassembles libpostal model data from split chunks.
# Works on macOS, Linux, and Git Bash on Windows.
#
# Usage:
#   ./reassemble.sh [output-dir]
#   Default output: ../model-data/

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${1:-$(dirname "$SCRIPT_DIR")/model-data}"

echo "Reassembling libpostal model from chunks..."
echo "Source: $SCRIPT_DIR"
echo "Output: $OUTPUT_DIR"

# Concatenate chunks
cat "$SCRIPT_DIR"/model-part-* > /tmp/libpostal-model.tar.gz
echo "Concatenated $(ls "$SCRIPT_DIR"/model-part-* | wc -l) chunks"

# Extract
mkdir -p "$OUTPUT_DIR"
tar xzf /tmp/libpostal-model.tar.gz -C "$OUTPUT_DIR"
rm /tmp/libpostal-model.tar.gz

echo "Model data extracted to: $OUTPUT_DIR"
du -sh "$OUTPUT_DIR"
