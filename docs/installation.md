# Installation

HomeOps installation changes Docker, Tailscale, certificates, `launchd`, and a production database. Treat each activation as a separate operator decision. The repository provides examples; it does not install or enable anything automatically.

## Prerequisites

- Apple Silicon Mac with a currently supported macOS release
- Docker Desktop and Docker Compose
- Tailscale with HTTPS enabled for the tailnet
- GitHub repository with Actions and GHCR access if automatic deployment is desired
- OpenSSL-compatible tooling for a private Agent certificate authority

The current code target is Java 21, Spring Boot 4.1, Node 24, React 19, Vite 8, Go 1.26, PostgreSQL 18, and `linux/arm64` application images. Confirm current compatibility before upgrading any major version.

## 1. Review and validate the source

Before enabling runtime components:

1. Read `SECURITY.md`, `docs/configuration.md`, and `docs/operations.md`.
2. Generate and commit the frontend lockfile using the pinned Node/npm toolchain.
3. Run the three focused test services in `compose.test.yaml` sequentially.
4. Render all Compose definitions and inspect the resolved mounts, ports, images, networks, and volumes.
5. Confirm that neither the Web nor API service mounts a Docker socket and that the database has no host port.

The repository is pre-release until these checks pass in its own CI.

## 2. Prepare the production directory

Keep source and production state separate. A production directory contains only:

- `.env` with mode `0600`;
- private TLS material in a mode-restricted directory;
- immutable runtime-config releases and current/pending pointers;
- deployment state and operation locks;
- the dedicated PostgreSQL volume managed by Docker.

Do not copy the Git working tree into the production directory. Copy `deploy/env.example` to a private `.env` and replace every placeholder. Create a mode-restricted `smoke.origin` from `deploy/smoke.origin.example`; it contains exactly one tailnet HTTPS origin without a path. Use the explicit `:9443` port when preserving existing Serve listeners on 443 and 8443. Do not commit either private file.

## 3. Create the Agent mTLS boundary

Create a private CA, a server certificate valid for `localhost` and loopback use, and a separate client certificate for the Agent. Keep private keys outside Git. Recommended permissions are:

- CA and server/client public certificates: readable only by the operator and required process;
- server and Agent private keys: `0600`;
- TLS directories: `0700`.

Use different keys for Agent mTLS, human SSH, and CI deployment. Do not reuse the tailnet authentication key or a GitHub token.

## 4. Install the native Agent manually (current behavior)

The CI validation workflow builds a macOS ARM64 Agent artifact identified by the commit SHA. Verify its SHA-256 checksum before installation. Place the binary and configuration under operator-owned, non-world-writable directories.

Start from `deploy/launchd/dev.homeops.agent.plist.example`, replace every placeholder, and verify:

- the exact `Label` is unique;
- all paths are absolute;
- the Docker socket is the active Docker Desktop context socket on that Mac;
- the API URL is loopback HTTPS and ends with the fixed Agent snapshot path;
- the log and spool directories exist with restrictive modes;
- the process runs as the logged-in operator without `sudo`.

Lint the plist before any load. Loading the LaunchAgent, testing a natural scheduled start, and testing reboot persistence are separate acceptance gates.

The `main` deployment workflow does **not** replace this Agent. It deploys only the API, Web, and runtime-config images. Keeping the Agent upgrade separate is intentional: the native process reads macOS metrics and the Docker socket, so a CI-to-host Agent replacement path needs its own constrained command, rollback, and acceptance checks.

### Future opt-in Agent rollout

Automatic Agent rollout is not implemented yet. When implemented, it must remain a separate opt-in deployment track and must not accept arbitrary paths, labels, actions, or shell fragments. Its minimum contract is:

1. CI builds an Agent artifact identified by the exact full commit SHA and records a checksum.
2. The Mac stages and verifies the immutable artifact before touching the active binary.
3. A dedicated restricted command switches only the configured Agent release pointer and restarts only the configured LaunchAgent label.
4. A fresh snapshot with the expected Agent version proves the new binary is healthy.
5. Failure restores the previous immutable binary pointer and reports a non-successful rollout.
6. A separate kill switch can disable Agent rollout without disabling API/Web deployment.

The artifact retention location, CI authorization, exact install layout, and staging/rollback drill are implementation decisions. Do not treat a checksum alone as an independent code-signing guarantee.

## 5. Start the application stack

The runtime Compose bundle is built from `runtime-config.Dockerfile`. It runs a dedicated PostgreSQL, Spring API, and Nginx/React Web service. Bind both published ports to loopback. The database must remain internal.

The initial database requires the one-shot `migration` profile before the API can pass schema validation. Future migrations must be additive and backward compatible. Because automatic HomeOps database backup is intentionally excluded, any destructive migration requires a separately approved logical snapshot or an explicit decision to discard all HomeOps history.

Do not substitute mutable tags. API and Web must use immutable `@sha256:` references, and both images' `org.opencontainers.image.revision` labels must identify the same full 40-character commit SHA.

## 6. Configure Tailscale Serve

Point one tailnet-only HTTPS origin at the loopback Web binding. Do not enable Funnel. Confirm that:

- the tailnet identity headers arrive at Nginx;
- only an allowlisted login receives an authenticated API response;
- a missing or different login receives `401` or `403`;
- direct LAN and public Internet access fail;
- the Agent listener remains loopback-only and requires its client certificate.

Tailscale CLI syntax can change; use the current official Serve documentation for the final command and inspect `tailscale serve status` before and after activation.

## 7. Install the iPhone PWA

Connect the iPhone to Tailscale, open the Serve HTTPS origin in Safari, and use **Add to Home Screen**. Verify fresh launch, safe-area layout, background/foreground refresh, network loss, stale-state warning, and update prompting. The API must never appear healthy from cached data.

## 8. Optional automatic deployment

Automatic deployment follows the same contract as the existing home-server projects: reviewed `main`, full validation, ARM64 images, immutable API/Web/runtime-config digests, a shared full-SHA revision identity, GHCR, Tailscale, restricted SSH, one-shot migration, health, tailnet smoke, and digest-pinned rollback.

Configure the repository values listed in `docs/configuration.md`, install the `deploy-homeops-v2` project-specific forced-command SSH key using `deploy/bootstrap/deploy-homeops-ci.sh.example`, and leave `MAC_MINI_DEPLOY_ENABLED` false until the bootstrap has passed a manual staging and rollback drill. The earlier v1 command grammar is intentionally rejected; update the installed bootstrap before enabling this workflow.

The example bootstrap contains placeholders and is not safe to install unchanged.
