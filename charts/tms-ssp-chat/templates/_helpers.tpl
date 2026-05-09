{{/* Conditional timesamp on latest docker imageversion, use that to force reload of latest version images.     */}}
{{/*   If you have more than one docker image for i.e. a statefulset but only one annotation, then you can set  */}}
{{/*   .customAnnotation with 2 different potential labels.                                                     */}}
{{/*  Sample                                                                                                    */}}
{{/*  {{- include "timestamp.onlatest" (dict "Values" $.Values "imageVersion" $.Values.x.dockerImageVersion "customAnnotation" "xDockerImageTimestamp") | indent 8 }}   */}}
{{/*  {{- include "timestamp.onlatest" (dict "Values" $.Values "imageVersion" .Values.y.dockerImageVersion "customAnnotation" "yDockerImageTimestamp") | indent 8 }}    */}}
{{define "timestamp.onlatest"}}
{{- if or (eq .imageVersion "latest") (hasSuffix "-SNAPSHOT" .imageVersion) }}
{{ default "releaseTimestamp" .customAnnotation}}: {{ dateInZone "2006-01-02 15:04:05Z" (now) "UTC"| quote }}
{{- end}}
{{- end}}