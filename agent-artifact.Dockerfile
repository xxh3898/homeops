FROM --platform=$BUILDPLATFORM golang:1.26.5-alpine AS build

ARG TARGETOS
ARG TARGETARCH
ARG REVISION

WORKDIR /source/agent
COPY agent/go.mod ./
RUN go mod download
COPY agent/ ./
RUN test "${TARGETOS}" = "linux" \
    && test "${TARGETARCH}" = "arm64" \
    && test -n "${REVISION}" \
    && printf '%s' "${REVISION}" | grep -Eq '^[0-9a-f]{40}$' \
    && CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 go build -trimpath \
      -ldflags "-s -w -X main.version=${REVISION}" \
      -o /out/homeops-agent ./cmd/homeops-agent \
    && cd /out \
    && sha256sum homeops-agent >homeops-agent.sha256

FROM scratch

ARG REVISION
LABEL org.opencontainers.image.revision=${REVISION} \
      org.opencontainers.image.version=${REVISION} \
      dev.homeops.agent-artifact.project=homeops

COPY --from=build /out/homeops-agent /homeops-agent
COPY --from=build /out/homeops-agent.sha256 /homeops-agent.sha256
