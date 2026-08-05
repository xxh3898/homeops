FROM alpine:3.23

ARG REVISION

LABEL org.opencontainers.image.revision="${REVISION}"
LABEL dev.homeops.runtime-config.project="homeops"

WORKDIR /runtime
COPY deploy/compose.example.yaml ./compose.yaml
COPY deploy/scripts ./scripts
RUN chmod 700 \
    ./scripts/deploy-homeops.sh \
    ./scripts/validate-https-origin.sh
