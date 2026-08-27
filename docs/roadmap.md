# 구현 로드맵

## 목적과 상태

이 로드맵은 HomeOps의 순서 있는 구현 계획입니다. 현재 지원하는 동작과 계획된 작업을 구분하며, 이후 phase는 구현 약속이나 production 승인으로 보아서는 안 됩니다.

지원 기준선은 host metric과 container inventory를 제공하는 tailnet 전용 읽기 전용 PWA입니다. native macOS Agent와 API/Web deployment는 의도적으로 분리된 delivery boundary입니다.

## 제공 원칙

- API와 Web container를 Docker socket에서 멀리 둡니다.
- native Agent는 고정된 macOS 및 Docker 작업으로 제한합니다.
- mutable tag 또는 암묵적 latest artifact보다 immutable full-SHA identity, 명시적 health evidence, rollback을 우선합니다.
- CI에서 host로 Agent를 교체하는 권한을 API/Web image deployment와 별도로 취급합니다.
- 향후 데이터를 위한 table이 이미 존재한다는 이유만으로 capability를 추가하지 않습니다.

## 현재 phase 상태

| Phase | Source | Production | Acceptance |
|---|---|---|---|
| Phase 1 읽기 전용 대시보드 | IMPLEMENTED | ACTIVE | COMPLETE |
| Phase 2 native Agent rollout | IMPLEMENTED | ACTIVE | COMPLETE |
| Phase 3 운영 이력 | IMPLEMENTED | ACTIVE | COMPLETE |
| Phase 4 알림 | IMPLEMENTED | ACTIVE | COMPLETE |
| Phase 5 제한된 컨테이너 제어 | IMPLEMENTED | ACTIVE | COMPLETE |

## Phase 1: 읽기 전용 대시보드 정확성 및 사용성

**상태:** Source IMPLEMENTED / Production ACTIVE / Acceptance COMPLETE. Host/system summary, container inventory와 Compose project grouping, metric aggregate 저장·retention, bounded metric history API/UI, latest snapshot 기반 Container Detail API/UI와 bounded/redacted Container Logs가 production에서 검증됐습니다. Container Logs는 controlled opt-in, 실제 one-shot retrieval, mobile 표시와 revoke 뒤 fail-closed/payload 제거 acceptance까지 완료했습니다.

**목표:** 제어 기능을 추가하지 않고 현재 iPhone 대시보드를 신뢰할 수 있고 훑기 쉽게 만듭니다.

| 항목 | 범위 | 완료 근거 |
|---|---|---|
| Host metric 정렬 | 선택한 macOS/Netdata 의미에 맞춰 memory usage를 일관되게 정의하고 CPU sampling 및 memory-pressure context를 유지 | Agent regression test 및 동일 시점 macOS/Netdata 비교 |
| Project container group | mobile 친화적이고 접근 가능한 accordion과 명확한 aggregate health로 Docker Compose project별 inventory group화 | Frontend regression test, 여러 Compose project가 있는 iPhone visual check |
| Metric history | `1h`/`6h`/`24h`/`7d`로 제한한 UTC aggregate API와 missing bucket을 보존하는 mobile history UI | PostgreSQL weighted/last-value integration test, API bound/auth test, frontend gap/stale/accessibility regression |
| Container detail | 최신 Agent snapshot 안에서 12자리 identifier를 fail-closed resolve하고 freshness를 보존하는 mobile 읽기 전용 detail 제공 | Full-ID collision 및 auth/no-store Backend test, terminal cache와 mobile/accessibility Frontend test, Tailnet production detail acceptance |
| Container tail log | Fresh capability와 container별 exact opt-in에서만 `50`/`100`/`200` one-shot tail을 허용하고, output을 이중 redact/limit하며 host path를 받지 않음 | Agent live resolver, bounded in-memory broker, exact mTLS ingress, synchronous ADMIN API와 ephemeral mobile UI 구현. Controlled production opt-in·retrieval·revoke와 mobile acceptance 완료 |

**포함하지 않음:** start, stop, restart, 임의 Docker command, 전체 log retention.

## Phase 2: opt-in native Agent rollout

**상태:** Source IMPLEMENTED / Production ACTIVE / Operational Acceptance COMPLETE. 현재 repository의 automatic rollout kill switch는 `false`지만 이는 capability 또는 acceptance 상태와 별개입니다.

**목표:** generic remote shell을 허용하거나 마지막 known-good binary를 조용히 덮어쓰지 않고 `main` merge 뒤 Agent code 변경을 배포 가능하게 합니다.

`main`에서는 full validation과 기존 application release 정책을 유지합니다. Persistent native Agent artifact는 `agent/**`, `agent-artifact.Dockerfile`, `.dockerignore`, `.gitattributes`가 바뀔 때만 release eligible입니다. docs, backend, frontend, rollout workflow만 바뀌면 Agent artifact publication과 rollout은 실행하지 않습니다.

### 구현 및 운영 contract

1. CI는 정확한 macOS ARM64 binary와 checksum을 persistent immutable artifact로 보관합니다.
2. rollout request는 full commit SHA와 검증된 artifact identity만 받습니다.
3. Agent별 lock, 고정 install root와 LaunchAgent label을 가진 host-side restricted wrapper가 caller의 path 또는 command 입력을 차단합니다.
4. binary는 immutable versioned directory에 stage하며 checksum, regular-file type, owner와 restrictive mode를 promotion 전에 검증합니다.
5. `current`와 `previous` pointer를 atomic하게 전환한 뒤 구성한 LaunchAgent만 재시작합니다.
6. 요청한 Agent version의 fresh snapshot과 `version-proof`가 확인된 뒤에만 rollout을 수락합니다.
7. 실패하면 previous pointer와 Agent를 자동 복구하고 evidence를 보존한 채 failure를 반환합니다.
8. API/Web deployment와 독립적인 repository kill switch가 production mutation을 제어합니다.

**완료 근거:** unit 및 deployment-wrapper regression, checksum·concurrent rollout 거부, first-install과 실패 자동 복구, production artifact/release boundary, live previous rollback과 original current roll-forward, fresh proof/snapshot 및 실제 cadence stability 관찰.

**포함하지 않음:** Agent self-update, 임의 artifact URL, 임의 `launchctl` command, sudo, Docker command input, 이전 release 자동 cleanup.

## Phase 3: 운영 이력

**상태:** Source IMPLEMENTED / Production ACTIVE / Acceptance COMPLETE. 신뢰하는 reporter의 deployment/backup ingestion, exact-origin service check와 incident transition, 안정 cursor를 사용하는 Activity API/UI가 production에서 동작하며 formal read-only acceptance를 완료했습니다.

**목표:** 예약한 data model을 범위가 제한되고 감사 가능한 운영 이력으로 만듭니다.

| 항목 | 범위 | 완료 근거 |
|---|---|---|
| Deployment ingestion | 신뢰하는 deployment path에서 멱등적인 deployment start/success/failure event 수신 | 시간·본문 bound HMAC-SHA-256 인증, event/digest 멱등성, terminal transition과 concurrent update regression. Production reporter 설치와 live ingestion 확인 |
| Backup-result ingestion | 기존 project backup script에서 metadata 수신. HomeOps는 backup을 실행하지 않음 | `RUNNING`/`SUCCESS`/`FAILED`/`INCOMPLETE` lifecycle, bounded logical identifier, 멱등성·terminal conflict regression. Production reporter와 live metadata ingestion 확인 |
| Service check 및 incident | 구성한 HTTP check, 연속 failure/recovery logic, incident history | exact-origin·no-redirect·timeout, per-service in-flight 보호, failure/recovery threshold, single active incident와 check-result retention regression. Production scheduler와 recovery history 확인 |
| Activity view | deployment, backup, incident, Agent event와 container control audit를 위한 mobile timeline 및 single event-type filter | Allowlist projection, deterministic ordering, filter scope-bound visibility snapshot과 first-page 기준 최대 1시간의 versioned HMAC cursor, no-store, invalid continuation recovery, empty/stale/error/pagination regression. 기존 production timeline acceptance는 완료됐으며 signed cursor release acceptance는 별도 gate로 분리 |

Phase 3 완료 뒤 `DISK_LOW`, `HTTP_5XX_BURST`의 bounded monitoring episode ingestion을 추가했습니다. Exact HMAC endpoint는 typed `ALERT`/`RECOVERED` request만 받고 durable event-key ledger, active project/type uniqueness와 같은-incident recovery를 사용합니다. Flyway V13, installed reporter의 fixed `signal` mode, 두 lifecycle과 bounded Activity projection은 production acceptance를 완료했습니다. 이 추가 capability는 기존 Phase 3 완료 상태를 재정의하거나 notification authority를 넓히지 않으며, FormDock signal integration을 위한 HomeOps 측 prerequisite는 충족됐습니다.

[ADR-001](adr/ADR-001-operational-history-retention-and-deletion-safety.md)에서 장기 운영 이력 보존·삭제 safety policy와 source별 current operating decision을 확정했습니다. Production aggregate evidence에서 destructive cleanup benefit과 duration 근거가 입증되지 않아 deployment, backup과 Agent event는 `KEEP_UNBOUNDED`이고 deletion runtime은 구현하지 않습니다. Incident는 lifecycle·notification dependency Decision 전까지 finite candidate가 아니며 privileged container control audit도 별도 Security/Audit Decision 범위입니다. Future finite source는 server-owned `recorded_at`, first-page cursor `snapshotAt`에 고정한 logical cutoff, accepted cursor invalidation과 active/nonterminal fail-closed ordering을 모두 충족해야 합니다. V12 event-key ledger cleanup은 disabled이고 정상/비정상 service-check result와 다른 existing bounded data의 retention semantics도 그대로 유지합니다. 이 결정은 Phase 3/5 COMPLETE 상태를 변경하지 않습니다.

**포함하지 않음:** Uptime Kuma internal API dependency 또는 독립 reachability 역할의 대체.

## Phase 4: 알림

**상태:** Source IMPLEMENTED / Production ACTIVE / Acceptance COMPLETE.

Transactional outbox와 bounded Discord worker, fail-closed service eligibility authority, deployment·backup ingestion winner, incident lifecycle, Agent freshness/version 및 Docker episode producer가 구현되어 있습니다. Incident producer는 future opt-in OPEN winner와 SENT root가 있는 bounded escalation/recovery만 생성합니다. Agent producer는 persisted expected status의 stale episode와 actual current snapshot winner만 사용하고 SENT stale root에만 recovery를 연결합니다. Docker producer는 exact `homeops.notifications=true`를 전달한 fresh·strictly-newer current snapshot만 authority로 삼아 baseline, sustained failure, cooldown과 SENT-root recovery를 bounded current state에 기록합니다.

Production acceptance에서는 disabled baseline의 outbound zero와 no-replay, additive V8/V9 schema 적용, controlled Native Agent rollout, exact Docker opt-in의 failure/recovery delivery, 대표 deployment·backup lifecycle delivery와 deduplication을 검증했습니다. Acceptance 종료 뒤 현재 `HOMEOPS_NOTIFICATIONS_ENABLED=false`라 새 qualifying event는 replay 불가 `SUPPRESSED`로 끝나고 Discord outbound 및 `PENDING`/`DELIVERING` backlog는 없습니다. Webhook Secret은 설치된 상태이며 `HOMEOPS_AGENT_ROLLOUT_ENABLED=false`도 유지합니다. 두 kill switch의 현재 값은 production-accepted capability 상태와 별개입니다. Incident와 Agent producer의 deterministic lifecycle은 source 및 automated test evidence로 검증했으며, production service 장애를 인위적으로 유도하는 Discord drill은 완료 근거로 주장하지 않습니다.

**목표:** 중복 alert storm 없이 유용한 운영 signal을 제공합니다.

- Agent, Docker, deployment, backup-result, incident event용 Discord delivery
- Deduplication key, cooldown, 장기 failure escalation, recovery notification, delivery-failure record
- 문서화한 ownership matrix: Uptime Kuma는 외부 HTTP availability와 email path를 유지하고 HomeOps는 내부 운영 event를 담당
- Uptime Kuma email과 HomeOps Discord의 owner 및 duplicate-prevention policy를 분리하고 optional email escalation은 현재 범위에서 제외

**완료 근거:** migration·outbox lease/CAS·bounded transport·retry/unknown·privacy·producer lifecycle·dedup/cooldown/recovery automated regression과 disabled/no-replay production baseline, representative Docker failure/recovery 및 deployment/backup Discord delivery, Uptime Kuma/HomeOps ownership 분리, final disable 뒤 suppressed/no-backlog acceptance.

## Phase 5: 제한된 컨테이너 제어

**상태:** Source IMPLEMENTED / Production ACTIVE / Acceptance COMPLETE. Exact managed/project candidate authority, bounded Agent outbound control protocol, ADMIN 전용 public mutation/polling API의 durable audit·client idempotency와 Container Detail mobile control UI가 production에서 검증됐습니다.

**목표:** 명시적으로 관리하는 container만 의도적으로 start, stop, restart할 수 있게 합니다.

1. Candidate foundation은 exact `homeops.managed=true`, fresh snapshot, unique 12자리 short-ID match와 서버가 관리하는 exact project allowlist를 모두 요구하며 HomeOps/standalone/unknown project를 fail closed합니다.
2. Bounded in-memory broker와 outbound-only Agent worker는 global active 1, PENDING operation TTL 15초, CLAIMED result-reporting grace 15초, non-replay claimed work, metadata-only tombstone을 사용합니다. Agent는 operation 직전 live full-ID, exact label/project/service와 mount를 다시 검증하고 HomeOps, protected service, writable bind/volume과 판정 불가능 mount를 hard deny합니다. Operation deadline 이후 grace 안의 terminal result는 수락하고, grace까지 result가 없으면 `OUTCOME_UNKNOWN`으로 끝냅니다.
3. Agent operation은 `START|STOP|RESTART`와 fixed Docker POST/timeout만 지원합니다. Docker CLI, shell, image, volume, network, arbitrary path/query/body/timeout input은 받지 않으며 ambiguous outcome은 재실행하지 않습니다.
4. Public API는 ADMIN session, CSRF, server-owned canonical public HTTPS Origin, exact confirmation과 canonical idempotency key를 요구합니다. Proxy-internal Host는 public authority로 사용하지 않으며 missing/invalid configuration과 wrong scheme/host/port를 fail closed합니다. Existing durable replay/conflict를 먼저 판정하고 genuinely new key에 principal+key rate limit을 적용한 뒤 V10 durable audit reservation을 commit합니다. Durable winner만 broker enqueue로 이어지며 global operation lock, async result CAS와 stale `REQUESTED` reconciliation을 적용합니다.
5. HomeOps, database, unknown container는 기본 거부하며 더 강한 confirmation은 명시적 policy로만 도입합니다.
6. Docker request 성공을 가정하지 않고 fresh Agent snapshot으로 operation result를 검증합니다.
7. Container Detail UI는 latest snapshot을 이용해 보수적인 candidate action만 표시하고 Backend와 Agent의 live authorization을 최종 authority로 유지합니다. 별도 confirmation 뒤에만 mutation을 보내며 자동 POST retry를 하지 않습니다.
8. Ambiguous submission은 같은 in-memory idempotency key로만 explicit retry할 수 있고, `REQUESTED` operation은 반환된 public operation ID에 대한 visibility/online-aware bounded GET polling으로만 확인합니다.

**현재 source 근거:** Agent exact-label/authority-independence와 bounded allowlist/candidate test, control broker concurrency·expiry·duplicate-result test, exact mTLS route, strict wire decode, live protected target/mount revalidation, fixed Docker HTTP와 ambiguous no-retry regression. Public API에는 strict DTO, same-origin/CSRF security, commit-before-dispatch idempotency, bounded audit projection/reconciliation과 PostgreSQL concurrency/migration regression이 있습니다. Container Detail UI에는 conservative action matrix, accessible confirmation, one-key ambiguous retry, GET-only bounded polling과 terminal-state regression이 있습니다. Control audit의 bounded public subset은 visibility-snapshot cursor를 유지한 Activity source로도 projection됩니다.

**전체 완료 근거:** authorization, CSRF, canonical public Origin, allowlist, duplicate request, audit, timeout, stale Agent와 database-protection automated regression에 더해 controlled `STOP → START → RESTART`, terminal 이후 fresh snapshot 확인과 iPhone production interaction acceptance를 완료했습니다.

## 문서 및 릴리스 원칙

완료한 phase는 사용 가능하다고 선언하기 전에 `README.md`, 적절한 installation/operations runbook, test의 지원 범위를 갱신해야 합니다. code 변경, commit, push, PR, merge, API/Web deployment, Agent rollout은 계속 별도 승인 단계입니다.
