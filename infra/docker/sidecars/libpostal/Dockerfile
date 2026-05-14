# libpostal gRPC sidecar.
#
# Downloads model data from libpostal's GitHub data server during build.
# First build downloads ~2GB (cached in Docker layer for subsequent builds).
#
# Build:
#   docker build -t tfpm-libpostal-sidecar .
#
# Run:
#   docker run -p 50051:50051 tfpm-libpostal-sidecar

# --- Stage 1: Build libpostal + download model data ---
FROM python:3.12-slim AS builder

RUN apt-get update && apt-get install -y \
    build-essential curl autoconf automake libtool pkg-config git \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /tmp
RUN git clone --depth 1 https://github.com/openvenues/libpostal.git && \
    cd libpostal && \
    ./bootstrap.sh && \
    ./configure --datadir=/opt/libpostal_data && \
    make -j$(nproc) && \
    make install && \
    ldconfig && \
    libpostal_data download all /opt/libpostal_data

# --- Stage 2: Runtime ---
FROM python:3.12-slim

RUN apt-get update && apt-get install -y libsnappy-dev && rm -rf /var/lib/apt/lists/*

COPY --from=builder /usr/local/lib/libpostal* /usr/local/lib/
COPY --from=builder /usr/local/include/libpostal/ /usr/local/include/libpostal/
COPY --from=builder /opt/libpostal_data/ /opt/libpostal_data/
RUN ldconfig

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server.py structurer_pb2.py structurer_pb2_grpc.py ./

EXPOSE 50051

HEALTHCHECK --interval=10s --timeout=5s --retries=3 \
    CMD python -c "import grpc; ch=grpc.insecure_channel('localhost:50051'); grpc.channel_ready_future(ch).result(timeout=3)" || exit 1

ENTRYPOINT ["python", "server.py"]
CMD ["--port", "50051", "--workers", "10"]
