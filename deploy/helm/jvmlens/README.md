= jvmlens Helm chart

A *standalone* deployment that runs a JVM workload with the **jvmlens agent** attached —
it does not modify your app's main chart/release. The app writes a rolling, LLM-ready
profile summary (and optional non-breaking variable snapshots) to `/agent/jvmlens.md`,
served over HTTP by a tiny sidecar.

How it works: an init container copies `jvmlens-agent.jar` from a small OCI image into a
shared `emptyDir`; the app container sets `JAVA_TOOL_OPTIONS=-javaagent:/agent/jvmlens-agent.jar=...`
(honoured by buildpacks and standard JVM launchers); the agent records in-process and
writes the summary on an interval. No attach, no JMX.

== 1. Build & publish the agent image

`scripts/deploy-agent.sh` builds the agent jar, wraps it in the tiny OCI image, and pushes
it to the registry (default `nas1.home.int:5000`, the homelab Zot). It mirrors unitrack's
`deploy-k8s.sh` (podman/docker, `--tls-verify=false` for the self-signed wildcard):

[source,bash]
----
podman login nas1.home.int:5000      # once
scripts/deploy-agent.sh              # → nas1.home.int:5000/jvmlens-agent:<version>
----

== 2. Deploy (separate release)

One command builds, pushes, and installs the chart (point `--target-image` at the app):

[source,bash]
----
scripts/deploy-agent.sh --release unitrack-profiled --namespace unitrack \
  --target-image nas1.home.int:5000/unitrack:0.2.0-SNAPSHOT
----

Or `helm upgrade --install` directly for full control:

[source,bash]
----
helm upgrade --install unitrack-profiled deploy/helm/jvmlens --namespace unitrack \
  --set-json 'target.envFrom=[{"configMapRef":{"name":"unitrack"}},{"secretRef":{"name":"unitrack"}}]' \
  --set 'agent.snapshot=org.alexmond.unitrack.web.ReportController#ingest'   # optional
----

Reusing the app release's `ConfigMap`/`Secret` via `target.envFrom` gives the profiled copy
the same DB/config as the real app — it connects to the **same database**, so point it at a
throwaway DB (or run it read-mostly) if that matters. The pull secret `zot-regcred` must
exist in the namespace (it already does in `unitrack`).

== 3. Read the summary

[source,bash]
----
kubectl port-forward svc/unitrack-profiled-jvmlens 8090:8090
curl localhost:8090/jvmlens.md
----

== Notes

- This is deliberately a *second* deployment for experimentation — keep it out of the
  app's production/test release.
- For Docker Compose instead of k8s, attach the agent with a bind-mount + env:
  `JAVA_TOOL_OPTIONS=-javaagent:/agent/jvmlens-agent.jar=out=/agent/jvmlens.md,interval=300`
  and `-v ./jvmlens-agent.jar:/agent/jvmlens-agent.jar:ro -v ./out:/agent`.
