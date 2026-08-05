# Implementation roadmap

## Purpose and status

This roadmap is the ordered implementation plan for HomeOps. It distinguishes current supported behavior from planned work; a later phase is not an implementation commitment or production authorization.

The supported baseline is a tailnet-only, read-only PWA with host metrics and container inventory. The native macOS Agent and API/Web deployment are intentionally separate delivery boundaries.

## Delivery principles

- Keep the API and Web containers away from the Docker socket.
- Keep the native Agent bounded to fixed macOS and Docker operations.
- Prefer immutable full-SHA identities, explicit health evidence, and rollback over mutable tags or implicit latest artifacts.
- Treat CI-to-host Agent replacement as a separate privilege from API/Web image deployment.
- Do not add a capability only because a table already exists for its future data.

## Phase 1: read-only dashboard accuracy and usability

**Goal:** make the current iPhone dashboard reliable and easy to scan without adding control capabilities.

| Item | Scope | Completion evidence |
|---|---|---|
| Host metric alignment | Define memory usage consistently with the chosen macOS/Netdata meaning; retain CPU sampling and memory-pressure context | Agent regression tests and same-time macOS/Netdata comparison |
| Project container groups | Group inventory by Docker Compose project, with a mobile-friendly accessible accordion and clear aggregate health | Frontend regression tests; iPhone visual check with multiple Compose projects |
| Metric history | Expose already-aggregated host metrics as bounded time ranges and show history without caching API responses as live state | API, retention, frontend, and stale-data tests |
| Container detail and logs | Add bounded read-only detail and tail endpoints; redact/limit output and never accept a host path | Agent/API tests, size limit tests, security review |

**Not included:** start, stop, restart, arbitrary Docker commands, or full log retention.

## Phase 2: opt-in native Agent rollout

**Goal:** make an Agent code change deployable after `main` merge without granting a generic remote shell or silently overwriting the last known-good binary.

### Required implementation order

1. Choose a persistent artifact location and retention policy for the exact macOS ARM64 binary and checksum. A short-lived CI artifact alone is insufficient for rollback.
2. Define a dedicated Agent rollout request with only full commit SHA and verified artifact identity as input.
3. Implement a host-side restricted wrapper with a per-Agent lock, fixed installation root, exact LaunchAgent label, and no caller-controlled path or command.
4. Stage the binary in an immutable versioned directory; verify checksum, regular-file type, owner, and restrictive mode before promotion.
5. Switch `current` and `previous` pointers atomically, then restart only the configured LaunchAgent.
6. Wait for a fresh snapshot with the requested Agent version. Promote only after that evidence arrives.
7. On failure, restore the previous pointer, restart the previous Agent, preserve rollout evidence, and return failure.
8. Add a disabled-by-default Agent rollout switch independent from API/Web deployment, then execute a staging and rollback drill before enabling production use.

**Completion evidence:** unit and deployment-wrapper regression tests; checksum rejection; concurrent rollout rejection; first-install behavior; rollback after restart or snapshot failure; a Mac staging drill; an iPhone/API freshness check.

**Not included:** Agent self-update, arbitrary artifact URLs, arbitrary `launchctl` commands, sudo, Docker command input, or automatic cleanup of old releases.

## Phase 3: operational history

**Goal:** turn the reserved data model into bounded, auditable operational history.

| Item | Scope | Completion evidence |
|---|---|---|
| Deployment ingestion | Receive idempotent deployment start/success/failure events from the trusted deployment path | signature/auth, idempotency, state transition tests |
| Backup-result ingestion | Receive metadata from existing project backup scripts; HomeOps does not execute backups | logical identifier validation and failure-path tests |
| Service checks and incidents | Configured HTTP checks, consecutive failure/recovery logic, and incident history | timeout, state transition, retention tests |
| Activity view | Mobile timeline for deployment, backup, incident, and Agent events | empty, stale, error, and pagination tests |

**Not included:** a Uptime Kuma internal API dependency or replacement of its independent reachability role.

## Phase 4: notifications

**Goal:** provide useful operational signals without duplicate alert storms.

- Discord delivery for Agent, Docker, deployment, backup-result, and incident events.
- Deduplication keys, cooldown, long-duration escalation, recovery notification, and delivery-failure records.
- A documented ownership matrix: Uptime Kuma keeps external HTTP availability and its email path; HomeOps owns internal operational events.
- Optional email escalation only after the incident owner and duplicate-prevention policy are tested.

**Completion evidence:** mock webhook tests, retry/dedup/recovery tests, and no duplicate alert for one ownership-matrix scenario.

## Phase 5: restricted container control

**Goal:** permit deliberate start, stop, and restart for explicitly managed containers only.

1. Require live `homeops.managed=true` label verification plus a server-managed project allowlist.
2. Add fixed operations only; no Docker CLI, shell, image, volume, network, or Compose input.
3. Require CSRF, Origin checks, confirmation UX, idempotency keys, rate limits, operation locks, and audit records.
4. Default-deny HomeOps, database, and unknown containers; introduce stronger confirmation only through an explicit policy.
5. Verify an operation result through a fresh Agent snapshot rather than assuming a Docker request succeeded.

**Completion evidence:** authorization, CSRF, allowlist, duplicate request, audit, timeout, stale Agent, and database-protection tests.

## Documentation and release discipline

Each completed phase must update the supported scope in `README.md`, the appropriate installation/operations runbook, and tests before it is declared available. Code changes, commit, push, PR, merge, API/Web deployment, and Agent rollout remain separate approval steps.
