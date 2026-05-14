# libpostal gRPC sidecar — FULLY OFFLINE build.
#
# Model data is pre-downloaded and split into chunks in model-data/.
# No internet access needed for model data during docker build.
#
# Build:
#   docker build -t tfpm-libpostal-sidecar .
#
# Run:
#   docker run -p 50051:50051 tfpm-libpostal-sidecar

# --- Stage 1: Build libpostal C library from source ---
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
    ldconfig

# --- Stage 2: Reassemble model data from repo chunks (OFFLINE) ---
FROM python:3.12-slim AS model

WORKDIR /tmp
COPY model-data/libpostal-data.tar.gz.part-* ./
COPY model-data/SHA256SUMS ./

# Verify integrity then reassemble — no downloads
RUN sha256sum -c SHA256SUMS && \
    cat libpostal-data.tar.gz.part-* > libpostal-data.tar.gz && \
    tar xzf libpostal-data.tar.gz -C /opt && \
    rm -f libpostal-data.tar.gz libpostal-data.tar.gz.part-*

# --- Stage 3: Runtime ---
FROM python:3.12-slim

RUN apt-get update && apt-get install -y libsnappy-dev && rm -rf /var/lib/apt/lists/*

# Copy libpostal shared library from builder
COPY --from=builder /usr/local/lib/libpostal* /usr/local/lib/
COPY --from=builder /usr/local/include/libpostal/ /usr/local/include/libpostal/
RUN ldconfig

# Copy pre-downloaded model data (NO DOWNLOAD at build time)
COPY --from=model /opt/libpostal_data/ /opt/libpostal_data/

# Python deps
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Sidecar code
COPY server.py structurer_pb2.py structurer_pb2_grpc.py ./

EXPOSE 50051

HEALTHCHECK --interval=10s --timeout=5s --retries=3 \
    CMD python -c "import grpc; ch=grpc.insecure_channel('localhost:50051'); grpc.channel_ready_future(ch).result(timeout=3)" || exit 1

ENTRYPOINT ["python", "server.py"]
CMD ["--port", "50051", "--workers", "10"]
