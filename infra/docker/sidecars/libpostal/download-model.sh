#!/bin/bash
# Downloads libpostal model data, compresses it, and splits into chunks
# for embedding in the repository. Run ONCE; results go into model-data/.
#
# Prerequisites: Docker (used to run libpostal_data inside a container)
#
# Output:
#   model-data/libpostal-data.tar.gz.part-aa
#   model-data/libpostal-data.tar.gz.part-ab
#   ...
#   model-data/SHA256SUMS
#
# The Dockerfile reassembles these during build.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODEL_DIR="$SCRIPT_DIR/model-data"
TEMP_DIR=$(mktemp -d)

echo "=== Downloading libpostal model data ==="
echo "This downloads ~2GB of data. First time only."
echo ""

# Use a temporary Docker container to download the data
docker run --rm \
    -v "$TEMP_DIR:/data" \
    python:3.12-slim \
    bash -c '
        apt-get update -qq && apt-get install -y -qq curl autoconf automake libtool pkg-config git build-essential > /dev/null 2>&1
        cd /tmp
        git clone --depth 1 https://github.com/openvenues/libpostal.git > /dev/null 2>&1
        cd libpostal
        ./bootstrap.sh > /dev/null 2>&1
        ./configure --datadir=/data/libpostal_data > /dev/null 2>&1
        make -j$(nproc) > /dev/null 2>&1
        make install > /dev/null 2>&1
        ldconfig
        echo "Downloading model data..."
        libpostal_data download all /data/libpostal_data
        echo "Download complete."
    '

echo ""
echo "=== Compressing model data ==="
cd "$TEMP_DIR"
tar czf libpostal-data.tar.gz -C /tmp libpostal_data 2>/dev/null || \
    tar czf libpostal-data.tar.gz libpostal_data

echo ""
echo "=== Splitting into 50MB chunks ==="
mkdir -p "$MODEL_DIR"
rm -f "$MODEL_DIR"/libpostal-data.tar.gz.part-*
split -b 50m libpostal-data.tar.gz "$MODEL_DIR/libpostal-data.tar.gz.part-"

echo ""
echo "=== Generating checksums ==="
cd "$MODEL_DIR"
shasum -a 256 libpostal-data.tar.gz.part-* > SHA256SUMS

echo ""
echo "=== Cleanup ==="
rm -rf "$TEMP_DIR"

echo ""
echo "Done! Model data split into chunks in $MODEL_DIR:"
ls -lh "$MODEL_DIR"
echo ""
echo "Total size:"
du -sh "$MODEL_DIR"
echo ""
echo "Add to git with: git add infra/docker/sidecars/libpostal/model-data/"
echo "Consider git-lfs for large files: git lfs track '*.part-*'"
