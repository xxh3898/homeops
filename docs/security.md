# Security design

## Security goals

- Tailnet-only, single-administrator browser access
- No public status page and no Tailscale Funnel
- No Docker socket in the Web, API, or database containers
- No arbitrary command, path, image, Compose project, or Docker argument from the browser
- Explicit identity and Agent certificate verification
- Bounded payloads, responses, spool, and container count
- No secret values in the public repository or normal logs

## Threat model

| Threat | Likelihood | Impact | Primary controls | Residual risk |
|---|---|---|---|---|
| Tailnet account compromise | low/medium | high | Tailnet MFA, narrow grants, exact login allowlist, session cookies | An approved identity can view all dashboard data |
| Lost unlocked iPhone | medium | high | Device passcode/biometrics, Tailscale device revoke, short operational response | Existing PWA session may remain valid until revoked or expired |
| Local header spoofing | low | high | Web port loopback-only, API internal network, no LAN bind | A compromised local macOS account can impersonate Serve headers |
| Docker socket compromise | low | critical | Socket only in native Agent, no inbound Agent API, fixed GET endpoints | The Agent process still has Docker Engine-equivalent read/control potential at the OS boundary |
| Agent client key theft | low | high | `0600` key, operator-only directories, mTLS, exact Agent ID, payload limits | Stolen key can submit false snapshots from the same Mac account context |
| API vulnerability | medium | high | same-origin, Spring Security, validation, no-store, minimal endpoints, dependency scanning | Unknown framework or application defects remain possible |
| API outbound egress abuse | low/medium | medium/high | API-only `egress` bridge, exact HTTPS origin allowlist, no general request endpoint | Docker network names do not enforce direction; a compromised API process can still initiate outbound traffic |
| Web container outbound abuse | low/medium | medium | loopback-only host publish, static-file/Nginx proxy-only Web image, no general outbound feature | Web needs a non-internal `ingress` bridge for Docker Desktop host-published ports, so Docker does not provide outbound blocking; stronger isolation needs host firewall, proxy, or edge network policy |
| CSRF | low now | high for future control | SameSite session, CSRF token contract, no mutation API in current milestone | Future control endpoints require explicit CSRF and Origin tests |
| XSS | low/medium | high | React escaping, restrictive CSP, no raw log rendering, no HTML injection | A vulnerable future log/UI component could steal visible data |
| Secret in container logs | medium | high | no env/inspect persistence, safe error summaries, bounded structured fields | Existing managed services may themselves log secrets |
| Container/image name injection | low now | critical for control | values are display-only; no shell interpolation or control endpoint | Future control must use live IDs and fixed SDK calls only |
| Malicious container on app network | low | high | dedicated internal network; only HomeOps services join | A compromised Web container can reach the API, making proxy hardening essential |
| PostgreSQL credential leak | low | high | private `.env`, internal DB port, dedicated role/database | Volume and history are exposed after host account compromise |
| Backup path disclosure | low | medium | only logical identifiers belong in metadata | Future ingestion must reject raw private paths |
| Forged deploy request | low | critical | Tailscale OIDC, separate CI key, forced command grammar, exact SHA/digest, GHCR token on stdin | Compromised GitHub production secrets can deploy approved package names |
| Forged history ingestion | low | high | bounded-time HMAC over raw JSON, fail-closed secret configuration, event key idempotency and state transitions | A caller with the dedicated ingestion secret can submit false history until the secret is rotated |
| Repeated restart API | none now | high later | control excluded | Requires rate limit, idempotency, lock, audit, and confirmation before implementation |
| HomeOps outage | medium | medium | Kuma remains independent; stale UI; health checks | HomeOps cannot alert while it is down |
| Entire Mac outage | medium | high | optional external heartbeat | No same-host component can report total power/network loss |

## Docker API boundary

The Agent implements only version discovery, container listing, and per-container inspect over an explicitly configured Unix socket. It decodes an allowlisted structure and discards the raw response. It does not expose a Docker proxy endpoint to the API. The mTLS ingress also enforces a 512 KiB request bound, short proxy timeouts, and a small per-source burst/rate limit.

Even read access can reveal operational metadata. Treat the Agent binary and configuration as privileged. Do not run it as root, give it `sudo`, mount its spool into a container, or configure an arbitrary TCP Docker endpoint.

## Browser and PWA controls

- API responses carry `Cache-Control: no-store`.
- Workbox uses `NetworkOnly` for API GET requests; state-changing methods are not cached.
- Offline data is never presented as current, and the current milestone has no control button.
- The PWA shell can load offline, but operational data requires the network and tailnet.
- Nginx sets a restrictive CSP, frame denial, no-sniff, referrer, and permissions headers.

## Deployment boundary

The CI key must be forced to the stable bootstrap. The v2 bootstrap accepts only a full commit SHA, API digest, Web digest, runtime-config digest, registry owner, and registry login identity. It rejects zero or malformed digests, verifies the runtime image labels and extracted release shape, and passes the application digests to the immutable deployment worker. The worker pulls candidate and rollback images by digest and verifies their revision labels before cutover. Neither layer accepts a path, image name, project name, Compose subcommand, or shell fragment.

The examples still require operator review and staging. They are not proof that the live SSH key, file owner/mode, Tailscale grants, or production directory are correctly configured.

## Vulnerability reporting

Use the private GitHub security advisory channel described in `SECURITY.md`. Do not include a credential, private host name, Tailnet map, personal login, certificate, environment dump, or raw service log in a public report.
