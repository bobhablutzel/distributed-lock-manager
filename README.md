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

## Kubernetes Deployment with Istio

For production deployments, the lock manager can be deployed to Kubernetes with Istio service mesh for mTLS, traffic management, and observability.

### Prerequisites

- Kubernetes 1.19+
- Helm 3.0+
- istioctl 1.10+

### Install Istio

```bash
# Install Istio with the demo profile
istioctl install --set profile=demo -y

# Verify installation
kubectl get pods -n istio-system
```

### Deploy the Lock Manager

```bash
# Build the Docker image (if using local registry)
docker build -t lockmgr:latest .

# Deploy US-East region
helm upgrade --install lockmgr-us-east helm/lockmgr \
  -f helm/lockmgr/values-us-east.yaml \
  -n lockmgr-us-east --create-namespace

# Deploy US-West region
helm upgrade --install lockmgr-us-west helm/lockmgr \
  -f helm/lockmgr/values-us-west.yaml \
  -n lockmgr-us-west --create-namespace

# Deploy Istio configuration (routes traffic via gateway)
helm upgrade --install lockmgr-istio helm/lockmgr-istio \
  -n istio-system \
  --set gateway.tls.enabled=false \
  --set 'regions[0].serviceName=lockmgr-us-east' \
  --set 'regions[1].serviceName=lockmgr-us-west'
```

### Verify Deployment

```bash
# Check pods have Istio sidecars (2/2 containers)
kubectl get pods -n lockmgr-us-east
kubectl get pods -n lockmgr-us-west

# Verify Istio proxy status
istioctl proxy-status

# Analyze configuration for errors
istioctl analyze --all-namespaces
```

### Architecture with Istio

```
                    ┌─────────────────────────────────────────────┐
                    │           Istio Ingress Gateway             │
                    │              locks.example.com              │
                    │                  :80 (gRPC)                 │
                    └─────────────────┬───────────────────────────┘
                                      │
              ┌───────────────────────┴───────────────────────┐
              │           VirtualService Routing              │
              │           Header: x-region                    │
              ▼                                               ▼
   ┌──────────────────────┐                       ┌──────────────────────┐
   │  lockmgr-us-east     │◄─────── mTLS ────────►│  lockmgr-us-west     │
   │  namespace           │                       │  namespace           │
   │  ┌────────────────┐  │                       │  ┌────────────────┐  │
   │  │ Pod + Envoy    │  │                       │  │ Pod + Envoy    │  │
   │  │ Pod + Envoy    │  │                       │  │ Pod + Envoy    │  │
   │  │ Pod + Envoy    │  │                       │  │ Pod + Envoy    │  │
   │  └────────────────┘  │                       │  └────────────────┘  │
   └──────────────────────┘                       └──────────────────────┘
```

### Testing through Istio Gateway

**Docker Desktop Setup (one-time)**

Enable direct localhost access by patching the ingress gateway to use hostPort:

```bash
kubectl patch deployment istio-ingressgateway -n istio-system --type='json' -p='[
  {"op": "add", "path": "/spec/template/spec/containers/0/ports/1/hostPort", "value": 80}
]'
```

**Testing the Lock Service**

```bash
# List available services
grpcurl -plaintext -authority locks.example.com localhost:80 list

# Acquire a lock (routes to default region: us-east)
grpcurl -plaintext -authority locks.example.com -d '{
  "lock_id": "550e8400-e29b-41d4-a716-446655440000",
  "client_id": "my-client",
  "timeout_ms": 30000
}' localhost:80 com.nationsbenefits.grpc.LockService/AcquireLock

# Route to US-West using x-region header
grpcurl -plaintext -authority locks.example.com \
  -H "x-region: us-west" -d '{
  "lock_id": "660e8400-e29b-41d4-a716-446655440001",
  "client_id": "west-client",
  "timeout_ms": 30000
}' localhost:80 com.nationsbenefits.grpc.LockService/AcquireLock

# Check lock status
grpcurl -plaintext -authority locks.example.com -d '{
  "lock_id": "550e8400-e29b-41d4-a716-446655440000"
}' localhost:80 com.nationsbenefits.grpc.LockService/CheckLock
```

**Alternative: Port-forward (if hostPort isn't available)**

```bash
kubectl port-forward svc/istio-ingressgateway -n istio-system 8080:80 &
# Then use localhost:8080 instead of localhost:80
```

**Production (LoadBalancer IP)**

```bash
export GATEWAY_IP=$(kubectl get svc istio-ingressgateway -n istio-system \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

grpcurl -plaintext -authority locks.example.com $GATEWAY_IP:80 \
  com.nationsbenefits.grpc.LockService/AcquireLock
```

### Istio Features

| Feature | Description |
|---------|-------------|
| **mTLS** | Strict mutual TLS between all services |
| **Header-based routing** | Route to specific region via `x-region` header |
| **Automatic retries** | 3 retry attempts on connection failures |
| **Circuit breaking** | Outlier detection ejects unhealthy pods |
| **Connection pooling** | HTTP/2 with configurable max requests |

### Cleanup

```bash
helm uninstall lockmgr-istio -n istio-system
helm uninstall lockmgr-us-east -n lockmgr-us-east
helm uninstall lockmgr-us-west -n lockmgr-us-west
kubectl delete namespace lockmgr-us-east lockmgr-us-west
istioctl uninstall --purge -y
```

For detailed Istio configuration options, see [helm/lockmgr-istio/README.md](helm/lockmgr-istio/README.md).

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
