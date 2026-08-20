# 보안 설계

## 보안 목표

- Tailnet 전용 단일 관리자 브라우저 접근
- 공개 상태 페이지와 Tailscale Funnel 미사용
- Web, API, database container에 Docker socket 미사용
- 브라우저에서 임의 command, path, image, Compose project, Docker argument 미수용
- 명시적인 identity 및 Agent certificate 검증
- 범위가 제한된 payload, response, spool, container 수
- public repository 또는 일반 log에 secret 값 미기록
- private deployment metadata를 GitHub Secret masking boundary 밖에 노출하지 않음

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
| XSS | 낮음/중간 | 높음 | React text escaping, 제한적 CSP, log message의 plain-text rendering, `dangerouslySetInnerHTML` 미사용 | 승인된 log 자체에 공격적인 text나 운영 metadata가 포함될 수 있음 |
| container log의 secret | 중간 | 높음 | fresh capability와 exact opt-in, bounded tail, Agent/API 이중 redaction, no-store와 ephemeral UI, payload 미저장 | regex redaction은 모든 민감값 제거를 보장하지 않으며 service 자체가 예상 밖 형식으로 secret을 log에 남길 수 있음 |
| container/image name injection | 현재 낮음 | control에서는 매우 높음 | 값은 표시 전용, shell interpolation 또는 control endpoint 없음 | 이후 control은 live ID와 고정 SDK call만 사용해야 함 |
| app network의 악성 container | 낮음 | 높음 | 전용 internal network, HomeOps service만 연결 | 침해된 Web container가 API에 접근할 수 있어 proxy hardening이 중요 |
| PostgreSQL credential 유출 | 낮음 | 높음 | private `.env`, internal DB port, 전용 role/database | host account 침해 뒤 volume과 history가 노출됨 |
| backup path 공개 | 낮음 | 중간 | bounded relative logical identifier만 허용하고 absolute·traversal·invalid identifier는 fail closed | 운영자가 선택한 logical identifier 자체에는 민감한 이름을 넣지 않아야 함 |
| 위조 deploy request | 낮음 | 매우 높음 | Tailscale OIDC, 별도 CI key, forced command grammar, 정확한 SHA/digest, stdin의 GHCR token | 침해된 GitHub production secret이 승인된 package name을 배포할 수 있음 |
| 위조 history ingestion | 낮음 | 높음 | bounded body·timestamp HMAC-SHA-256, fail-closed secret configuration, event key idempotency 및 state transition | 전용 ingestion secret을 가진 caller는 rotate 전까지 false history를 제출할 수 있음 |
| 반복 restart API | 현재 없음 | 향후 높음 | control 제외 | 구현 전 rate limit, idempotency, lock, audit, confirmation 필요 |
| HomeOps outage | 중간 | 중간 | Kuma 독립 유지, stale UI, health check | HomeOps가 다운되면 스스로 alert할 수 없음 |
| 전체 Mac outage | 중간 | 높음 | optional external heartbeat | 같은 host의 component는 전체 power/network loss를 보고할 수 없음 |
| Discord webhook 유출 또는 arbitrary outbound | 낮음/중간 | 높음 | Secret은 environment에만 유지, official HTTPS host/path allowlist, no redirect/query/userinfo/custom port, disabled-by-default kill switch | API process 또는 host account 침해 시 in-memory credential과 outbound capability가 노출될 수 있음 |

## Docker API 경계

Agent는 명시적으로 구성한 Unix socket을 통해 version discovery, snapshot용 container listing/inspect/stats와 opt-in log 작업용 lightweight list, 선택 container TTY inspect, bounded logs read만 구현합니다. allowlist 구조로 decode한 뒤 raw response를 버립니다. API에 Docker proxy endpoint를 노출하지 않습니다. mTLS ingress도 bounded request, 짧은 proxy timeout, source별 작은 burst/rate limit을 강제합니다.

Phase 5의 현재 source는 mutation이 아닌 candidate authority만 추가합니다. Agent는 exact `homeops.managed=true`를 boolean으로만 전달하고 Backend는 fresh latest snapshot, unique bounded short ID와 default-empty exact Compose project allowlist를 함께 요구합니다. `homeops`, standalone/blank/unknown project는 hard deny하며 denial은 stable code만 유지합니다. Full Docker ID, raw labels, image/registry와 allowlist 전체를 public API, error, log 또는 persistence에 노출하지 않습니다. Snapshot authority만으로 Docker를 변경하지 않으며 후속 protocol의 live label/project revalidation, database protection, operation lock, idempotency, audit와 confirmation 전에는 start/stop/restart가 없습니다.

읽기 접근도 운영 metadata를 드러낼 수 있습니다. Agent binary와 configuration은 privileged로 다루세요. root로 실행하거나 `sudo`를 제공하지 말고, spool을 container에 mount하거나 임의 TCP Docker endpoint를 구성하지 마세요.

## 브라우저 및 PWA 통제

- API response에는 `Cache-Control: no-store`가 있습니다.
- 브라우저 read API의 ADMIN session과 reporter ingestion 인증을 분리합니다. Ingestion은 bounded body와 timestamp window의 HMAC-SHA-256을 요구하고 missing·wrong·malformed signature를 controller 전에 거부하며 raw signature나 request body를 error response에 반사하지 않습니다.
- Activity는 deployment, backup, incident와 Agent event의 bounded allowlist context만 노출하며 visibility-snapshot cursor를 사용하는 no-store pagination을 제공합니다.
- Workbox는 API GET request에 `NetworkOnly`를 사용하며 상태 변경 method는 cache하지 않습니다.
- Container Logs는 explicit user action에만 요청하고 UI memory에만 잠시 보관합니다. route, tail 또는 disclosure authority가 바뀌면 표시 payload를 제거합니다.
- offline data를 current로 표시하지 않으며 현 마일스톤에는 control button이 없습니다.
- PWA shell은 offline으로 load할 수 있지만 운영 data에는 network와 tailnet이 필요합니다.
- Nginx는 제한적인 CSP, frame denial, no-sniff, referrer, permission header를 설정합니다.

## 배포 경계

CI key는 stable bootstrap으로 강제되어야 합니다. v2 bootstrap은 full commit SHA, API digest, Web digest, runtime-config digest, registry owner, registry login identity만 받습니다. zero 또는 malformed digest를 거부하고 runtime image label과 추출한 release shape를 검증하며 application digest를 immutable deployment worker에 전달합니다. worker는 candidate와 rollback image를 digest로 pull하고 cutover 전에 revision label을 검증합니다. 어느 layer도 path, image name, project name, Compose subcommand, shell fragment를 받지 않습니다.

`HOMEOPS_DEPLOY_HOST`와 `HOMEOPS_DEPLOY_USER`는 credential은 아니지만 private deployment metadata입니다. 현재 workflow는 두 값을 Production environment Secret에서만 받아 Tailscale ping과 restricted SSH target에 사용하며, same-name Variable은 두지 않습니다. Secret 기반 production deployment acceptance에서 Tailscale ping input이 masking되고 public log의 literal target metadata가 남지 않음을 확인했습니다.

Secret migration 이전의 public workflow log에는 historical deployment-target metadata가 남아 있을 수 있습니다. 이 residual exposure에 credential이 포함됐다는 evidence나 현재 credential compromise evidence는 없지만, 기존 run 삭제 여부는 보존 영향과 별도 destructive 승인을 요구합니다. 실제 host/account 값을 issue, 문서 또는 cleanup 계획에 다시 기록하지 마세요.

예시는 운영자 검토와 staging이 여전히 필요합니다. live SSH key, file owner/mode, Tailscale grant, production directory가 올바르게 구성되었다는 증거가 아닙니다.

## Discord notification 경계

Notification outbox는 typed allowlist payload만 JSONB로 저장하며 webhook URL/token, raw response, raw exception, monitored raw URL, host/Tailnet metadata, labels, logs, filesystem path 또는 full Docker ID를 저장하지 않습니다. Discord message는 content·attachment·image·URL·username/avatar override 없이 embed 하나와 최대 6개 field만 사용하고 `allowed_mentions.parse=[]`를 강제합니다. Webhook response는 bounded read 뒤 폐기합니다.

Deployment와 backup producer는 신뢰하는 HMAC ingestion의 실제 insert 또는 terminal transition winner만 같은 transaction에서 outbox에 연결합니다. Deployment payload에는 project, environment, 12자리 commit identity와 bounded status만 포함하며 raw failure summary, actor, workflow URL, image tag 또는 private deployment metadata를 저장하지 않습니다. Backup payload에는 project, database type, status만 포함하며 logical location, failure summary, expiry, restore-test metadata 또는 private path를 저장하지 않습니다. Incident producer는 persisted service authority와 future incident winner만 사용하고 logical service name, severity, lifecycle status와 bounded duration만 저장합니다. Agent producer는 persisted expected Agent row와 actual current snapshot winner만 사용하고 logical Agent ID, bounded version, lifecycle status와 bounded duration만 저장합니다. Host/Tailnet identity, certificate, Docker state, raw snapshot, metric, path, log와 credential은 Agent notification payload에 넣지 않습니다. Incident와 Agent recovery는 각 SENT root가 없으면 fail closed합니다. Producer는 Discord transport를 직접 호출하지 않습니다.

Docker notification capability는 Agent가 list response의 exact `homeops.notifications=true`만 boolean으로 축소해 내부 snapshot에 전달합니다. Raw label map, inspect document, environment, mount/network/path는 Backend, public container API, DB 또는 log에 전달하지 않습니다. 이 boolean은 managed/control 및 logs disclosure authority와 독립적이며 public inventory/detail 응답에도 노출하지 않습니다. Backend current state는 bounded project/name logical hash와 full Docker ID의 one-way instance fingerprint만 사용합니다. Discord payload에는 logical display name, optional project, bounded state/health/lifecycle/duration만 허용하며 Docker ID/fingerprint, image/registry, raw status, label, port, metric 또는 private host metadata를 넣지 않습니다. Duplicate/out-of-order/equal/stale snapshot, missing container, recreate와 authority revoke는 notification recovery authority가 아닙니다. Production acceptance 완료는 future container opt-in이나 outbound enable 권한을 만들지 않습니다.

Agent freshness notification은 HomeOps API가 살아 있는 동안 native Agent 또는 snapshot pipeline이 stale해지는 내부 신호입니다. API와 Agent가 같은 Mac에서 실행되므로 Mac 전체 outage나 외부 reachability를 보장하지 않으며, 해당 경계는 Uptime Kuma가 계속 소유합니다.

`HOMEOPS_NOTIFICATIONS_ENABLED=false`에서는 webhook이 없어도 application이 시작하고 outbound를 수행하지 않습니다. 반대로 enabled 상태에서 missing/invalid webhook은 startup을 fail closed합니다. Production acceptance에서 typed representative payload와 channel visibility를 검토했고 webhook Secret은 값 비노출 상태로 설치했지만, 종료 뒤 switch를 다시 `false`로 두었습니다. Regex와 allowlist는 credential-free delivery를 보장하지 않으므로 새 producer, payload field 또는 channel 변경에는 별도 privacy review가 필요합니다.

Service별 `notification_enabled`는 Discord Secret이나 activation switch가 아니라 future incident eligibility입니다. Existing service의 변경 endpoint는 ADMIN session과 CSRF를 요구하고 service ID와 boolean만 받으며, unknown service field를 fail closed합니다. 응답과 오류에는 monitored raw URL, incident payload 또는 credential을 추가하지 않습니다. Toggle만으로 outbox row, historical replay 또는 outbound를 만들지 않습니다.

## 취약점 제보

`SECURITY.md`에 설명한 비공개 GitHub security advisory 채널을 사용하세요. credential, private host name, Tailnet map, 개인 login, certificate, environment dump, raw service log를 공개 report에 포함하지 마세요.
