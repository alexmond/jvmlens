= jvmlens Helm chart

A *standalone* deployment that runs a JVM workload with the **jvmlens agent** attached —
it does not modify your app's main chart/release. The app writes a rolling, LLM-ready
profile summary (and optional non-breaking variable snapshots) to `/agent/jvmlens.md`,
served over HTTP by a tiny sidecar.

How it works: an init container copies `jvmlens-agent.jar` from a small OCI image into a
shared `emptyDir`; the app container sets `JAVA_TOOL_OPTIONS=-javaagent:/agent/jvmlens-agent.jar=...`
(honoured by buildpacks and standard JVM launchers); the agent records in-process and
writes the summary on an interval. No attach, no JMX.

== 1. Publish the agent image

[source,bash]
----
# from the jvmlens repo root, after a build:
cp target/jvmlens-0.1.0-SNAPSHOT-agent.jar deploy/agent-image/jvmlens-agent.jar
docker build -t <registry>/jvmlens-agent:0.1.0 deploy/agent-image
docker push <registry>/jvmlens-agent:0.1.0   # e.g. your homelab zot registry
----

== 2. Deploy (separate release)

[source,bash]
----
helm install unitrack-profiled deploy/helm/jvmlens \
  --set agent.image=<registry>/jvmlens-agent:0.1.0 \
  --set target.image=ghcr.io/alexmond/unitrack:latest \
  --set-json 'target.envFrom=[{"configMapRef":{"name":"unitrack"}},{"secretRef":{"name":"unitrack"}}]' \
  --set 'agent.options=out=/agent/jvmlens.md,interval=300' \
  --set 'agent.snapshot=org.alexmond.unitrack.web.ReportController#ingest'   # optional
----

Reusing the app release's `ConfigMap`/`Secret` via `target.envFrom` is the simplest way to
give the profiled copy the same DB/config as the real app. (It connects to the same
database — run it read-mostly, or point it at a throwaway DB, if that matters.)

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
