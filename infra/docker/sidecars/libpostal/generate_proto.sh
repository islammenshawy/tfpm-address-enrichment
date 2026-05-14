#!/bin/bash
# Generate Python gRPC stubs from the shared proto definition.
# Run from the sidecar directory.
set -euo pipefail

PROTO_DIR="$(cd "$(dirname "$0")/../../../.." && pwd)/proto"
OUT_DIR="$(dirname "$0")"

python -m grpc_tools.protoc \
    -I"$PROTO_DIR" \
    --python_out="$OUT_DIR" \
    --grpc_python_out="$OUT_DIR" \
    "$PROTO_DIR/structurer.proto"

echo "Generated Python stubs in $OUT_DIR"
