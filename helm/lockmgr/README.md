# Lockmgr Helm Chart

Helm chart for deploying the distributed lock manager with Raft consensus.

## Prerequisites

- Kubernetes 1.19+
- Helm 3.0+
- Istio 1.10+ (for service mesh features)

## Installation

### Create namespace with Istio injection

```bash
kubectl create namespace lockmgr-us-east
kubectl label namespace lockmgr-us-east istio-injection=enabled
```

### Install the chart

```bash
# US-East region
helm install lockmgr-us-east ./helm/lockmgr \
  -n lockmgr-us-east \
  -f ./helm/lockmgr/values-us-east.yaml \
  --set image.repository=your-registry/lockmgr

# US-West region
helm install lockmgr-us-west ./helm/lockmgr \
  -n lockmgr-us-west \
  -f ./helm/lockmgr/values-us-west.yaml \
  --set image.repository=your-registry/lockmgr
```

## Configuration

| Parameter | Description | Default |
|-----------|-------------|---------|
| `replicaCount` | Number of replicas in the StatefulSet | `3` |
| `region.id` | Region identifier | `us-east` |
| `region.peers` | Cross-region peer configuration | `[]` |
| `image.repository` | Container image repository | `lockmgr` |
| `image.tag` | Container image tag | `latest` |
| `image.pullPolicy` | Image pull policy | `IfNotPresent` |
| `resources.limits.memory` | Memory limit | `512Mi` |
| `resources.limits.cpu` | CPU limit | `500m` |
| `resources.requests.memory` | Memory request | `256Mi` |
| `resources.requests.cpu` | CPU request | `250m` |
| `raft.electionTimeoutMs` | Raft election timeout in milliseconds | `150` |
| `raft.heartbeatIntervalMs` | Raft heartbeat interval in milliseconds | `50` |
| `lock.defaultTimeoutMs` | Default lock timeout | `30000` |
| `lock.maxTimeoutMs` | Maximum lock timeout | `300000` |
| `lock.minTimeoutMs` | Minimum lock timeout | `1000` |
| `service.grpcPort` | gRPC service port | `9090` |
| `service.regionPort` | Cross-region communication port | `9091` |
| `service.actuatorPort` | Health check actuator port | `8080` |

## Architecture

Each region deployment consists of:

- **StatefulSet**: 3 replicas for Raft consensus quorum
- **Headless Service**: For pod DNS discovery within the cluster
- **ClusterIP Service**: For load-balanced access to the region
- **ConfigMap**: Configuration for Raft and lock parameters

## Ports

| Port | Protocol | Purpose |
|------|----------|---------|
| 9090 | gRPC | Client LockService + Raft |
| 9091 | gRPC | Cross-region quorum |
| 8080 | HTTP | Actuator health endpoints |

## Health Checks

- **Liveness**: HTTP GET `/actuator/health` on port 8080
- **Readiness**: TCP check on gRPC port 9090

## Cross-Region Communication

Regions communicate via the ClusterIP service on port 9091. Configure peers in the regional values file:

```yaml
region:
  id: "us-east"
  peers:
    - regionId: "us-west"
      host: "lockmgr.lockmgr-us-west.svc.cluster.local"
      port: 9091
```

## Uninstall

```bash
helm uninstall lockmgr-us-east -n lockmgr-us-east
helm uninstall lockmgr-us-west -n lockmgr-us-west
```
