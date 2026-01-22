# Distributed Lock Manager

A distributed lock manager with Raft consensus and cross-region quorum support, built with Java 21, Spring Boot, and gRPC.

## Features

- Distributed locking with fencing tokens
- Raft consensus for leader election and log replication
- Cross-region quorum for global consistency
- gRPC API for client communication
- Virtual threads for high concurrency

## Building

### Prerequisites

- Java 21+
- Maven 3.8+
- Docker (for containerized deployment)

### Build from source

```bash
mvn clean install
```

### Run tests

```bash
mvn test
```

## Running with Docker

### Single Container (Development)

```bash
# Build the Docker image
docker build -t lockmgr .

# Run it
docker run -d --name lockmgr -p 9090:9090 -p 9091:9091 lockmgr

# View logs
docker logs -f lockmgr

# Stop and remove
docker stop lockmgr && docker rm lockmgr
```

### Multi-Region Setup (docker-compose)

The docker-compose configuration sets up 3 regions for testing distributed consensus:

| Region    | Client gRPC | Inter-region gRPC |
|-----------|-------------|-------------------|
| us-east-1 | 9090        | 9091              |
| us-west-2 | 9190        | 9191              |
| eu-west-1 | 9290        | 9291              |

```bash
# Start all regions
docker-compose up -d

# View logs for all regions
docker-compose logs -f

# Stop all regions
docker-compose down
```

## Testing the gRPC API

### Install grpcurl

```bash
# Ubuntu/Debian
sudo apt-get install -y grpcurl

# Or download directly
curl -sSL https://github.com/fullstorydev/grpcurl/releases/download/v1.8.9/grpcurl_1.8.9_linux_x86_64.tar.gz | tar xz
sudo mv grpcurl /usr/local/bin/
```

### List Available Services

```bash
grpcurl -plaintext localhost:9090 list
```

### Acquire a Lock

```bash
grpcurl -plaintext -d '{
  "lock_id": "550e8400-e29b-41d4-a716-446655440000",
  "client_id": "my-client-1",
  "timeout_ms": 30000
}' localhost:9090 com.nationsbenefits.grpc.LockService/AcquireLock
```

Expected response:
```json
{
  "success": true,
  "fencingToken": "1",
  "expiresAt": "1706000000000",
  "status": "LOCK_STATUS_OK"
}
```

### Check Lock Status

```bash
grpcurl -plaintext -d '{
  "lock_id": "550e8400-e29b-41d4-a716-446655440000"
}' localhost:9090 com.nationsbenefits.grpc.LockService/CheckLock
```

### Release a Lock

Use the `fencing_token` from the acquire response:

```bash
grpcurl -plaintext -d '{
  "lock_id": "550e8400-e29b-41d4-a716-446655440000",
  "client_id": "my-client-1",
  "fencing_token": 1
}' localhost:9090 com.nationsbenefits.grpc.LockService/ReleaseLock
```

### Test Lock Contention

Open two terminals and try to acquire the same lock:

**Terminal 1:**
```bash
grpcurl -plaintext -d '{
  "lock_id": "550e8400-e29b-41d4-a716-446655440000",
  "client_id": "client-A",
  "timeout_ms": 60000
}' localhost:9090 com.nationsbenefits.grpc.LockService/AcquireLock
```

**Terminal 2:**
```bash
grpcurl -plaintext -d '{
  "lock_id": "550e8400-e29b-41d4-a716-446655440000",
  "client_id": "client-B",
  "timeout_ms": 60000
}' localhost:9090 com.nationsbenefits.grpc.LockService/AcquireLock
```

The second request will fail with `LOCK_STATUS_ALREADY_LOCKED`.

## API Reference

### LockService

| Method | Description |
|--------|-------------|
| `AcquireLock` | Acquire a distributed lock with a specified timeout |
| `ReleaseLock` | Release a previously acquired lock |
| `CheckLock` | Check the status of a lock |

### Lock Status Codes

| Status | Description |
|--------|-------------|
| `LOCK_STATUS_OK` | Operation completed successfully |
| `LOCK_STATUS_ALREADY_LOCKED` | Lock is held by another client |
| `LOCK_STATUS_NOT_FOUND` | Lock not found |
| `LOCK_STATUS_INVALID_TOKEN` | Fencing token mismatch |
| `LOCK_STATUS_EXPIRED` | Lock has expired |
| `LOCK_STATUS_QUORUM_FAILED` | Cross-region quorum not reached |
| `LOCK_STATUS_NOT_LEADER` | Node is not the Raft leader |
| `LOCK_STATUS_TIMEOUT` | Request timed out |
| `LOCK_STATUS_ERROR` | Internal error |

## Configuration

Environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `NODE_ID` | Unique node identifier | `node-1` |
| `REGION_ID` | Region identifier | `default` |
| `GRPC_PORT` | Client gRPC port | `9090` |
| `REGION_PORT` | Inter-region gRPC port | `9091` |

## Architecture

```
+------------------+     +------------------+     +------------------+
|   us-east-1      |     |   us-west-2      |     |   eu-west-1      |
|                  |     |                  |     |                  |
|  +------------+  |     |  +------------+  |     |  +------------+  |
|  | Raft Node  |<------->| Raft Node  |<------->| Raft Node  |  |
|  +------------+  |     |  +------------+  |     |  +------------+  |
|        |         |     |        |         |     |        |         |
|  +------------+  |     |  +------------+  |     |  +------------+  |
|  | Lock Store |  |     |  | Lock Store |  |     |  | Lock Store |  |
|  +------------+  |     |  +------------+  |     |  +------------+  |
+------------------+     +------------------+     +------------------+
         ^                        ^                        ^
         |                        |                        |
    gRPC :9090               gRPC :9190               gRPC :9290
         |                        |                        |
    +---------+              +---------+              +---------+
    | Clients |              | Clients |              | Clients |
    +---------+              +---------+              +---------+
```

## License

Proprietary - NationsBenefits
