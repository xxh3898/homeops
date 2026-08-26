# 운영 runbook

이 runbook은 contract와 점검 항목을 설명합니다. 실행 중인 Mac을 변경할 권한이 아닙니다. placeholder는 비공개 운영자 사본에서만 바꾸고, 실행 전 해석된 모든 target을 검사하세요.

## 라이프사이클 gate

| Gate | 근거 | 현재 상태 |
|---|---|---|
| G0 읽기 전용 기준선 | Host/runtime/repository 인벤토리 및 결정 | COMPLETE — repository, GitHub와 live runtime identity/health baseline 검증 |
| G1 개발 | production 격리를 적용한 focused Backend, Frontend, Agent test | COMPLETE — 격리된 순차 검증과 component regression 통과 |
| G2 path-aware CI | Classifier case 및 안정적인 required context | COMPLETE — validation/release scope 분리와 push, pull request CI 검증 |
| G3 배포 staging | Bootstrap identity, release shape, mode, idempotence, rollback | PARTIAL — mock regression과 production transaction evidence는 있으나 별도 live staging acceptance 기록 없음 |
| G4 초기 migration | 비어 있는 전용 DB migration 및 JPA validation | COMPLETE — 격리된 PostgreSQL 초기/upgrade migration과 JPA validation 통과 |
| G5 Agent | mTLS delivery, 실제 Mac metric, Docker socket, spool recovery | COMPLETE — production Agent, immutable release, fresh proof/snapshot, live rollback·roll-forward와 stability 검증 |
| G6 tailnet/PWA | Serve identity, iPhone install, background recovery, public access 없음 | PARTIAL — Serve HTTPS, Funnel 부재, root/asset/readiness와 PWA asset 확인. iPhone standalone/background acceptance 미확인 |
| G7 production | immutable-digest deploy, SHA identity validation, health, tailnet smoke, previous rollback | COMPLETE — immutable application deploy, runtime identity, health/tailnet smoke와 previous application rollback evidence 확인 |

HomeOps는 자체 PostgreSQL에 대해 Master Playbook의 recurring/offsite backup, retention, restore gate를 의도적으로 제외합니다. 이는 명시적인 durability tradeoff이지 backup 성공 상태가 아닙니다. destructive migration에는 여전히 일회성 logical snapshot 또는 명시적 reset 결정이 필요합니다.

## focused 개발 검증

격리된 `homeops-test` Compose project에서 다음 검사를 순차 실행합니다.

1. Agent unit test 및 macOS ARM64 cross-build
2. Backend unit/slice test
3. Frontend lint, typecheck, unit test, production build
4. development, test, production 예시의 Compose rendering
5. runtime-config image build

실행 전 memory pressure, disk headroom, 활성 production health, 정확한 Compose file을 확인하고, 어떤 test service도 운영 socket, network, volume, credential, endpoint를 mount하지 않는지 확인하세요. Test container는 폐기 가능하지만 named test cache는 production data가 아닙니다.

## 배포 transaction

Phase 3 source와 production ingestion/monitoring은 활성 상태이며 formal production acceptance도 COMPLETE입니다. Production의 `dev.homeops.ingestion-reporter` LaunchAgent는 범위 제한 `--drain` retry를 담당하고 신뢰하는 project의 deployment/backup event가 Activity에 수신됩니다. Exact-origin service checker와 incident history가 동작하며, retained ingestion metadata, check growth와 incident recovery, Activity의 안정 cursor 전체 pagination과 mobile 표시를 production mutation 없이 검증했습니다.

자동 retention은 metric aggregate, 처리한 Agent snapshot 멱등성 ledger, service-check result와 terminal notification outbox에 각자의 기존 contract로 적용됩니다. [ADR-001](adr/ADR-001-operational-history-retention-and-deletion-safety.md)은 deployment, backup, incident와 Agent 장기 event의 automatic deletion을 명시적인 source policy 전까지 disabled로 두고, `container_action_audit`를 generic Activity retention에서 제외합니다. 운영자는 임의 cleanup이나 age-only DELETE를 실행하면 안 됩니다. 이 fail-closed policy는 Phase 3/5 acceptance를 미완료로 되돌리거나 영구 보존을 Product requirement로 확정하는 의미가 아닙니다.

새 host에서 Phase 3를 활성화하는 작업은 source release와 분리합니다. HomeOps `.env`에 생성한 64글자 소문자 hexadecimal `HOMEOPS_INGESTION_SHARED_SECRET` 하나가 있는지, `smoke.origin`이 의도한 tailnet HTTPS origin인지, 두 file이 owner-only mode `0600`인지, deployment account의 `~/Server/data/homeops/ingestion-spool`이 owner-only mode `0700`인지 확인합니다. private reporter copy와 LaunchAgent를 load 전에 lint하고, reporter warning을 application deploy 또는 backup 실패로 취급하지 말며 spool과 HomeOps ingestion health를 따로 검사하세요.

GitHub workflow는 다음을 수행합니다.

1. `main`에서 재사용 가능한 전체 validation
2. ARM64 API, Web, runtime-config image publication
3. 정확한 commit SHA와 immutable API, Web, runtime-config digest capture
4. OIDC OAuth client를 이용한 tailnet 연결
5. standard input의 GHCR token을 사용한 restricted SSH invocation
6. runtime release extraction 및 validation
7. non-blocking project lock
8. 전용 database 시작 및 one-shot Flyway migration
9. API/Web 교체와 container health check
10. Mac에서 tailnet HTTPS root, representative asset, readiness smoke
11. current/previous state update
12. GitHub runner에서 두 번째 tailnet readiness smoke

runtime-config image는 release마다 다시 build합니다. 첫 public release에서 숨은 Compose/script synchronization path를 피하는 대신 작은 추가 build 비용이 듭니다.

Full validation은 native Agent release 허가가 아닙니다. Persistent Agent artifact는 `agent/**`, `agent-artifact.Dockerfile`, `.dockerignore`, `.gitattributes` 변경만 eligible이며 docs/backend/frontend/workflow-only 변경에서는 publication과 rollout을 건너뜁니다. Rollout은 artifact publication 성공과 repository `HOMEOPS_AGENT_ROLLOUT_ENABLED=true`를 모두 요구합니다. 현재 값은 `false`이며 Phase 2 acceptance와 별개인 operational kill switch입니다.

`HOMEOPS_DEPLOY_HOST`와 `HOMEOPS_DEPLOY_USER`는 Production environment Secret으로만 Tailscale ping과 SSH target에 전달합니다. 같은 이름의 legacy Variable은 제거됐고 secret 기반 production deploy와 public log literal zero-match acceptance를 완료했습니다.

## 장기 운영 이력 destructive retention gate

Operational Activity history 삭제 runtime은 현재 구현되지 않았고 production activation도 승인되지 않았습니다. Activity cursor source에는 process-local HMAC integrity와 first-page 기준 최대 1시간 validity가 구현됐지만, future source별 retention은 logical visibility cutoff, cursor validity/invalidation boundary와 physical deletion eligibility를 계속 분리해야 합니다. Server가 아직 수락할 Activity cursor가 참조할 수 있는 row는 hard-delete할 수 없으며 cursor TTL과 retention duration의 단순 비교만으로 이 조건을 충족했다고 판단하면 안 됩니다. V12 minimal ledger로 deployment/backup event-key replay authority는 business history와 분리됐지만 ledger cleanup은 없고, source별 retention policy·grace, incident/notification dependency와 privileged audit 경계는 [ADR-001](adr/ADR-001-operational-history-retention-and-deletion-safety.md)에 따라 별도로 충족해야 합니다.

첫 production hard-delete 전에는 application release와 별도의 Ops Gate가 필요합니다. Exact DB/release identity, candidate age/count, active/nonterminal/dependency count를 read-only로 확인하고 cleanup switch가 default disabled인지 검증하세요. Pre-delete logical backup 또는 동등한 accepted recovery artifact와 isolated restore evidence가 없으면 실행하지 않습니다. 첫 run은 작은 bounded batch로 제한하고 deleted/skipped aggregate count와 Activity/API/health만 검증하며 raw payload나 private metadata를 evidence에 남기지 않습니다. 예상 밖 삭제가 있으면 추가 batch를 중단하고 restore 또는 사전에 합의한 forward recovery를 사용합니다. Deleted row를 설정 rollback만으로 복구할 수 있다고 간주하지 마세요.

## Docker network 토폴로지

`application`은 Web, API, PostgreSQL, one-shot migration용 internal east-west network입니다. `ingress`는 Docker Desktop이 loopback-published browser/Tailscale Serve entry에 필요하므로 Web에만 연결한 non-internal bridge입니다. `egress`는 monitored-service HTTPS check를 위해 API에만 연결한 별도 non-internal bridge입니다. Docker network name은 방향성 firewall rule을 만들지 않습니다. Web의 ingress 연결은 Docker 수준 outbound 차단을 보장하지 않습니다. Web image는 static-file 및 Nginx reverse-proxy 역할만 하며 API check destination은 계속 `SafeServiceUrlPolicy`로 제한합니다. 더 강한 Web egress 격리가 필요하면 host firewall, proxy, 별도 edge network policy가 필요합니다.

## 상태 파일 및 pointer

- `runtime-config/pending`: 완료되지 않은 candidate transaction 식별
- `runtime-config/current`: 수락한 deployment state와 연결된 runtime configuration 식별
- `deployment.state`: current/previous application SHA, API digest, Web digest, runtime-config digest 포함
- lock file에는 credential이 없음

`pending`이 남아 있으면 자동으로 삭제하지 마세요. deployment log, `deployment.state`, 두 symlink target, 정확한 candidate release, current container image digest와 revision label, database migration state를 검사합니다. 재개, current release 복귀, partial state 복구 중 무엇을 할지 결정하세요. 광범위한 cleanup과 `docker system prune`은 recovery step이 아닙니다.

## 실패 처리

| 실패 | 즉시 동작 | 운영자 후속 조치 |
|---|---|---|
| validation 또는 image build | publish/deploy 없음 | source를 고치고 검토된 CI 재실행 |
| runtime image identity/shape mismatch | bootstrap 중지 | digest, GHCR package, immutable release directory 검증 |
| lock 사용 불가 | 중첩 없이 종료 | 활성 exact operation을 찾고 lock 우회 금지 |
| migration 실패 | current API writer quiesce 유지, candidate cutover와 state advance 중지 | current pinned application/runtime configuration 복구와 health/readiness/public smoke 확인 후 Flyway와 DB를 검사하고 forward fix 우선. current release가 없는 bootstrap은 fail closed |
| API/Web health 실패 | previous digest-pinned image와 runtime config 시도 | rollback health를 검증하고 pending evidence 보존. V12 적용 뒤 pre-V12 API가 복구돼도 DB-level fence가 ledgerless ingestion INSERT를 차단 |
| tailnet smoke 실패 | local health 뒤 workflow 실패 | Serve/ACL/identity와 application health 분리 |
| 첫 배포 검증 실패 | API/Web 중지. 수락된 `current` state 없음 | `pending`과 전용 DB를 진단용으로 보존. volume 자동 삭제 금지 |
| Agent delivery 실패 | retry 가능한 pending delivery는 FIFO를 보존하고 새 collection을 억제. 새 snapshot 전달 실패 시 queue | spool file을 지우지 말고 API, mTLS, spool capacity, clock 검증 |
| snapshot 영구 거부 | 연속 permanent reject를 FIFO drain의 같은 위치에 숨은 `.rejected-*.json` evidence file로 rename. retryable pending item이 없으면 fresh collection 재개 | metadata와 안전한 error status만 검사. rejected evidence도 bounded spool capacity에 포함되므로 정확한 retention을 수동 결정 |
| event reporter transient 실패 또는 deployment 중 API quiescence | event는 전송 전에 private ingestion spool에 원자 기록되고 transient/unavailable route에서는 남아 `dev.homeops.ingestion-reporter` LaunchAgent가 5분마다 범위 제한 `--drain` retry 호출 | spool entry를 지우지 말고 API ingestion health, origin, secret configuration, spool count 검사 |
| stale Agent | UI가 stale/offline 경고 | native process와 Docker Desktop 검사. 자동 restart 없음 |

Production worker는 ingestion ledger backfill을 포함한 migration 전에 current API를 완전히 정지하고, migration 성공 뒤에만 candidate API/Web을 활성화합니다. Migration 실패 시 accepted deployment state와 runtime-config current pointer를 candidate로 바꾸지 않습니다. V12 DB는 old/new application writer 모두의 deployment/backup INSERT 전에 ledger reservation을 강제하므로 migration 성공 뒤 candidate verification rollback에서도 ledgerless business row를 허용하지 않습니다. 다른 migration은 이미 database를 바꿨을 수 있으므로 image rollback이 backward-compatible할 때만 안전합니다. 자동 path에 incompatible migration을 사용하지 마세요.

## 일상 읽기 전용 점검

- API 및 Web container health
- current application SHA와 API, Web, runtime-config digest
- Agent의 마지막 captured/received 시각 및 version
- payload를 읽지 않는 spool file count
- PostgreSQL volume 크기 및 host disk 추세
- Tailscale Serve status 및 Funnel 부재
- Uptime Kuma HTTP monitor status
- GitHub deployment workflow 및 Production environment history

환경 값, 전체 Tailnet status, certificate, private path, Docker inspect environment를 issue report에 출력하지 마세요.

## Uptime Kuma 및 알림

| Owner | 담당 signal | Delivery |
|---|---|---|
| Uptime Kuma | 외부 HTTP/Tailnet reachability | 기존 email path |
| HomeOps | 신뢰하는 reporter의 future deployment·backup lifecycle, persisted exact-origin incident 중 명시적으로 opt-in한 service의 future transition, API가 살아 있는 동안 expected native Agent의 freshness/version lifecycle, 명시적으로 opt-in한 내부 Docker container episode | Production-accepted Discord delivery capability. 현재 global switch가 `false`라 outbound는 disabled |

Phase 4는 Source IMPLEMENTED / Production ACTIVE / Acceptance COMPLETE입니다. Production acceptance는 disabled baseline과 historical no-replay, additive V8/V9 schema 적용, controlled Native Agent rollout, exact Docker opt-in의 failure/recovery, 대표 deployment·backup lifecycle delivery와 final disable을 검증했습니다. 현재 webhook Secret은 값 비노출 상태로 설치되어 있지만 `HOMEOPS_NOTIFICATIONS_ENABLED=false`이므로 outbound는 없고 새 qualifying intent는 replay 불가 `SUPPRESSED`로 종료되며 `PENDING`/`DELIVERING` backlog도 없습니다. 이는 capability의 production 상태와 별개인 운영 kill switch입니다.

`monitored_service.notification_enabled`는 HomeOps Discord incident의 future eligibility만 나타냅니다. Uptime Kuma monitor/email 설정이나 Discord global kill switch를 변경하지 않습니다. 이 값을 바꾸는 것만으로 notification intent, historical/open incident replay 또는 outbound가 발생하지 않습니다. Existing service는 boolean-only ADMIN + CSRF operation으로만 이 authority를 변경하며 DB default와 legacy migration 결과는 fail-closed `false`입니다.

Deployment와 backup producer는 실제 ingestion insert 또는 terminal transition winner만 typed outbox intent로 기록하며 replay나 경쟁 loser는 새 intent를 만들지 않습니다. Backup payload는 project, database type, status만 허용하고 logical location, failure, expiry와 restore metadata는 제외합니다. Incident producer는 event 시점의 persisted service authority를 확인하고 actual OPEN winner, 기본 15분 이상 지속된 DOWN observation, actual recovery winner만 고려합니다. Agent producer는 persisted expected status row의 last snapshot을 stale episode root로 사용하고 actual current snapshot update winner만 recovery/version intent를 만들며, first connection·duplicate·out-of-order snapshot은 notification을 만들지 않습니다. Docker producer는 fresh current snapshot의 exact opt-in만 사용하고 first observation/recreate/re-enable을 baseline으로 처리하며 기본 30초 sustained failure와 5분 re-alert cooldown을 적용합니다. Incident, Agent와 Docker recovery는 같은 episode의 SENT root에만 parent로 연결하고 suppressed/pending/failed/unknown/missing root에는 child를 만들지 않습니다. Global switch가 disabled이면 root와 version intent는 `SUPPRESSED`로 끝나고 enable 뒤 재생되지 않습니다. Agent freshness는 `HOMEOPS_AGENT_STALE_AFTER`를 재사용하고 checker cadence만 별도 bounded setting으로 둡니다. Optional email escalation은 같은 incident의 owner와 duplicate-prevention policy를 별도로 검증한 뒤에만 고려합니다.

Native Agent는 exact `homeops.notifications=true`를 container별 `notificationsAllowed` boolean으로만 전달합니다. 이 capability는 `homeops.managed`와 `homeops.logs`에서 독립적이고 old/rollback Agent가 field를 생략하면 false입니다. Backend는 public inventory/detail에 이를 노출하지 않고 fresh current snapshot winner에서만 bounded Docker episode state와 typed outbox intent를 갱신합니다. Source와 production acceptance 완료는 future service label opt-in, Agent rollout 또는 Discord enable 권한이 아니므로 이후 변경도 별도 production gate를 요구합니다.

Phase 5는 Source IMPLEMENTED / Production ACTIVE / Acceptance COMPLETE입니다. `HOMEOPS_CONTROL_ALLOWED_PROJECTS`는 기본 empty이며 exact `homeops.managed=true`, fresh snapshot, unique short-ID match와 exact allowlisted non-HomeOps project가 모두 있어야 work를 enqueue할 수 있습니다. Public ADMIN API는 CSRF, server-owned `HOMEOPS_CONTROL_PUBLIC_ORIGIN`과 정확히 일치하는 단일 HTTPS Origin, exact confirmation과 canonical UUID idempotency key를 요구합니다. Canonical origin이 empty이면 POST를 거부하고 invalid non-empty 값은 startup을 fail closed하며, Tailscale Serve 뒤의 internal/port-less Nginx `Host`나 forwarded-host는 public browser authority로 사용하지 않습니다. Existing durable key replay/conflict는 limiter 앞에서 결정하고, genuinely new key만 principal+key 5/60초 bound를 통과한 뒤 V10 audit reservation을 commit하며 durable created winner만 existing broker를 호출합니다. Audit에는 fixed operation, 12자리 ID, principal, stable status/reason과 timestamp만 저장하며 image, confirmation, raw error와 private metadata는 저장하지 않습니다. Result는 async CAS로 반영하고 operation TTL·reporting grace 이후 남은 public `REQUESTED`는 requeue 없이 `OUTCOME_UNKNOWN`으로 복구합니다. Agent outbound worker는 live label/project/service/mount를 재검증하고 protected/stateful target을 hard deny한 뒤 fixed `START|STOP|RESTART` Docker POST만 work당 최대 한 번 수행합니다. Backend active work는 global 1개이며 PENDING operation deadline은 15초, CLAIMED result-reporting grace는 추가 fixed 15초입니다. Container Detail UI는 별도 confirmation, one-key ambiguous retry와 GET-only bounded status polling을 사용하며 UI candidate gating을 final authority로 취급하지 않습니다. Production에서는 canonical Origin gate, controlled `STOP → START → RESTART`, terminal 이후 fresh snapshot과 iPhone interaction을 검증했습니다. 이 완료 상태는 future allowlist/label 변경이나 추가 control mutation 권한이 아닙니다.

## 데이터 손실 및 재구성

HomeOps database 자동 backup은 없습니다. PostgreSQL volume을 잃으면 host metric history, session, incident, deployment/backup metadata, notification, audit event, database setting을 잃습니다. service는 다음으로 재구성할 수 있습니다.

- public repository와 정확한 immutable image digest
- private `.env` 및 TLS material
- Agent binary/configuration 및 LaunchAgent definition
- stable bootstrap 및 deployment state

이 private file은 HomeOps가 자동화하지 않더라도 운영자가 관리하는 recovery method가 필요합니다. 같은 disk에만 있으면 disk failure 뒤 secret과 certificate를 다시 만들기 전에는 재구성할 수 없습니다.

## Agent upgrade

### 현재: production active, automatic rollout kill switch disabled

Agent publication과 installation은 API/Web deployment와 분리됩니다. Production Agent는 immutable release directory, atomic `current`/`previous` pointer, checksum, owner/mode, LaunchAgent와 fresh `version-proof`를 사용합니다. 일반 `main` application deployment만으로 native Agent를 갱신하지 않습니다.

### opt-in 자동 rollout contract

Agent가 macOS state와 Docker socket을 읽으므로 automatic rollout은 별도 repository kill switch로 통제합니다. Phase 2 operational acceptance는 COMPLETE지만 현재 `HOMEOPS_AGENT_ROLLOUT_ENABLED=false`로 CI mutation을 닫아 두었습니다. `deploy-homeops-v2`에 Agent path, LaunchAgent label, action, shell input을 확장하지 마세요. rollout은 자체 key를 사용하며 정확한 `rollout-homeops-agent-v1` grammar만 받습니다.

향후 kill switch를 `true`로 바꾸거나 rollout contract와 artifact input을 변경하기 전 다음 불변식을 다시 확인하세요.

1. rollback을 위해 current 및 previous digest를 보관하는 persistent GHCR exact-SHA Agent artifact와 checksum
2. 기대하는 full SHA와 검증된 artifact identity만 받는 전용 restricted host command
3. Agent별 lock, stage한 immutable release directory, 검증한 file owner/mode, atomic current/previous pointer transition
4. previous known-good binary를 지우지 않는 명시적 first-install policy와 rollback path
5. caller-supplied label 또는 전역 launchd 작업이 아닌 구성한 LaunchAgent label만 restart
6. 보고한 version이 요청 release와 일치하는 fresh Agent snapshot의 success proof
7. 별도 Agent rollout kill switch와 application deploy로부터 독립된 failure boundary

Production live drill에서 `current → previous → original current` 전환, 각 release의 fresh proof/snapshot, 실제 cadence의 연속 snapshot과 application/Tailnet health를 검증했고 시작 pointer 상태를 복원했습니다. 이 acceptance는 현재 kill switch가 `false`인 것과 모순되지 않습니다.

Container Logs는 Agent가 inbound listener나 generic command channel을 열지 않고 기존 loopback mTLS origin의 두 exact path를 outbound poll/POST하는 구조입니다. Docker 작업은 container list 한 번, full-ID prefix와 `homeops.logs=true` 재검증, 선택 container의 TTY inspect와 bounded logs read로 제한합니다. Tail은 `50`/`100`/`200`만 허용하고 raw Docker response 256 KiB, logical line 8 KiB, 최종 normalized/redacted message 합계 128 KiB, Docker read 3초, public request 6초로 제한합니다. In-memory broker는 pending+claimed 총 4개, container당 1개, Agent active work 1개를 넘지 않습니다. UI는 명시적 Load/Refresh 외에는 요청하지 않고 response를 persistent cache에 두지 않습니다. Agent와 API가 동일 semantic redaction contract를 순차 적용하지만 regex redaction이 모든 secret 제거를 보장하지는 않습니다. Raw log는 Agent spool, filesystem, PostgreSQL 또는 Activity에 저장하지 않습니다. Controlled production opt-in에서 bounded retrieval과 mobile UI를 확인하고, revoke 뒤 fresh snapshot의 fail-closed 응답과 UI payload 제거까지 검증했습니다. 이후 service opt-in도 별도 privacy review와 명시적 승인을 요구합니다.

host는 검증한 binary/checksum으로 `agent/releases/<SHA>`를 stage하고, `current`와 `previous`를 atomic하게 바꾸며 `gui/<uid>/dev.homeops.agent`만 kickstart합니다. restart 뒤 `agent/version-proof`에 요청 SHA와 성공 snapshot timestamp가 있어야 rollout을 수락합니다. 실패 시 `current`를 previous immutable release로 복구합니다. first-install 실패 시 `current`를 제거하고 해당 label만 boot out합니다. Agent rollout을 요청하지 않으면 API/Web deployment는 독립적으로 성공합니다. 구현과 상태는 [구현 로드맵](roadmap.md)을 참고하세요.
