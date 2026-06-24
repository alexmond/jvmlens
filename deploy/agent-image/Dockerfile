# Tiny OCI image that just carries jvmlens-agent.jar, so an init container can copy
# it into a shared volume next to any JVM workload (see deploy/helm/jvmlens).
#
# Build (from the repo root, after `mvn package` produces the agent jar):
#   cp target/jvmlens-0.1.0-SNAPSHOT-agent.jar deploy/agent-image/jvmlens-agent.jar
#   docker build -t <registry>/jvmlens-agent:0.1.0 deploy/agent-image
#   docker push <registry>/jvmlens-agent:0.1.0
FROM busybox:1.37
COPY jvmlens-agent.jar /jvmlens-agent.jar
