{{/*
Expand the name of the chart.
*/}}
{{- define "lockmgr.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "lockmgr.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "lockmgr.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "lockmgr.labels" -}}
helm.sh/chart: {{ include "lockmgr.chart" . }}
{{ include "lockmgr.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "lockmgr.selectorLabels" -}}
app.kubernetes.io/name: {{ include "lockmgr.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/region: {{ .Values.region.id }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "lockmgr.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "lockmgr.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Create the headless service name
*/}}
{{- define "lockmgr.headlessServiceName" -}}
{{- printf "%s-headless" (include "lockmgr.fullname" .) }}
{{- end }}

{{/*
Generate Raft peer list from replica count
*/}}
{{- define "lockmgr.raftPeers" -}}
{{- $fullname := include "lockmgr.fullname" . -}}
{{- $headless := include "lockmgr.headlessServiceName" . -}}
{{- $namespace := .Release.Namespace -}}
{{- $port := .Values.service.grpcPort -}}
{{- $replicas := int .Values.replicaCount -}}
{{- $peers := list -}}
{{- range $i := until $replicas -}}
{{- $peers = append $peers (printf "%s-%d.%s.%s.svc.cluster.local:%d" $fullname $i $headless $namespace $port) -}}
{{- end -}}
{{- join "," $peers -}}
{{- end }}

{{/*
Generate region peer configuration as JSON
*/}}
{{- define "lockmgr.regionPeersJson" -}}
{{- if .Values.region.peers -}}
{{- .Values.region.peers | toJson -}}
{{- else -}}
[]
{{- end -}}
{{- end }}
