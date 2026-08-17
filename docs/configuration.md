# 구성

모든 secret과 host별 값은 Git 밖에 둡니다. 예시 값에는 reserved domain 또는 replacement marker를 사용하며 운영 기본값이 아닙니다.

## 애플리케이션 환경

| 키 | 필수 여부 | secret | 용도 |
|---|---:|---:|---|
| `HOMEOPS_DB_NAME` | 예 | 아니요 | 전용 PostgreSQL 데이터베이스 이름 |
| `HOMEOPS_DB_USER` | 예 | 아니요 | 전용 PostgreSQL role |
| `HOMEOPS_DB_PASSWORD` | 예 | 예 | PostgreSQL 비밀번호 |
| `HOMEOPS_ALLOWED_USERS` | 예 | 개인 정보 | 쉼표로 구분한 정확한 Tailscale login allowlist |
| `HOMEOPS_AGENT_ID` | 예 | 아니요 | API가 기대하는 정확한 Agent identifier |
| `HOMEOPS_WEB_BIND` | production | 아니요 | loopback Web host binding |
| `HOMEOPS_AGENT_BIND` | production | 아니요 | loopback mTLS Agent binding |
| `HOMEOPS_TLS_DIR` | production | 민감 path | server certificate, key, Agent CA certificate가 있는 directory |
| `HOMEOPS_API_IMAGE` | 수동 실행 | 아니요 | 변경 불가능한 API image digest reference. image revision label은 release SHA와 일치해야 함 |
| `HOMEOPS_WEB_IMAGE` | 수동 실행 | 아니요 | 변경 불가능한 Web image digest reference. image revision label은 release SHA와 일치해야 함 |
| `HOMEOPS_DB_POOL_SIZE` | 아니요 | 아니요 | Hikari 최대 pool 크기. 기본값 `5` |
| `HOMEOPS_DB_MIN_IDLE` | 아니요 | 아니요 | Hikari 최소 idle. 기본값 `1` |
| `HOMEOPS_DB_MAX_CONNECTIONS` | 아니요 | 아니요 | PostgreSQL connection 상한. 기본값 `20` |
| `HOMEOPS_DB_SHARED_BUFFERS` | 아니요 | 아니요 | PostgreSQL shared buffer. 기본값 `128MB` |
| `HOMEOPS_DB_MEMORY_LIMIT` | 아니요 | 아니요 | PostgreSQL container memory limit. 기본값 `512m` |
| `HOMEOPS_API_MEMORY_LIMIT` | 아니요 | 아니요 | API container memory limit. 기본값 `640m` |
| `HOMEOPS_WEB_MEMORY_LIMIT` | 아니요 | 아니요 | Web container memory limit. 기본값 `64m` |
| `HOMEOPS_JAVA_TOOL_OPTIONS` | 아니요 | 아니요 | JVM memory option. 기본값 `-Xms128m -Xmx384m` |
| `HOMEOPS_AGENT_STALE_AFTER` | 아니요 | 아니요 | stale threshold. 양수, 최대 `30d`, 기본값 `30s` |
| `HOMEOPS_AGENT_MAXIMUM_SNAPSHOT_AGE` | 아니요 | 아니요 | 허용하는 가장 오래된 Agent snapshot. 양수이며 processed-snapshot retention보다 작아야 함. 기본값 `5m` |
| `HOMEOPS_AGENT_ALLOWED_FUTURE_SKEW` | 아니요 | 아니요 | 허용 clock skew. `0`부터 `15m`, 기본값 `1m` |
| `HOMEOPS_AGENT_MAXIMUM_CONTAINERS` | 아니요 | 아니요 | snapshot 상한. 기본값 `128`, Agent의 hard maximum `256` |
| `HOMEOPS_AGENT_PROCESSED_SNAPSHOT_RETENTION` | 아니요 | 아니요 | 영속 멱등성 ledger 보존 기간. 기본값 `1d`, 최대 snapshot age보다 길어야 함 |
| `HOMEOPS_AGENT_PROCESSED_SNAPSHOT_CLEANUP_CRON` | 아니요 | 아니요 | UTC 기준 멱등성 ledger cleanup cron. 기본값 `0 47 3 * * *` |
| `HOMEOPS_METRIC_RETENTION` | 아니요 | 아니요 | 1분 aggregate 보존 기간. 기본값 `30d`, 최대 `365d` |
| `HOMEOPS_METRIC_CLEANUP_CRON` | 아니요 | 아니요 | UTC 기준 cleanup cron. 기본값 `0 17 3 * * *` |
| `HOMEOPS_INGESTION_SHARED_SECRET` | Phase 3 통합 | 예 | 신뢰하는 deployment/backup-result script용 공유 HMAC secret. 비어 있으면 ingestion 비활성화(fail closed) |
| `HOMEOPS_INGESTION_MAXIMUM_REQUEST_AGE` | 아니요 | 아니요 | 허용하는 가장 오래된 signed request. 양수, 최대 `24h`, 기본값 `5m` |
| `HOMEOPS_INGESTION_ALLOWED_FUTURE_SKEW` | 아니요 | 아니요 | 허용 sender clock skew. `0`부터 `15m`, 기본값 `1m` |
| `HOMEOPS_MONITORING_ALLOWED_ORIGINS` | Phase 3 점검 | 비공개 | service check에 허용하는 쉼표 구분 정확한 HTTPS origin. 비어 있으면 등록 비활성화 |
| `HOMEOPS_HEALTHY_RESULT_RETENTION` | 아니요 | 아니요 | 정상 점검 결과 보존 기간. 기본값 `7d` |
| `HOMEOPS_FAILURE_RESULT_RETENTION` | 아니요 | 아니요 | 실패 점검 결과 보존 기간. 기본값 `30d` |
| `HOMEOPS_MONITORING_SCHEDULER_DELAY` | 아니요 | 아니요 | 점검 대상 service scan delay. 기본값 `5s` |
| `HOMEOPS_MONITORING_CLEANUP_CRON` | 아니요 | 아니요 | UTC 기준 점검 결과 cleanup cron |

`HOMEOPS_AUTH_MODE=DEV`는 Spring `dev` profile에서만 허용됩니다. production 환경에서는 절대 설정하지 마세요.

## 배포 및 백업 수집

`HOMEOPS_INGESTION_SHARED_SECRET`이 비어 있는 동안 ingestion endpoint는 의도적으로 비활성화됩니다. 현재 production은 secret과 reporter를 구성해 deployment/backup ingestion이 활성 상태지만, 새 설치와 미구성 환경의 fail-closed contract는 그대로 유지됩니다. 신뢰하는 caller는 `POST /api/v1/internal/ingestion/deployments` 또는 `POST /api/v1/internal/ingestion/backups`로 compact JSON request를 보내며 다음 header를 포함합니다.

- `X-HomeOps-Ingestion-Timestamp`: ISO-8601 UTC instant
- `X-HomeOps-Ingestion-Signature`: 공유 secret을 사용한 `timestamp + "." + raw-request-body`의 소문자 hexadecimal HMAC-SHA-256

API는 구성한 time window 안의 request만 받습니다. secret을 command line, repository variable, shell trace, deployment output, event payload에 넣지 마세요. event key는 하나의 deployment 또는 backup lifecycle을 식별합니다. 정확히 같은 retry는 duplicate로 허용하고, 유효한 active-state transition은 event를 갱신하며, 충돌하거나 terminal-state를 바꾸는 요청은 거부합니다. Backup `logicalLocation`은 logical identifier이며 absolute host path가 아닙니다.

포함된 host reporter는 `runtime-config/current/scripts/report-homeops-event.py`입니다. mode `0600` HomeOps `.env`에서 `HOMEOPS_INGESTION_SHARED_SECRET`을, 기존 mode `0600` `smoke.origin`에서 HTTPS origin을 읽습니다. reporter와 API가 두 번째 secret file 없이 같은 값을 공유하도록 생성한 64글자 소문자 hexadecimal secret을 사용하세요. 현재 macOS account에서 HomeOps path를 유도한 뒤 `~/Server/data/homeops/ingestion-spool`에 mode `0600` event를 기록하고 file lock으로 drain을 직렬화하며, 각 drain을 제한하고 redirect를 거부합니다. active, pending, quarantine을 합친 capacity는 128개입니다. 가득 찬 경우 기존 evidence를 보존하고 오래된 event를 지우는 대신 새 event를 거부합니다. transient failure는 retry를 위해 spool에 남고, malformed entry와 permanent API client rejection은 이후 event를 막지 않도록 mode `0700` `quarantine` subdirectory로 옮깁니다. 이후 deployment나 backup이 없을 때도 보존된 transient failure를 재시도할 수 있도록 별도 주기 LaunchAgent에서 event payload 없이 `--drain`을 호출하세요. 통합 protocol은 caller에게 secret을 전달하지 않지만 같은 macOS account에서 이미 침해된 다른 process에 대한 격리 경계는 아닙니다.

## 서비스 점검 경계

`HOMEOPS_MONITORING_ALLOWED_ORIGINS`에 하나 이상의 정확한 HTTPS origin이 들어가기 전까지 service check는 fail-closed입니다. 현재 production은 allowlist와 monitored service를 구성해 scheduler, check와 incident history가 활성 상태입니다. service가 non-default port를 쓰면 `https://homeops.example.ts.net:9443`처럼 명시적으로 포함하세요. path와 query parameter는 origin allowlist가 아니라 각 monitored service URL에 속합니다. user info와 URL fragment는 거부하고 redirect를 따르지 않으며 각 request는 설정한 timeout을 사용합니다. 이 exact-origin policy는 인증된 settings request가 HomeOps를 범용 network client로 바꾸는 일을 막습니다.

## native Agent 환경

| 키 | 필수 여부 | secret | 제약 |
|---|---:|---:|---|
| `HOMEOPS_AGENT_ID` | 예 | 아니요 | API configuration과 정확히 일치해야 함 |
| `HOMEOPS_AGENT_API_URL` | 예 | 민감 endpoint | 고정 snapshot path를 포함하는 HTTPS loopback URL. user info, query, fragment 불가. Agent는 검증된 scheme/host/port에서 compile-time fixed log work/result path를 파생하며 별도 endpoint 환경변수를 받지 않음 |
| `HOMEOPS_AGENT_CLIENT_CERT` | 예 | 민감 path | absolute client certificate path |
| `HOMEOPS_AGENT_CLIENT_KEY` | 예 | 예/path | absolute private key path |
| `HOMEOPS_AGENT_CA_CERT` | 예 | 민감 path | absolute server CA path |
| `HOMEOPS_DOCKER_SOCKET` | 예 | 민감 path | `docker.sock`으로 끝나는 absolute active Unix socket |
| `HOMEOPS_AGENT_SPOOL_DIR` | 예 | 민감 path | operator가 소유한 absolute spool directory |
| `HOMEOPS_AGENT_INTERVAL` | 아니요 | 아니요 | `5s`부터 `5m`, 기본값 `5s` |
| `HOMEOPS_AGENT_MAXIMUM_CONTAINERS` | 아니요 | 아니요 | `1`부터 `256`, 기본값 `128` |
| `HOMEOPS_AGENT_MAXIMUM_SPOOL_FILES` | 아니요 | 아니요 | `1`부터 `1440`, 기본값 `120` |
| `HOMEOPS_AGENT_VERSION_PROOF_FILE` | rollout host | 민감 path | 성공한 snapshot 전달 뒤에만 기록하는 absolute mode `0600` proof |

Agent는 spool이 가득 차도 아직 전달하지 못한 snapshot을 제거하지 않습니다. 실패를 보고하고 기존 evidence를 보존합니다.

## Nginx가 기대하는 TLS 파일명

`/run/homeops/tls`에 mount하는 directory에는 다음이 있어야 합니다.

- `server.crt`
- `server.key`
- `ca.crt`: Agent client certificate를 검증하는 CA

Agent는 host에서 다른 CA file name을 쓸 수 있지만 `server.crt`의 issuer를 신뢰해야 합니다.

production directory에는 `smoke.origin`도 있습니다. path, query, fragment, credential, trailing slash가 없는 단일 줄 tailnet HTTPS origin입니다. DNS host 뒤에는 `:9443`처럼 1부터 65535까지 decimal port를 붙일 수 있으나 leading zero가 있는 port는 거부됩니다. deployment worker만 이를 읽어 root, representative asset, readiness check가 통과하기 전에 `current`가 진행되지 않도록 합니다. host name은 비공개 운영 metadata로 다루세요.

## 컨테이너 라벨

읽기 전용 마일스톤은 다음 label만 읽습니다.

- `com.docker.compose.project`
- `homeops.managed`
- `homeops.logs`

`homeops.managed=true`는 이 마일스톤에서 표시용입니다. control endpoint는 없습니다. 이후 control 마일스톤에서는 live label을 다시 읽고 추가 project allowlist, operation lock, idempotency key, confirmation policy, audit record를 강제해야 합니다.

`homeops.logs=true`는 future Container Logs disclosure를 위한 container별 exact opt-in입니다. Agent root capability와 fresh snapshot이 함께 있어야 하며 stale snapshot은 authority가 아닙니다. 현재 foundation PR에는 관리자용 public log API/UI가 없으므로 이 label만으로 기능이 활성화되지는 않습니다. Agent는 raw log를 snapshot spool, file 또는 DB에 저장하지 않습니다.

## GitHub repository 구성

repository variable `MAC_MINI_DEPLOY_ENABLED`가 정확히 `true`가 아니면 자동 배포는 비활성입니다.

Repository variable:

- `MAC_MINI_DEPLOY_ENABLED`: 현재 `true`이며 reviewed `main`의 application deploy를 활성화합니다.
- `HOMEOPS_AGENT_ROLLOUT_ENABLED`: 별도 Agent rollout job이 host를 변경하려면 정확히 `true`여야 합니다. Phase 2 operational acceptance는 COMPLETE지만 현재 값은 `false`이며 automatic CI mutation을 닫아 둔 kill switch입니다.

Rollout job의 `if`는 repository Variable을 effective gate로 사용합니다. 같은 이름의 Production environment Variable을 만들지 마세요.

Production environment secret:

- `HOMEOPS_DEPLOY_HOST`: MagicDNS host name
- `HOMEOPS_DEPLOY_USER`: 제한된 SSH account
- `TS_OAUTH_CLIENT_ID`
- `TS_AUDIENCE`
- `HOME_MINI_SSH_KEY`: HomeOps 전용 CI key
- `HOME_MINI_KNOWN_HOSTS`
- `HOMEOPS_SMOKE_URL`: path 없는 tailnet HTTPS origin. `smoke.origin`과 같은 명시 port를 쓸 수 있음
- `HOMEOPS_AGENT_ROLLOUT_SSH_KEY`: `rollout-homeops-agent-v1`로 제한된 별도 forced-command key
- `HOMEOPS_AGENT_ROLLOUT_KNOWN_HOSTS`: Agent rollout key에만 쓰는 known-host entry

`HOMEOPS_DEPLOY_HOST`와 `HOMEOPS_DEPLOY_USER`는 credential 자체는 아니지만 private deployment metadata입니다. Production environment Secret으로만 관리하고 workflow에서 GitHub Variable로 참조하지 마세요. Secret 기반 production deployment와 public log literal zero-match를 확인한 뒤 같은 이름의 legacy repository/environment Variable을 제거했습니다. 이를 rollback/reference 용도로 다시 만들지 마세요.

GitHub Actions는 workflow의 scoped package access에 `GITHUB_TOKEN`을 자동 공급합니다. 별도의 `GITHUB_TOKEN` repository 또는 environment secret을 만들거나 저장하지 마세요.

최소 grant가 있는 tagged Tailscale OAuth client를 사용하세요. Production environment를 보호하고 Actions에 사람이 쓰는 SSH private key를 사용하지 마세요.

## secret 처리

- production `.env`와 TLS material은 mode를 제한하고 source checkout 밖에 둡니다.
- Compose label, GitHub variable, command argument, log, issue body, deployment state에 secret 값을 넣지 마세요.
- private deployment metadata를 repository/environment Variable, step summary, public log, issue body에 기록하지 마세요.
- 이후 notification adapter를 구현할 때만 Discord webhook과 SMTP credential을 저장하세요. 현재 읽기 전용 runtime의 일부가 아닙니다.
- credential 값이 Git history나 workflow log에 나타나면 rotate하세요. 보이는 줄을 지우는 것만으로는 충분하지 않습니다.
