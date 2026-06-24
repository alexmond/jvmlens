{{- define "jvmlens.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "jvmlens.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "jvmlens.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "jvmlens.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/name: {{ include "jvmlens.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "jvmlens.selectorLabels" -}}
app.kubernetes.io/name: {{ include "jvmlens.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "jvmlens.javaToolOptions" -}}
-javaagent:/agent/jvmlens-agent.jar={{ .Values.agent.options }}{{ if .Values.agent.snapshot }},snapshot={{ .Values.agent.snapshot }}{{ end }}
{{- end -}}
