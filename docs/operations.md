# 운영 runbook

이 runbook은 contract와 점검 항목을 설명합니다. 실행 중인 Mac을 변경할 권한이 아닙니다. placeholder는 비공개 운영자 사본에서만 바꾸고, 실행 전 해석된 모든 target을 검사하세요.

## 라이프사이클 gate

| Gate | 근거 | 현재 repository 상태 |
|---|---|---|
| G0 읽기 전용 기준선 | Host/runtime/repository 인벤토리 및 결정 | 문서화됨. live 값은 달라질 수 있음 |
| G1 개발 | production 격리를 적용한 focused Backend, Frontend, Agent test | 로컬 완료. 순차 Docker 검증 통과 |
| G2 path-aware CI | Classifier case 및 안정적인 required context | 구현됨. 최신 push 및 pull request Validate run 통과 |
| G3 배포 staging | Bootstrap identity, release shape, mode, idempotence, rollback | 실행 가능한 mock regression coverage가 로컬 통과. CI 및 live host staging 미실행 |
| G4 초기 migration | 비어 있는 전용 DB migration 및 JPA validation | 격리된 PostgreSQL 18.4 초기 migration, V1-to-V2 upgrade, JPA validation 통과 |
| G5 Agent | mTLS delivery, 실제 Mac metric, Docker socket, spool recovery | 미실행 |
| G6 tailnet/PWA | Serve identity, iPhone install, background recovery, public access 없음 | 미실행 |
| G7 production | immutable-digest deploy, SHA identity validation, health, tailnet smoke, previous rollback | 승인 또는 실행되지 않음 |

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

Phase 3 ingestion activation은 source release가 merge된 뒤의 별도 host 작업입니다. HomeOps `.env`에 생성한 64글자 소문자 hexadecimal `HOMEOPS_INGESTION_SHARED_SECRET` 하나가 있는지, `smoke.origin`이 의도한 tailnet HTTPS origin인지, 두 file이 owner-only mode `0600`인지, deployment account의 `~/Server/data/homeops/ingestion-spool`이 owner-only mode `0700`인지 확인합니다. private copy를 lint한 뒤에만 별도 `dev.homeops.ingestion-reporter` LaunchAgent를 설치합니다. 이는 제한된 interval로 고정 `--drain` mode를 호출하며 보존된 transient event delivery의 retry owner입니다. Cubing Hub와 Guess Pokémon은 hook이 포함된 각자의 runtime-config release를 배포한 뒤에만 event를 내보내기 시작합니다. reporter warning을 application deploy 또는 backup 실패로 취급하지 말고 spool과 HomeOps ingestion health를 따로 검사하세요.

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
| migration 실패 | cutover 중지 | Flyway와 DB를 검사하고 forward fix 우선 |
| API/Web health 실패 | previous digest-pinned image와 runtime config 시도 | rollback health를 검증하고 pending evidence 보존 |
| tailnet smoke 실패 | local health 뒤 workflow 실패 | Serve/ACL/identity와 application health 분리 |
| 첫 배포 검증 실패 | API/Web 중지. 수락된 `current` state 없음 | `pending`과 전용 DB를 진단용으로 보존. volume 자동 삭제 금지 |
| Agent delivery 실패 | retry 가능한 pending delivery는 FIFO를 보존하고 새 collection을 억제. 새 snapshot 전달 실패 시 queue | spool file을 지우지 말고 API, mTLS, spool capacity, clock 검증 |
| snapshot 영구 거부 | 연속 permanent reject를 FIFO drain의 같은 위치에 숨은 `.rejected-*.json` evidence file로 rename. retryable pending item이 없으면 fresh collection 재개 | metadata와 안전한 error status만 검사. rejected evidence도 bounded spool capacity에 포함되므로 정확한 retention을 수동 결정 |
| event reporter transient 실패 | event는 private ingestion spool에 남고 `dev.homeops.ingestion-reporter` LaunchAgent가 5분마다 범위 제한 `--drain` retry 호출 | spool entry를 지우지 말고 API ingestion health, origin, secret configuration, spool count 검사 |
| stale Agent | UI가 stale/offline 경고 | native process와 Docker Desktop 검사. 자동 restart 없음 |

Flyway가 이미 database를 바꿨을 수 있으므로 image rollback은 backward-compatible migration일 때만 안전합니다. 자동 path에 incompatible migration을 사용하지 마세요.

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

Uptime Kuma를 독립 HTTP availability source로 유지하고 현재 email 동작을 보존하세요. service-ownership matrix를 구성하기 전까지 HomeOps가 duplicate health alert를 내보내면 안 됩니다. 이후 HomeOps Discord event는 Docker, Agent, deployment, backup-result ingestion, internal incident transition을 다뤄야 합니다. critical long-duration failure는 email로 escalate할 수 있지만 같은 incident에는 owner 하나와 deduplication key 하나가 필요합니다.

## 데이터 손실 및 재구성

HomeOps database 자동 backup은 없습니다. PostgreSQL volume을 잃으면 host metric history, session, incident, deployment/backup metadata, notification, audit event, database setting을 잃습니다. service는 다음으로 재구성할 수 있습니다.

- public repository와 정확한 immutable image digest
- private `.env` 및 TLS material
- Agent binary/configuration 및 LaunchAgent definition
- stable bootstrap 및 deployment state

이 private file은 HomeOps가 자동화하지 않더라도 운영자가 관리하는 recovery method가 필요합니다. 같은 disk에만 있으면 disk failure 뒤 secret과 certificate를 다시 만들기 전에는 재구성할 수 없습니다.

## Agent upgrade

### 현재: 운영자 관리 설치

Agent publication과 Agent installation은 API/Web deployment와 분리됩니다. artifact checksum을 검증하고 binary를 immutable release directory에 설치하며 고정 `current` symlink가 이를 가리키게 한 뒤 version과 fresh snapshot을 확인하세요. 일반 `main` deployment workflow는 native Agent를 갱신하지 않습니다.

### opt-in 자동 rollout

Agent가 macOS state와 Docker socket을 읽으므로 자동 Agent rollout은 구현되어 있으나 기본 비활성입니다. `deploy-homeops-v2`에 Agent path, LaunchAgent label, action, shell input을 확장하지 마세요. rollout은 자체 key를 사용하며 정확한 `rollout-homeops-agent-v1` grammar만 받습니다.

`HOMEOPS_AGENT_ROLLOUT_ENABLED=true`를 설정하기 전 staging에서 다음을 모두 증명하세요.

1. rollback을 위해 current 및 previous digest를 보관하는 persistent GHCR exact-SHA Agent artifact와 checksum
2. 기대하는 full SHA와 검증된 artifact identity만 받는 전용 restricted host command
3. Agent별 lock, stage한 immutable release directory, 검증한 file owner/mode, atomic current/previous pointer transition
4. previous known-good binary를 지우지 않는 명시적 first-install policy와 rollback path
5. caller-supplied label 또는 전역 launchd 작업이 아닌 구성한 LaunchAgent label만 restart
6. 보고한 version이 요청 release와 일치하는 fresh Agent snapshot의 success proof
7. 별도 Agent rollout kill switch, 기본 비활성 opt-in, staging 및 rollback drill

host는 검증한 binary/checksum으로 `agent/releases/<SHA>`를 stage하고, `current`와 `previous`를 atomic하게 바꾸며 `gui/<uid>/dev.homeops.agent`만 kickstart합니다. restart 뒤 `agent/version-proof`에 요청 SHA와 성공 snapshot timestamp가 있어야 rollout을 수락합니다. 실패 시 `current`를 previous immutable release로 복구합니다. first-install 실패 시 `current`를 제거하고 해당 label만 boot out합니다. Agent rollout을 요청하지 않으면 API/Web deployment는 독립적으로 성공합니다. 구현 순서는 [구현 로드맵](roadmap.md)을 참고하세요.
