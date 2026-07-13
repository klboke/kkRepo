{{- define "kkrepo.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "kkrepo.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name (include "kkrepo.name" .) | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "kkrepo.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
app.kubernetes.io/name: {{ include "kkrepo.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "kkrepo.selectorLabels" -}}
app.kubernetes.io/name: {{ include "kkrepo.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "kkrepo.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "kkrepo.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "kkrepo.validate" -}}
{{- if not (has .Values.database.type (list "mysql" "postgresql")) }}
{{- fail "database.type must be mysql or postgresql" }}
{{- end }}
{{- if not .Values.externalDatabase.enabled }}
{{- fail "externalDatabase.enabled must remain true unless an explicit database subchart is added" }}
{{- end }}
{{- if .Values.embeddedDatabase.enabled }}
{{- fail "embeddedDatabase.enabled is not implemented; use an external database" }}
{{- end }}
{{- if and (gt (int .Values.replicaCount) 1) .Values.blobStorage.file.enabled (not .Values.blobStorage.file.existingClaim) }}
{{- fail "replicaCount > 1 with File storage requires blobStorage.file.existingClaim backed by ReadWriteMany storage" }}
{{- end }}
{{- end }}
