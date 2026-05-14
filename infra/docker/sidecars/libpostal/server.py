"""
libpostal gRPC sidecar — speaks proto/structurer.proto v1.

Wraps the libpostal C library via the `postal` Python package.
Stateless, idempotent, thread-safe (libpostal is internally thread-safe).

Start:
    python server.py [--port 50051]

Health:
    grpcurl -plaintext localhost:50051 com.jpmc.tfpm.address.proto.v1.AddressStructurerService/Health
"""

import time
import logging
import argparse
from concurrent import futures

import grpc
from google.protobuf import empty_pb2

import structurer_pb2
import structurer_pb2_grpc

from postal.parser import parse_address

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
LOG = logging.getLogger("libpostal-sidecar")

# Map libpostal component labels to our AddressField enum names
LABEL_TO_FIELD = {
    "house_number": "BLDG_NB",
    "road": "STRT_NM",
    "city": "TWN_NM",
    "state": "CTRY_SUB_DVSN",
    "postcode": "PST_CD",
    "country": "CTRY",
    "house": "BLDG_NM",
}

SUPPORTED_FIELDS = list(LABEL_TO_FIELD.values())

VERSION = "libpostal-1.1.10"


class AddressStructurerServicer(structurer_pb2_grpc.AddressStructurerServiceServicer):

    def Structure(self, request, context):
        start = time.monotonic_ns()
        raw = request.raw_address
        country_hint = request.country_hint or None

        if not raw or not raw.strip():
            return structurer_pb2.StructureResponse(
                structurer_version=VERSION,
                internal_latency_nanos=time.monotonic_ns() - start,
            )

        try:
            # parse_address returns list of (value, label) tuples
            parsed = parse_address(raw, country=country_hint if country_hint else None)
        except Exception as e:
            LOG.warning("libpostal parse error: %s", e)
            return structurer_pb2.StructureResponse(
                structurer_version=VERSION,
                internal_latency_nanos=time.monotonic_ns() - start,
            )

        fields = {}
        for value, label in parsed:
            field_name = LABEL_TO_FIELD.get(label)
            if not field_name or not value.strip():
                continue
            if field_name in fields:
                # libpostal can return multiple values for the same label;
                # keep the first (highest confidence by convention)
                continue

            # libpostal doesn't return confidence scores natively.
            # We assign a fixed 0.85 baseline — the ConfidenceCalibrator
            # on the Java side adjusts per (country, field) from golden-set data.
            fields[field_name] = structurer_pb2.FieldOutput(
                value=value.strip(),
                confidence=0.85,
            )

        # Country hint override: if libpostal didn't extract CTRY but we have a hint,
        # inject it with lower confidence
        if "CTRY" not in fields and country_hint and len(country_hint) == 2:
            fields["CTRY"] = structurer_pb2.FieldOutput(
                value=country_hint.upper(),
                confidence=0.70,
            )

        elapsed = time.monotonic_ns() - start
        LOG.debug("Parsed '%s' -> %d fields in %.2fms",
                  raw[:50], len(fields), elapsed / 1e6)

        return structurer_pb2.StructureResponse(
            fields=fields,
            structurer_version=VERSION,
            internal_latency_nanos=elapsed,
        )

    def Health(self, request, context):
        return structurer_pb2.HealthResponse(
            status=structurer_pb2.HealthResponse.SERVING,
            detail="libpostal loaded and serving",
        )

    def Capabilities(self, request, context):
        return structurer_pb2.CapabilitiesResponse(
            supported_fields=SUPPORTED_FIELDS,
            supported_countries=[],  # global
            max_qps=0,  # no advertised limit
        )


def serve(port=50051, max_workers=10):
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=max_workers))
    structurer_pb2_grpc.add_AddressStructurerServiceServicer_to_server(
        AddressStructurerServicer(), server
    )
    server.add_insecure_port(f"[::]:{port}")
    server.start()
    LOG.info("libpostal sidecar listening on :%d (workers=%d)", port, max_workers)
    server.wait_for_termination()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="libpostal gRPC sidecar")
    parser.add_argument("--port", type=int, default=50051, help="gRPC listen port")
    parser.add_argument("--workers", type=int, default=10, help="thread pool size")
    args = parser.parse_args()
    serve(port=args.port, max_workers=args.workers)
