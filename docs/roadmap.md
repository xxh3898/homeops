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

## Phase 1: 읽기 전용 대시보드 정확성 및 사용성

**목표:** 제어 기능을 추가하지 않고 현재 iPhone 대시보드를 신뢰할 수 있고 훑기 쉽게 만듭니다.

| 항목 | 범위 | 완료 근거 |
|---|---|---|
| Host metric 정렬 | 선택한 macOS/Netdata 의미에 맞춰 memory usage를 일관되게 정의하고 CPU sampling 및 memory-pressure context를 유지 | Agent regression test 및 동일 시점 macOS/Netdata 비교 |
| Project container group | mobile 친화적이고 접근 가능한 accordion과 명확한 aggregate health로 Docker Compose project별 inventory group화 | Frontend regression test, 여러 Compose project가 있는 iPhone visual check |
| Metric history | 이미 aggregate한 host metric을 범위 제한 time range로 제공하고 API response를 live state로 cache하지 않고 history 표시 | API, retention, frontend, stale-data test |
| Container detail 및 log | 범위 제한 읽기 전용 detail 및 tail endpoint 추가. output을 redact/limit하고 host path를 절대 받지 않음 | Agent/API test, size limit test, security review |

**포함하지 않음:** start, stop, restart, 임의 Docker command, 전체 log retention.

## Phase 2: opt-in native Agent rollout

**상태:** source에는 구현되었으며 Mac staging과 rollback drill 전까지 기본 비활성입니다.

**목표:** generic remote shell을 허용하거나 마지막 known-good binary를 조용히 덮어쓰지 않고 `main` merge 뒤 Agent code 변경을 배포 가능하게 합니다.

### 필수 구현 순서

1. 정확한 macOS ARM64 binary 및 checksum의 persistent artifact 위치와 retention policy를 정합니다. 짧게 유지되는 CI artifact만으로는 rollback에 충분하지 않습니다.
2. full commit SHA와 검증된 artifact identity만 input으로 받는 전용 Agent rollout request를 정의합니다.
3. Agent별 lock, 고정 install root, 정확한 LaunchAgent label을 갖고 caller가 path 또는 command를 제어하지 못하는 host-side restricted wrapper를 구현합니다.
4. binary를 immutable versioned directory에 stage하고 promotion 전에 checksum, regular-file type, owner, restrictive mode를 검증합니다.
5. `current`와 `previous` pointer를 atomic하게 전환한 뒤 구성한 LaunchAgent만 재시작합니다.
6. 요청한 Agent version의 fresh snapshot을 기다립니다. 그 evidence가 도착한 뒤에만 promotion합니다.
7. 실패 시 previous pointer를 복구하고 previous Agent를 재시작하며 rollout evidence를 보존하고 failure를 반환합니다.
8. API/Web deployment와 독립적인 기본 비활성 Agent rollout switch를 추가한 뒤 production 활성화 전에 staging과 rollback drill을 실행합니다.

**완료 근거:** unit 및 deployment-wrapper regression test, checksum 거부, concurrent rollout 거부, first-install 동작, restart 또는 snapshot 실패 뒤 rollback, Mac staging drill, iPhone/API freshness check.

**포함하지 않음:** Agent self-update, 임의 artifact URL, 임의 `launchctl` command, sudo, Docker command input, 이전 release 자동 cleanup.

## Phase 3: 운영 이력

**상태:** source에 구현되었으며 production ingestion과 check는 운영자 구성 전까지 fail-closed 상태입니다.

**목표:** 예약한 data model을 범위가 제한되고 감사 가능한 운영 이력으로 만듭니다.

| 항목 | 범위 | 완료 근거 |
|---|---|---|
| Deployment ingestion | 신뢰하는 deployment path에서 멱등적인 deployment start/success/failure event 수신 | signature/auth, idempotency, state transition test |
| Backup-result ingestion | 기존 project backup script에서 metadata 수신. HomeOps는 backup을 실행하지 않음 | logical identifier validation 및 failure-path test |
| Service check 및 incident | 구성한 HTTP check, 연속 failure/recovery logic, incident history | timeout, state transition, retention test |
| Activity view | deployment, backup, incident, Agent event를 위한 mobile timeline | empty, stale, error, pagination test |

**포함하지 않음:** Uptime Kuma internal API dependency 또는 독립 reachability 역할의 대체.

## Phase 4: 알림

**목표:** 중복 alert storm 없이 유용한 운영 signal을 제공합니다.

- Agent, Docker, deployment, backup-result, incident event용 Discord delivery
- Deduplication key, cooldown, 장기 failure escalation, recovery notification, delivery-failure record
- 문서화한 ownership matrix: Uptime Kuma는 외부 HTTP availability와 email path를 유지하고 HomeOps는 내부 운영 event를 담당
- incident owner와 duplicate-prevention policy를 검증한 뒤에만 optional email escalation

**완료 근거:** mock webhook test, retry/dedup/recovery test, ownership-matrix scenario 하나에서 duplicate alert가 없음.

## Phase 5: 제한된 컨테이너 제어

**목표:** 명시적으로 관리하는 container만 의도적으로 start, stop, restart할 수 있게 합니다.

1. live `homeops.managed=true` label verification과 서버가 관리하는 project allowlist를 요구합니다.
2. 고정 작업만 추가합니다. Docker CLI, shell, image, volume, network, Compose input은 받지 않습니다.
3. CSRF, Origin check, confirmation UX, idempotency key, rate limit, operation lock, audit record를 요구합니다.
4. HomeOps, database, unknown container는 기본 거부하며 더 강한 confirmation은 명시적 policy로만 도입합니다.
5. Docker request 성공을 가정하지 않고 fresh Agent snapshot으로 operation result를 검증합니다.

**완료 근거:** authorization, CSRF, allowlist, duplicate request, audit, timeout, stale Agent, database-protection test.

## 문서 및 릴리스 원칙

완료한 phase는 사용 가능하다고 선언하기 전에 `README.md`, 적절한 installation/operations runbook, test의 지원 범위를 갱신해야 합니다. code 변경, commit, push, PR, merge, API/Web deployment, Agent rollout은 계속 별도 승인 단계입니다.
