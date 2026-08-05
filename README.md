# HomeOps

HomeOps is a mobile-first, self-hosted operations dashboard for an Apple Silicon Mac running Docker Desktop. Its target scope combines host metrics, container state, deployment metadata, backup-result metadata, and incident history without exposing an arbitrary shell or Docker command interface.

The source is public, but the supported deployment boundary is private: a single administrator accesses the PWA through Tailscale Serve. Do not publish HomeOps directly to the internet.

## Status

HomeOps is pre-release software. The implemented functionality is read-only host metrics and container inventory plus a disabled-by-default, HMAC-authenticated API foundation for deployment and backup-result history. Existing service scripts are not connected to that foundation yet. Service checks, incidents, notifications, bounded logs, and container control remain follow-up milestones. Container start, stop, and restart are deliberately excluded until label allowlists, operation locks, idempotency, and audit controls are complete.

The macOS Agent is installed by an operator. Agent code releases are published as immutable GHCR artifacts, but host rollout is separately disabled by default and uses a distinct restricted SSH key. The standard `main` deployment workflow continues to deploy only API, Web, and runtime-config images unless the independent Agent rollout switch is explicitly enabled after a staging/rollback drill. See the [implementation roadmap](docs/roadmap.md) before treating a future milestone as supported behavior.

## Architecture

- `frontend`: React 19, TypeScript, Vite, TanStack Query, Tailwind CSS, PWA
- `backend`: Java 21, Spring Boot 4.1, PostgreSQL, Flyway, server-side sessions
- `agent`: macOS Go process for real host metrics and restricted Docker Engine reads
- `deploy`: generic Docker Compose, Nginx, and LaunchAgent examples

The API container never mounts the Docker socket. A native user-level Agent reads the host and Docker Engine, then sends bounded snapshots through a loopback mTLS ingress.

## Supported environment

- Apple Silicon Mac
- macOS 26 or a compatible supported release
- Docker Desktop
- Tailscale Serve
- Docker Compose

Linux agents, Kubernetes, public-internet exposure, multi-user accounts, web terminals, arbitrary Docker commands, and automatic HomeOps database backups are outside the initial scope.

## Safety defaults

- Tailnet-only browser access
- Exact identity allowlist and server-side session
- CSRF protection for browser requests
- Dedicated PostgreSQL and Docker network
- No database host port
- No Docker socket in Web or API containers
- Read-only container inventory
- No API response caching in the service worker
- No telemetry

## Local development

The development environment is defined in `compose.dev.yaml`. Copy `.env.dev.example` to the ignored `.env.dev.local`, replace every required placeholder, and use the repository runbook before starting the stack.

Do not reuse production networks, volumes, credentials, certificates, or backup paths.

The pinned frontend dependency graph requires a committed `package-lock.json`; generate it with the repository's Node 24/npm 11 toolchain before the first CI run. Follow the sequential verification gates in `docs/operations.md`.

## Current API surface

- `GET /api/v1/session`
- `GET /api/v1/system/summary`
- `GET /api/v1/agent/status`
- `GET /api/v1/containers`, with Agent freshness metadata and read-only inventory
- `POST /api/v1/internal/agent/snapshots`, available only through the loopback mTLS ingress
- `POST /api/v1/internal/ingestion/deployments` and `/backups`, disabled until an operator configures the ingestion secret and explicitly connects a trusted script
- `GET /actuator/health/readiness`

There is no container mutation, arbitrary command, log, webhook, or settings mutation endpoint in this milestone.

## Data-loss policy

HomeOps does not automatically back up its own PostgreSQL database. Losing the database volume removes metric history, incidents, audit history, and database-backed settings. Essential deployment configuration must remain reconstructible from environment and configuration files.

A destructive schema migration requires a separately approved one-time logical snapshot or an explicit decision to reset the database and lose its history.

## Documentation

- `docs/architecture.md`
- `docs/installation.md`
- `docs/configuration.md`
- `docs/security.md`
- `docs/operations.md`
- `docs/roadmap.md`

## License

Apache License 2.0. See `LICENSE`.
