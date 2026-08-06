# Configuration

Every secret and host-specific value stays outside Git. Example values use reserved domains or replacement markers and are not operational defaults.

## Application environment

| Key | Required | Secret | Purpose |
|---|---:|---:|---|
| `HOMEOPS_DB_NAME` | yes | no | Dedicated PostgreSQL database name |
| `HOMEOPS_DB_USER` | yes | no | Dedicated PostgreSQL role |
| `HOMEOPS_DB_PASSWORD` | yes | yes | PostgreSQL password |
| `HOMEOPS_ALLOWED_USERS` | yes | personal | Comma-separated exact Tailscale login allowlist |
| `HOMEOPS_AGENT_ID` | yes | no | Exact Agent identifier expected by the API |
| `HOMEOPS_WEB_BIND` | production | no | Loopback Web host binding |
| `HOMEOPS_AGENT_BIND` | production | no | Loopback mTLS Agent binding |
| `HOMEOPS_TLS_DIR` | production | sensitive path | Directory containing server certificate, key, and Agent CA certificate |
| `HOMEOPS_API_IMAGE` | manual runs | no | Immutable API image digest reference; the image revision label must match the release SHA |
| `HOMEOPS_WEB_IMAGE` | manual runs | no | Immutable Web image digest reference; the image revision label must match the release SHA |
| `HOMEOPS_DB_POOL_SIZE` | no | no | Hikari maximum pool size; default `5` |
| `HOMEOPS_DB_MIN_IDLE` | no | no | Hikari minimum idle; default `1` |
| `HOMEOPS_DB_MAX_CONNECTIONS` | no | no | PostgreSQL connection ceiling; default `20` |
| `HOMEOPS_DB_SHARED_BUFFERS` | no | no | PostgreSQL shared buffers; default `128MB` |
| `HOMEOPS_DB_MEMORY_LIMIT` | no | no | PostgreSQL container memory limit; default `512m` |
| `HOMEOPS_API_MEMORY_LIMIT` | no | no | API container memory limit; default `640m` |
| `HOMEOPS_WEB_MEMORY_LIMIT` | no | no | Web container memory limit; default `64m` |
| `HOMEOPS_JAVA_TOOL_OPTIONS` | no | no | JVM memory options; default `-Xms128m -Xmx384m` |
| `HOMEOPS_AGENT_STALE_AFTER` | no | no | Stale threshold; default `30s` |
| `HOMEOPS_AGENT_MAXIMUM_SNAPSHOT_AGE` | no | no | Oldest accepted Agent snapshot; default `5m` |
| `HOMEOPS_AGENT_ALLOWED_FUTURE_SKEW` | no | no | Accepted clock skew; default `1m` |
| `HOMEOPS_AGENT_MAXIMUM_CONTAINERS` | no | no | Snapshot bound; default `128`, hard maximum `256` in the Agent |
| `HOMEOPS_AGENT_PROCESSED_SNAPSHOT_RETENTION` | no | no | Durable idempotency ledger retention; default `1d`, must exceed maximum snapshot age |
| `HOMEOPS_AGENT_PROCESSED_SNAPSHOT_CLEANUP_CRON` | no | no | Idempotency ledger cleanup cron in UTC; default `0 47 3 * * *` |
| `HOMEOPS_METRIC_RETENTION` | no | no | One-minute aggregate retention; default `30d`, maximum `365d` |
| `HOMEOPS_METRIC_CLEANUP_CRON` | no | no | UTC cleanup cron; default `0 17 3 * * *` |
| `HOMEOPS_INGESTION_SHARED_SECRET` | Phase 3 integration | yes | Shared HMAC secret for trusted deployment/backup-result scripts; blank disables ingestion (fail closed) |
| `HOMEOPS_INGESTION_MAXIMUM_REQUEST_AGE` | no | no | Oldest accepted signed request; default `5m` |
| `HOMEOPS_INGESTION_ALLOWED_FUTURE_SKEW` | no | no | Accepted sender clock skew; default `1m` |
| `HOMEOPS_MONITORING_ALLOWED_ORIGINS` | Phase 3 checks | private | Comma-separated exact HTTPS origins allowed for service checks; blank disables registration |
| `HOMEOPS_HEALTHY_RESULT_RETENTION` | no | no | Healthy check retention; default `7d` |
| `HOMEOPS_FAILURE_RESULT_RETENTION` | no | no | Failed check retention; default `30d` |
| `HOMEOPS_MONITORING_SCHEDULER_DELAY` | no | no | Due-service scan delay; default `5s` |
| `HOMEOPS_MONITORING_CLEANUP_CRON` | no | no | Check-result cleanup cron in UTC |

`HOMEOPS_AUTH_MODE=DEV` is accepted only with the Spring `dev` profile. Never set it in a production environment.

## Deployment and backup ingestion

The ingestion endpoints are intentionally disabled while `HOMEOPS_INGESTION_SHARED_SECRET` is blank. When a later integration step enables them, the trusted caller sends a compact JSON request to either `POST /api/v1/internal/ingestion/deployments` or `POST /api/v1/internal/ingestion/backups` with:

- `X-HomeOps-Ingestion-Timestamp`: an ISO-8601 UTC instant.
- `X-HomeOps-Ingestion-Signature`: lowercase hexadecimal HMAC-SHA-256 of `timestamp + "." + raw-request-body`, using the shared secret.

The API accepts only requests inside the configured time window. Never put the secret in a command line, repository variable, shell trace, deployment output, or the event payload. An event key identifies one deployment or backup lifecycle: the exact retry is accepted as a duplicate, a valid active-state transition updates the event, and a conflicting or terminal-state change is rejected. Backup `logicalLocation` is a logical identifier, never an absolute host path.

The bundled host reporter is `runtime-config/current/scripts/report-homeops-event.py`. It reads `HOMEOPS_INGESTION_SHARED_SECRET` from the mode-`0600` HomeOps `.env` and the HTTPS origin from the existing mode-`0600` `smoke.origin`; use a generated 64-character lowercase hexadecimal secret so the reporter and API share one value without a second secret file. It derives its HomeOps paths from the current macOS account, then writes a mode-`0600` event under `~/Server/data/homeops/ingestion-spool`, serializes drains with a file lock, limits each drain, and refuses redirects. Transient failures remain in the spool for retry; malformed entries and permanent API client rejections move to its mode-`0700` `quarantine` subdirectory so they do not block later events. The integration protocol does not pass the secret to a caller, but this is not an isolation boundary against another process already compromised under the same macOS account.

## Service check boundary

Service checks are fail-closed until `HOMEOPS_MONITORING_ALLOWED_ORIGINS` contains one or more exact HTTPS origins. Include an explicit non-default port when the service uses one, for example `https://homeops.example.ts.net:9443`. Paths and query parameters belong to each monitored service URL, not to the origin allowlist. User info and URL fragments are rejected, redirects are not followed, and each request uses the configured timeout. This exact-origin policy prevents an authenticated settings request from turning HomeOps into a general-purpose network client.

## Native Agent environment

| Key | Required | Secret | Constraint |
|---|---:|---:|---|
| `HOMEOPS_AGENT_ID` | yes | no | Must exactly match API configuration |
| `HOMEOPS_AGENT_API_URL` | yes | sensitive endpoint | HTTPS loopback URL with the fixed snapshot path; no user info, query, or fragment |
| `HOMEOPS_AGENT_CLIENT_CERT` | yes | sensitive path | Absolute client certificate path |
| `HOMEOPS_AGENT_CLIENT_KEY` | yes | yes/path | Absolute private key path |
| `HOMEOPS_AGENT_CA_CERT` | yes | sensitive path | Absolute server CA path |
| `HOMEOPS_DOCKER_SOCKET` | yes | sensitive path | Absolute active Unix socket ending in `docker.sock` |
| `HOMEOPS_AGENT_SPOOL_DIR` | yes | sensitive path | Absolute operator-owned spool directory |
| `HOMEOPS_AGENT_INTERVAL` | no | no | `5s` to `5m`; default `5s` |
| `HOMEOPS_AGENT_MAXIMUM_CONTAINERS` | no | no | `1` to `256`; default `128` |
| `HOMEOPS_AGENT_MAXIMUM_SPOOL_FILES` | no | no | `1` to `1440`; default `120` |
| `HOMEOPS_AGENT_VERSION_PROOF_FILE` | rollout host | sensitive path | Absolute, mode `0600` proof written only after a successful snapshot delivery |

The Agent refuses to evict an undelivered snapshot when the spool is full. It reports the failure and preserves existing evidence.

## TLS file names expected by Nginx

The directory mounted at `/run/homeops/tls` must contain:

- `server.crt`
- `server.key`
- `ca.crt`, the CA used to verify the Agent client certificate

The Agent may use a different CA file name on the host, but it must trust the issuer of `server.crt`.

The production directory also contains `smoke.origin`, a single-line tailnet HTTPS origin with no path, query, fragment, credentials, or trailing slash. A decimal port from 1 through 65535 may follow the DNS host, for example `:9443`; ports with leading zeroes are rejected. It is read only by the deployment worker so `current` is not advanced before root, representative asset, and readiness checks pass. Treat the host name as private operational metadata.

## Container labels

The read-only milestone reads only these labels:

- `com.docker.compose.project`
- `homeops.managed`

`homeops.managed=true` is display-only in this milestone. No control endpoint exists. A later control milestone must re-read the live label and enforce an additional project allowlist, operation lock, idempotency key, confirmation policy, and audit record.

## GitHub repository configuration

Automatic deployment is disabled unless the repository variable `MAC_MINI_DEPLOY_ENABLED` is exactly `true`.

Repository variable:

- `MAC_MINI_DEPLOY_ENABLED`
- `HOMEOPS_AGENT_ROLLOUT_ENABLED`: must be exactly `true` before the separate Agent rollout job can mutate the host; leave unset or `false` until a staging and rollback drill succeeds.

Production environment variables:

- `HOMEOPS_DEPLOY_HOST`, a MagicDNS host name
- `HOMEOPS_DEPLOY_USER`, the restricted SSH account

Production environment secrets:

- `TS_OAUTH_CLIENT_ID`
- `TS_AUDIENCE`
- `HOME_MINI_SSH_KEY`, a HomeOps-specific CI key
- `HOME_MINI_KNOWN_HOSTS`
- `HOMEOPS_SMOKE_URL`, the tailnet HTTPS origin without a path; it may use the same explicit port as `smoke.origin`
- `HOMEOPS_AGENT_ROLLOUT_SSH_KEY`, a distinct forced-command key limited to `rollout-homeops-agent-v1`
- `HOMEOPS_AGENT_ROLLOUT_KNOWN_HOSTS`, the known-host entry used only by the Agent rollout key

GitHub Actions supplies `GITHUB_TOKEN` automatically for the workflow's scoped package access. Do not create or store a separate `GITHUB_TOKEN` repository or environment secret.

Use a tagged Tailscale OAuth client with the narrowest grants. Protect the Production environment. Do not use a human SSH private key in Actions.

## Secret handling

- Keep production `.env` and TLS material mode-restricted and outside the source checkout.
- Never put secret values in Compose labels, GitHub variables, command arguments, logs, issue bodies, or deployment state.
- Store a Discord webhook and SMTP credential only when those later notification adapters are implemented. They are not part of the current read-only runtime.
- Rotate a credential if its value appears in Git history or a workflow log; deleting the visible line is not sufficient.
