# libpostal gRPC sidecar — builds libpostal from source, downloads
# model data, and runs the Python gRPC server.
#
# Build:
#   docker build -t tfpm-libpostal-sidecar .
#
# Run:
#   docker run -p 50051:50051 tfpm-libpostal-sidecar
#
# NOTE: First build downloads ~2GB of model data. Subsequent builds
# use Docker layer cache. The final image is ~2.5GB.

FROM python:3.12-slim AS builder

# Install build dependencies for libpostal
RUN apt-get update && apt-get install -y \
    build-essential \
    curl \
    autoconf \
    automake \
    libtool \
    pkg-config \
    git \
    && rm -rf /var/lib/apt/lists/*

# Build libpostal from source
WORKDIR /tmp
RUN git clone --depth 1 https://github.com/openvenues/libpostal.git && \
    cd libpostal && \
    ./bootstrap.sh && \
    ./configure --datadir=/opt/libpostal_data && \
    make -j$(nproc) && \
    make install && \
    ldconfig

# Download libpostal model data (~2GB)
# This is the trained model that powers address parsing.
# Downloaded once and cached in the Docker layer.
RUN libpostal_data download all /opt/libpostal_data

# --- Runtime stage ---
FROM python:3.12-slim

# Install runtime dependencies only
RUN apt-get update && apt-get install -y \
    libsnappy-dev \
    && rm -rf /var/lib/apt/lists/*

# Copy libpostal shared library and data from builder
COPY --from=builder /usr/local/lib/libpostal* /usr/local/lib/
COPY --from=builder /usr/local/include/libpostal/ /usr/local/include/libpostal/
COPY --from=builder /opt/libpostal_data/ /opt/libpostal_data/
RUN ldconfig

# Install Python dependencies
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy sidecar code
COPY server.py structurer_pb2.py structurer_pb2_grpc.py ./

EXPOSE 50051

HEALTHCHECK --interval=10s --timeout=5s --retries=3 \
    CMD python -c "import grpc; ch=grpc.insecure_channel('localhost:50051'); grpc.channel_ready_future(ch).result(timeout=3)" || exit 1

ENTRYPOINT ["python", "server.py"]
CMD ["--port", "50051", "--workers", "10"]
