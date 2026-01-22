# Lockmgr Istio Helm Chart

Istio service mesh configuration for the distributed lock manager, providing a single entry point for regional clusters with strict mTLS.

## Prerequisites

- Kubernetes 1.19+
- Helm 3.0+
- Istio 1.10+
- Lockmgr regional deployments (see `../lockmgr/`)

## Architecture

```
                    ┌─────────────────────────────────────────────┐
                    │           Istio Ingress Gateway             │
                    │         (Single Entry Point - gRPC)         │
                    │              locks.example.com              │
                    └─────────────────┬───────────────────────────┘
                                      │
              ┌───────────────────────┴───────────────────────┐
              │ Header: x-region                              │
              ▼                                               ▼
   ┌──────────────────────┐                       ┌──────────────────────┐
   │  lockmgr-us-east     │                       │  lockmgr-us-west     │
   │  (default)           │                       │                      │
   └──────────────────────┘                       └──────────────────────┘
```

## Installation

### Prerequisites

1. Deploy the lockmgr application to both regions first:

```bash
# Deploy US-East
helm install lockmgr-us-east ../lockmgr \
  -n lockmgr-us-east \
  -f ../lockmgr/values-us-east.yaml

# Deploy US-West
helm install lockmgr-us-west ../lockmgr \
  -n lockmgr-us-west \
  -f ../lockmgr/values-us-west.yaml
```

2. Install the Istio configuration:

```bash
helm install lockmgr-istio . -n istio-system
```

### TLS Configuration (Optional)

To enable TLS, create a secret with your certificate:

```bash
kubectl create secret tls lockmgr-tls-cert \
  -n istio-system \
  --cert=path/to/cert.pem \
  --key=path/to/key.pem
```

## Configuration

### Gateway

| Parameter | Description | Default |
|-----------|-------------|---------|
| `gateway.name` | Gateway resource name | `lockmgr-gateway` |
| `gateway.host` | Hostname for the gateway | `locks.example.com` |
| `gateway.selector` | Istio gateway selector | `istio: ingressgateway` |
| `gateway.tls.enabled` | Enable TLS | `true` |
| `gateway.tls.mode` | TLS mode | `SIMPLE` |
| `gateway.tls.credentialName` | Secret name for TLS cert | `lockmgr-tls-cert` |

### Regions

| Parameter | Description | Default |
|-----------|-------------|---------|
| `regions` | List of region configurations | us-east, us-west |
| `regions[].id` | Region identifier | - |
| `regions[].namespace` | Kubernetes namespace | - |
| `regions[].serviceName` | Service name | `lockmgr` |
| `regions[].servicePort` | Service port | `9090` |
| `defaultRegion` | Default region for requests without x-region header | `us-east` |

### Virtual Service

| Parameter | Description | Default |
|-----------|-------------|---------|
| `virtualService.retries.attempts` | Number of retry attempts | `3` |
| `virtualService.retries.perTryTimeout` | Timeout per retry | `10s` |
| `virtualService.retries.retryOn` | Retry conditions | connection failures |
| `virtualService.timeout` | Overall request timeout | `30s` |

### Destination Rule

| Parameter | Description | Default |
|-----------|-------------|---------|
| `destinationRule.connectionPool.http.h2UpgradePolicy` | HTTP/2 upgrade policy | `UPGRADE` |
| `destinationRule.connectionPool.http.http2MaxRequests` | Max HTTP/2 requests | `100` |
| `destinationRule.outlierDetection.consecutive5xxErrors` | Errors before ejection | `3` |
| `destinationRule.outlierDetection.interval` | Detection interval | `30s` |
| `destinationRule.tls.mode` | TLS mode | `ISTIO_MUTUAL` |

### Peer Authentication

| Parameter | Description | Default |
|-----------|-------------|---------|
| `peerAuthentication.mtls.mode` | mTLS mode | `STRICT` |

## Usage

### Routing by Region Header

Requests are routed based on the `x-region` header.

**Local Development (Docker Desktop / port-forward):**

```bash
# Start port-forward to the ingress gateway
kubectl port-forward svc/istio-ingressgateway -n istio-system 8080:80 &

# Acquire lock in US-East (default, no header needed)
grpcurl -plaintext -authority locks.example.com \
  -d '{"lock_id": "550e8400-e29b-41d4-a716-446655440000", "client_id": "test", "timeout_ms": 30000}' \
  localhost:8080 com.nationsbenefits.grpc.LockService/AcquireLock

# Acquire lock in US-West (explicit routing)
grpcurl -plaintext -authority locks.example.com -H "x-region: us-west" \
  -d '{"lock_id": "660e8400-e29b-41d4-a716-446655440001", "client_id": "test", "timeout_ms": 30000}' \
  localhost:8080 com.nationsbenefits.grpc.LockService/AcquireLock

# Check lock status (routes to default region)
grpcurl -plaintext -authority locks.example.com \
  -d '{"lock_id": "550e8400-e29b-41d4-a716-446655440000"}' \
  localhost:8080 com.nationsbenefits.grpc.LockService/CheckLock

# Stop port-forward
kill %1
```

**Production (LoadBalancer IP):**

```bash
export INGRESS_HOST=$(kubectl -n istio-system get service istio-ingressgateway \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

grpcurl -plaintext -authority locks.example.com -H "x-region: us-east" \
  -d '{"lock_id": "550e8400-e29b-41d4-a716-446655440000", "client_id": "test", "timeout_ms": 30000}' \
  $INGRESS_HOST:80 com.nationsbenefits.grpc.LockService/AcquireLock
```

### Verify mTLS

Check that mTLS is enabled between services:

```bash
istioctl proxy-config clusters -n lockmgr-us-east deploy/lockmgr-us-east
```

## Components

This chart creates the following Istio resources:

1. **Gateway**: Single entry point for all gRPC traffic
2. **VirtualService**: Routes traffic based on `x-region` header
3. **DestinationRule**: Connection pooling, outlier detection, and mTLS (per region)
4. **PeerAuthentication**: Strict mTLS enforcement (per region)

## Uninstall

```bash
helm uninstall lockmgr-istio -n istio-system
```
