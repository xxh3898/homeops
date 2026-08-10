# 보안 설계

## 보안 목표

- Tailnet 전용 단일 관리자 브라우저 접근
- 공개 상태 페이지와 Tailscale Funnel 미사용
- Web, API, database container에 Docker socket 미사용
- 브라우저에서 임의 command, path, image, Compose project, Docker argument 미수용
- 명시적인 identity 및 Agent certificate 검증
- 범위가 제한된 payload, response, spool, container 수
- public repository 또는 일반 log에 secret 값 미기록

## 위협 모델

| 위협 | 가능성 | 영향 | 주요 통제 | 잔여 위험 |
|---|---|---|---|---|
| Tailnet account 침해 | 낮음/중간 | 높음 | Tailnet MFA, 좁은 grant, 정확한 login allowlist, session cookie | 승인된 identity는 모든 dashboard data를 볼 수 있음 |
| 잠금 해제된 iPhone 분실 | 중간 | 높음 | device passcode/biometric, Tailscale device revoke, 짧은 운영 대응 | 기존 PWA session은 revoke 또는 expiry 전까지 유효할 수 있음 |
| local header spoofing | 낮음 | 높음 | Web port loopback 전용, API internal network, LAN bind 없음 | 침해된 local macOS account가 Serve header를 가장할 수 있음 |
| Docker socket 침해 | 낮음 | 매우 높음 | socket은 native Agent에만 제공, inbound Agent API 없음, 고정 GET endpoint | Agent process는 OS 경계에서 여전히 Docker Engine과 동등한 read/control potential을 가짐 |
| Agent client key 탈취 | 낮음 | 높음 | `0600` key, 운영자 전용 directory, mTLS, 정확한 Agent ID, payload limit | 탈취한 key는 같은 Mac account context에서 false snapshot을 제출할 수 있음 |
| API 취약점 | 중간 | 높음 | same-origin, Spring Security, validation, no-store, 최소 endpoint, dependency scanning | 알려지지 않은 framework 또는 application defect 가능 |
| API outbound egress 악용 | 낮음/중간 | 중간/높음 | API 전용 `egress` bridge, 정확한 HTTPS origin allowlist, 일반 request endpoint 없음 | Docker network name은 방향을 강제하지 않으므로 침해된 API process는 outbound connection을 시작할 수 있음 |
| Web container outbound 악용 | 낮음/중간 | 중간 | loopback 전용 host publish, static-file/Nginx proxy 전용 Web image, 일반 outbound 기능 없음 | Docker Desktop host-published port에는 non-internal `ingress` bridge가 필요하므로 Docker가 outbound 차단을 제공하지 않음. 더 강한 격리에는 host firewall, proxy, edge network policy 필요 |
| CSRF | 현재 낮음 | 향후 높음 | SameSite session, CSRF token contract, 현 마일스톤 mutation API 없음 | 이후 control endpoint에는 명시적 CSRF 및 Origin test 필요 |
| XSS | 낮음/중간 | 높음 | React escaping, 제한적 CSP, raw log rendering 없음, HTML injection 없음 | 취약한 향후 log/UI component가 visible data를 탈취할 수 있음 |
| container log의 secret | 중간 | 높음 | env/inspect persistence 없음, 안전한 error summary, 범위 제한 structured field | 기존 managed service 자체가 secret을 log에 남길 수 있음 |
| container/image name injection | 현재 낮음 | control에서는 매우 높음 | 값은 표시 전용, shell interpolation 또는 control endpoint 없음 | 이후 control은 live ID와 고정 SDK call만 사용해야 함 |
| app network의 악성 container | 낮음 | 높음 | 전용 internal network, HomeOps service만 연결 | 침해된 Web container가 API에 접근할 수 있어 proxy hardening이 중요 |
| PostgreSQL credential 유출 | 낮음 | 높음 | private `.env`, internal DB port, 전용 role/database | host account 침해 뒤 volume과 history가 노출됨 |
| backup path 공개 | 낮음 | 중간 | metadata에는 logical identifier만 허용 | 이후 ingestion은 raw private path를 거부해야 함 |
| 위조 deploy request | 낮음 | 매우 높음 | Tailscale OIDC, 별도 CI key, forced command grammar, 정확한 SHA/digest, stdin의 GHCR token | 침해된 GitHub production secret이 승인된 package name을 배포할 수 있음 |
| 위조 history ingestion | 낮음 | 높음 | raw JSON의 범위 제한 시간 HMAC, fail-closed secret configuration, event key idempotency 및 state transition | 전용 ingestion secret을 가진 caller는 rotate 전까지 false history를 제출할 수 있음 |
| 반복 restart API | 현재 없음 | 향후 높음 | control 제외 | 구현 전 rate limit, idempotency, lock, audit, confirmation 필요 |
| HomeOps outage | 중간 | 중간 | Kuma 독립 유지, stale UI, health check | HomeOps가 다운되면 스스로 alert할 수 없음 |
| 전체 Mac outage | 중간 | 높음 | optional external heartbeat | 같은 host의 component는 전체 power/network loss를 보고할 수 없음 |

## Docker API 경계

Agent는 명시적으로 구성한 Unix socket을 통해 version discovery, container listing, container별 inspect만 구현합니다. allowlist 구조로 decode한 뒤 raw response를 버립니다. API에 Docker proxy endpoint를 노출하지 않습니다. mTLS ingress도 512 KiB request bound, 짧은 proxy timeout, source별 작은 burst/rate limit을 강제합니다.

읽기 접근도 운영 metadata를 드러낼 수 있습니다. Agent binary와 configuration은 privileged로 다루세요. root로 실행하거나 `sudo`를 제공하지 말고, spool을 container에 mount하거나 임의 TCP Docker endpoint를 구성하지 마세요.

## 브라우저 및 PWA 통제

- API response에는 `Cache-Control: no-store`가 있습니다.
- Workbox는 API GET request에 `NetworkOnly`를 사용하며 상태 변경 method는 cache하지 않습니다.
- offline data를 current로 표시하지 않으며 현 마일스톤에는 control button이 없습니다.
- PWA shell은 offline으로 load할 수 있지만 운영 data에는 network와 tailnet이 필요합니다.
- Nginx는 제한적인 CSP, frame denial, no-sniff, referrer, permission header를 설정합니다.

## 배포 경계

CI key는 stable bootstrap으로 강제되어야 합니다. v2 bootstrap은 full commit SHA, API digest, Web digest, runtime-config digest, registry owner, registry login identity만 받습니다. zero 또는 malformed digest를 거부하고 runtime image label과 추출한 release shape를 검증하며 application digest를 immutable deployment worker에 전달합니다. worker는 candidate와 rollback image를 digest로 pull하고 cutover 전에 revision label을 검증합니다. 어느 layer도 path, image name, project name, Compose subcommand, shell fragment를 받지 않습니다.

예시는 운영자 검토와 staging이 여전히 필요합니다. live SSH key, file owner/mode, Tailscale grant, production directory가 올바르게 구성되었다는 증거가 아닙니다.

## 취약점 제보

`SECURITY.md`에 설명한 비공개 GitHub security advisory 채널을 사용하세요. credential, private host name, Tailnet map, 개인 login, certificate, environment dump, raw service log를 공개 report에 포함하지 마세요.
