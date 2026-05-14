#!/bin/bash
# Downloads libpostal model data locally for non-Docker usage.
#
# Usage:
#   ./download-model.sh [target-dir]
#   Default target: ./model-data/
#
# This is NOT needed for Docker builds — the Dockerfile downloads
# the model data during docker build and caches it in the image layer.
#
# This script is for:
#   - Running the sidecar natively (without Docker)
#   - Pre-caching model data for offline Docker builds
#
# Prerequisites: Docker (uses a temporary container to build libpostal)

set -euo pipefail

TARGET_DIR="${1:-$(dirname "$0")/model-data}"
mkdir -p "$TARGET_DIR"

echo "=== Downloading libpostal model data to $TARGET_DIR ==="
echo "This builds libpostal in a temporary Docker container and"
echo "downloads ~2GB of model data. Takes 5-10 minutes."
echo ""

docker run --rm -v "$TARGET_DIR:/output" python:3.12-slim bash -c '
    set -e
    apt-get update -qq > /dev/null 2>&1
    apt-get install -y -qq build-essential curl autoconf automake libtool pkg-config git > /dev/null 2>&1
    cd /tmp
    echo "Building libpostal from source..."
    git clone --depth 1 https://github.com/openvenues/libpostal.git > /dev/null 2>&1
    cd libpostal
    ./bootstrap.sh > /dev/null 2>&1
    ./configure --datadir=/output > /dev/null 2>&1
    make -j$(nproc) > /dev/null 2>&1
    make install > /dev/null 2>&1
    ldconfig
    echo "Downloading model data..."
    libpostal_data download all /output
    echo "Done!"
'

echo ""
echo "Model data downloaded to: $TARGET_DIR"
du -sh "$TARGET_DIR"
