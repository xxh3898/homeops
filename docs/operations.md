# Operations runbook

This runbook describes contracts and checks. It is not authorization to change a running Mac. Replace placeholders only in a private operator copy and inspect every resolved target before execution.

## Lifecycle gates

| Gate | Evidence | Current repository state |
|---|---|---|
| G0 read-only baseline | Host/runtime/repository inventory and decisions | documented; live values can drift |
| G1 development | Focused Backend, Frontend, Agent tests with production isolation | completed locally; sequential Docker verification passed |
| G2 path-aware CI | Classifier cases and stable required contexts | implemented; latest push and pull request Validate runs passed |
| G3 deploy staging | Bootstrap identity, release shape, modes, idempotence, rollback | executable mock regression coverage passed locally; CI and live host staging not executed |
| G4 initial migration | Empty dedicated DB migration and JPA validation | isolated PostgreSQL 18.4 initial migration, V1-to-V2 upgrade, and JPA validation passed |
| G5 Agent | mTLS delivery, actual Mac metrics, Docker socket, spool recovery | not executed |
| G6 tailnet/PWA | Serve identity, iPhone install, background recovery, no public access | not executed |
| G7 production | immutable-digest deploy, SHA identity validation, health, tailnet smoke, previous rollback | not authorized or executed |

HomeOps intentionally omits the Master Playbook's recurring/offsite backup, retention, and restore gates for its own PostgreSQL. This is an explicit durability tradeoff, not a successful backup state. A destructive migration still requires a one-time logical snapshot or an explicit reset decision.

## Focused development verification

Run checks sequentially on the isolated `homeops-test` Compose project:

1. Agent unit tests and macOS ARM64 cross-build
2. Backend unit/slice tests
3. Frontend lint, typecheck, unit tests, and production build
4. Compose rendering for development, test, and production examples
5. runtime-config image build

Before running, confirm memory pressure, disk headroom, active production health, exact Compose files, and that no test service mounts an operational socket, network, volume, credential, or endpoint. Test containers are disposable; named test caches are not production data.

## Deployment transaction

Phase 3 ingestion activation is a separate host operation after the source releases are merged. Confirm the HomeOps `.env` contains one generated 64-character lowercase hexadecimal `HOMEOPS_INGESTION_SHARED_SECRET`, `smoke.origin` is the intended tailnet HTTPS origin, both files remain owner-only mode `0600`, and `~/Server/data/homeops/ingestion-spool` for the deployment account is owner-only mode `0700`. Cubing Hub and Guess Pokémon begin emitting events only after their own runtime-config releases containing the hook are deployed. A reporter warning must not be treated as a failed application deploy or backup; inspect the spool and HomeOps ingestion health separately.

The GitHub workflow performs:

1. reusable full validation on `main`;
2. ARM64 API, Web, and runtime-config image publication;
3. exact commit SHA plus immutable API, Web, and runtime-config digest capture;
4. tailnet connection using an OIDC OAuth client;
5. restricted SSH invocation with the GHCR token on standard input;
6. runtime release extraction and validation;
7. non-blocking project locks;
8. dedicated database start and one-shot Flyway migration;
9. API/Web replacement and container health checks;
10. tailnet HTTPS root, representative asset, and readiness smoke from the Mac;
11. current/previous state update;
12. a second tailnet readiness smoke from the GitHub runner.

The runtime-config image is rebuilt on every release. This costs a small extra build but avoids a hidden Compose/script synchronization path in the first public release.

## State files and pointers

- `runtime-config/pending` identifies an incomplete candidate transaction.
- `runtime-config/current` identifies the runtime configuration associated with the accepted deployment state.
- `deployment.state` contains current/previous application SHA, API digest, Web digest, and runtime-config digest.
- Lock files contain no credentials.

If `pending` remains, do not delete it automatically. Inspect the deployment log, `deployment.state`, both symlink targets, the exact candidate release, current container image digests and revision labels, and database migration state. Decide whether to resume, return to the current release, or repair a partial state. Broad cleanup and `docker system prune` are not recovery steps.

## Failure handling

| Failure | Immediate behavior | Operator follow-up |
|---|---|---|
| validation or image build | no publish/deploy | fix source and rerun reviewed CI |
| runtime image identity/shape mismatch | bootstrap stops | verify digest, GHCR package, and immutable release directory |
| lock unavailable | exits without overlap | find the active exact operation; do not bypass the lock |
| migration failure | cutover stops | inspect Flyway and DB; prefer a forward fix |
| API/Web health failure | previous digest-pinned images and runtime config attempted | verify rollback health and keep pending evidence |
| tailnet smoke failure | workflow fails after local health | separate Serve/ACL/identity from application health |
| first-deploy verification failure | API/Web are stopped; no accepted `current` state exists | preserve `pending` and the dedicated DB for diagnosis; do not delete the volume automatically |
| Agent delivery failure | retryable pending delivery failure preserves FIFO and suppresses newer collection; a newly collected snapshot is queued when its delivery fails | verify API, mTLS, spool capacity, and clock without deleting spool files |
| snapshot permanently rejected | consecutive permanent rejects are renamed to hidden `.rejected-*.json` evidence files in the same FIFO drain; fresh collection resumes after no retryable pending item remains | inspect only metadata and safe error status; rejected evidence still counts toward bounded spool capacity, so decide exact retention manually |
| stale Agent | UI warns stale/offline | inspect the native process and Docker Desktop; no automatic restart |

Because Flyway may already have changed the database, an image rollback is safe only for backward-compatible migrations. Never use an incompatible migration in the automatic path.

## Routine read-only checks

- API and Web container health
- current application SHA plus API, Web, and runtime-config digests
- Agent last captured/received time and version
- spool file count without reading payloads
- PostgreSQL volume size and host disk trend
- Tailscale Serve status and absence of Funnel
- Uptime Kuma HTTP monitor status
- GitHub deployment workflow and Production environment history

Do not print environment values, full Tailnet status, certificates, private paths, or Docker inspect environments into issue reports.

## Uptime Kuma and notifications

Keep Uptime Kuma as the independent HTTP availability source and retain its current email behavior. HomeOps must not emit duplicate health alerts until a service-ownership matrix is configured. Later HomeOps Discord events should cover Docker, Agent, deployment, backup-result ingestion, and internal incident transitions. Critical long-duration failures may escalate to email, but the same incident needs one owner and one deduplication key.

## Data loss and reconstruction

There is no automatic HomeOps database backup. Loss of the PostgreSQL volume loses host metric history, sessions, incidents, deployment/backup metadata, notifications, audit events, and database settings. The service can be rebuilt from:

- the public repository and exact immutable image digests;
- the private `.env` and TLS material;
- the Agent binary/configuration and LaunchAgent definition;
- the stable bootstrap and deployment state.

Those private files need an operator-managed recovery method even though HomeOps does not automate it. If they exist only on the same disk, a disk failure prevents reconstruction until secrets and certificates are recreated.

## Agent upgrades

### Current: operator-managed installation

Agent publication and Agent installation are separate from API/Web deployment. Verify the artifact checksum, install the binary under the immutable release directory, point the fixed `current` symlink at it, and confirm version plus a fresh snapshot. The standard `main` deployment workflow does not update the native Agent.

### Opt-in automatic rollout

Automatic Agent rollout is implemented but disabled by default because the Agent reads macOS state and the Docker socket. Do not extend `deploy-homeops-v2` with Agent path, LaunchAgent label, action, or shell inputs; the rollout uses its own key and only accepts the exact `rollout-homeops-agent-v1` grammar.

Before setting `HOMEOPS_AGENT_ROLLOUT_ENABLED=true`, prove all of the following in staging:

1. A persistent GHCR exact-SHA Agent artifact and checksum, with current and previous digests retained for rollback.
2. A dedicated restricted host command that accepts only the expected full SHA and verified artifact identity.
3. A per-Agent lock, staged immutable release directory, validated file owner/mode, and atomic current/previous pointer transition.
4. An explicit first-install policy and a rollback path that does not delete the previous known-good binary.
5. Restart of only the configured LaunchAgent label, never a caller-supplied label or global launchd operation.
6. Success proof from a fresh Agent snapshot whose reported version matches the requested release.
7. The separate Agent rollout kill switch, disabled-by-default opt-in, and a staging plus rollback drill.

The host stages `agent/releases/<SHA>` with a verified binary/checksum, atomically changes `current` and `previous`, and kickstarts only `gui/<uid>/dev.homeops.agent`. It accepts the rollout only after `agent/version-proof` contains the requested SHA and a post-restart successful snapshot timestamp. Failure restores `current` to the previous immutable release; first-install failure removes `current` and boots out only that exact label. An API/Web deployment remains independently successful when no Agent rollout was requested. See [the implementation roadmap](roadmap.md) for the implementation order.
