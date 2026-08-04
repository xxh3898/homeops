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

`HOMEOPS_AUTH_MODE=DEV` is accepted only with the Spring `dev` profile. Never set it in a production environment.

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

The Agent refuses to evict an undelivered snapshot when the spool is full. It reports the failure and preserves existing evidence.

## TLS file names expected by Nginx

The directory mounted at `/run/homeops/tls` must contain:

- `server.crt`
- `server.key`
- `ca.crt`, the CA used to verify the Agent client certificate

The Agent may use a different CA file name on the host, but it must trust the issuer of `server.crt`.

The production directory also contains `smoke.origin`, a single-line tailnet HTTPS origin with no path. It is read only by the deployment worker so `current` is not advanced before root, representative asset, and readiness checks pass. Treat the host name as private operational metadata.

## Container labels

The read-only milestone reads only these labels:

- `com.docker.compose.project`
- `homeops.managed`

`homeops.managed=true` is display-only in this milestone. No control endpoint exists. A later control milestone must re-read the live label and enforce an additional project allowlist, operation lock, idempotency key, confirmation policy, and audit record.

## GitHub repository configuration

Automatic deployment is disabled unless the repository variable `MAC_MINI_DEPLOY_ENABLED` is exactly `true`.

Repository or Production environment variables:

- `MAC_MINI_DEPLOY_ENABLED`
- `HOMEOPS_DEPLOY_HOST`, a MagicDNS host name
- `HOMEOPS_DEPLOY_USER`, the restricted SSH account

Secrets:

- `TS_OAUTH_CLIENT_ID`
- `TS_AUDIENCE`
- `HOME_MINI_SSH_KEY`, a HomeOps-specific CI key
- `HOME_MINI_KNOWN_HOSTS`
- `HOMEOPS_SMOKE_URL`, the tailnet HTTPS origin without a path

Use a tagged Tailscale OAuth client with the narrowest grants. Protect the Production environment. Do not use a human SSH private key in Actions.

## Secret handling

- Keep production `.env` and TLS material mode-restricted and outside the source checkout.
- Never put secret values in Compose labels, GitHub variables, command arguments, logs, issue bodies, or deployment state.
- Store a Discord webhook and SMTP credential only when those later notification adapters are implemented. They are not part of the current read-only runtime.
- Rotate a credential if its value appears in Git history or a workflow log; deleting the visible line is not sufficient.
