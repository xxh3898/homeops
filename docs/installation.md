# 설치

HomeOps 설치는 Docker, Tailscale, certificate, `launchd`, production database를 변경합니다. 각 활성화는 별도의 운영자 결정으로 다루세요. 이 repository는 예시를 제공할 뿐 무엇도 자동 설치하거나 활성화하지 않습니다.

## 사전 요구 사항

- 현재 지원되는 macOS release를 실행하는 Apple Silicon Mac
- Docker Desktop 및 Docker Compose
- tailnet에 HTTPS를 활성화한 Tailscale
- 자동 배포를 원할 경우 Actions와 GHCR 접근 권한이 있는 GitHub repository
- private Agent certificate authority용 OpenSSL 호환 도구

현재 코드 target은 Java 21, Spring Boot 4.1, Node 24, React 19, Vite 8, Go 1.26, PostgreSQL 18, `linux/arm64` application image입니다. major version을 올리기 전에 현재 호환성을 확인하세요.

## 1. 소스 검토 및 검증

runtime component를 활성화하기 전에 다음을 수행합니다.

1. `SECURITY.md`, `docs/configuration.md`, `docs/operations.md`를 읽습니다.
2. 고정된 Node/npm toolchain으로 frontend lockfile을 생성하고 commit합니다.
3. `compose.test.yaml`의 focused test service 세 개를 순차 실행합니다.
4. 모든 Compose definition을 render하고 해석된 mount, port, image, network, volume을 검사합니다.
5. Web과 API service 어느 쪽에도 Docker socket이 mount되지 않고 database에 host port가 없는지 확인합니다.

이 검사가 자체 CI에서 통과하기 전 repository는 정식 출시 전 상태입니다.

## 2. production directory 준비

소스와 production state를 분리하세요. production directory에는 다음만 둡니다.

- mode `0600`의 `.env`
- mode가 제한된 directory의 private TLS material
- 변경 불가능한 runtime-config release와 current/pending pointer
- deployment state와 operation lock
- Docker가 관리하는 전용 PostgreSQL volume

Git working tree를 production directory로 복사하지 마세요. `deploy/env.example`을 private `.env`로 복사하고 모든 placeholder를 바꾸세요. `deploy/smoke.origin.example`에서 mode가 제한된 `smoke.origin`을 만들면 path가 없는 단 하나의 tailnet HTTPS origin을 담습니다. 443과 8443의 기존 Serve listener를 보존할 때는 명시적인 `:9443` port를 사용하세요. 두 private file 모두 commit하지 마세요.

## 3. Agent mTLS 경계 생성

private CA, `localhost` 및 loopback 용도의 server certificate, Agent용 별도 client certificate를 만듭니다. private key는 Git 밖에 둡니다. 권장 permission은 다음과 같습니다.

- CA 및 server/client public certificate: 운영자와 필요한 process만 읽을 수 있음
- server 및 Agent private key: `0600`
- TLS directory: `0700`

Agent mTLS, human SSH, CI deployment에는 서로 다른 key를 사용합니다. tailnet authentication key 또는 GitHub token을 재사용하지 마세요.

## 4. native Agent 설치 및 rollout 경계 준비

CI validation workflow는 commit SHA로 식별되는 macOS ARM64 Agent artifact를 build합니다. 설치 전 SHA-256 checksum을 검증하고 binary와 configuration은 운영자가 소유하며 다른 사용자가 쓸 수 없는 directory 아래에 둡니다.

`deploy/launchd/dev.homeops.agent.plist.example`에서 시작해 모든 placeholder를 바꾸고 다음을 확인하세요.

- 정확한 `Label`이 유일함
- 모든 path가 absolute path임
- Docker socket이 해당 Mac의 활성 Docker Desktop context socket임
- API URL이 loopback HTTPS이고 고정 Agent snapshot path로 끝남
- log와 spool directory가 존재하고 제한적인 mode를 가짐
- process가 `sudo` 없이 로그인한 운영자로 실행됨

load 전에 plist를 lint하세요. LaunchAgent load, 자연스러운 scheduled start test, reboot persistence test는 별도 acceptance gate입니다.

Agent release는 운영자가 소유한 `Server/apps/homeops/agent/releases/<full-sha>` directory 아래에 설치합니다. LaunchAgent는 version 없는 mutable binary가 아닌 고정 `agent/current/homeops-agent` symlink를 참조해야 합니다. `HOMEOPS_AGENT_VERSION_PROOF_FILE`은 고정 Agent root 아래에 구성하세요. 새 Agent가 snapshot을 성공적으로 전달한 뒤에만 기록하는 atomic `0600` file입니다.

일반 `main` deployment workflow는 이 Agent를 교체하지 않습니다. native process가 macOS metric과 Docker socket을 읽으므로, Agent rollout이 비활성이거나 실패하더라도 API/Web deployment는 독립적으로 성공하도록 분리했습니다.

### opt-in Agent rollout

repository에는 별도 Agent rollout track이 구현되어 있으나 기본으로 활성화하지 않습니다. 임의 path, label, action, shell fragment를 받으면 안 됩니다. contract는 다음과 같습니다.

1. CI는 정확한 full commit SHA, 변경 불가능한 digest, checksum으로 식별되는 GHCR Agent artifact를 발행합니다. package retention policy에서 current와 previous artifact digest를 보관합니다.
2. Mac은 active binary를 건드리기 전에 변경 불가능한 artifact를 stage하고 검증합니다.
3. 전용 restricted command는 구성한 Agent release pointer만 전환하고 구성한 LaunchAgent label만 재시작합니다.
4. 기대한 Agent version의 새 snapshot이 새 binary의 healthy 상태를 증명합니다.
5. 실패하면 이전 immutable binary pointer를 복구하고 성공하지 않은 rollout을 보고합니다.
6. `HOMEOPS_AGENT_ROLLOUT_ENABLED` repository variable은 별도 kill switch이며 API/Web deployment를 비활성화하지 않습니다.

활성화하기 전 `deploy/scripts/rollout-homeops-agent.sh`와 `deploy/bootstrap/deploy-homeops-agent-rollout-ci.sh.example`을 운영자가 소유한 `0700` file로 설치하고, 해당 bootstrap에만 제한된 별도 SSH key를 만들며 rollout SSH secret 두 개만 채우세요. API/Web deployment key를 재사용하지 마세요. checksum은 가져온 artifact를 검증하지만 독립적인 code-signing 보장은 아닙니다.

### ingestion reporter retry schedule 설치

deployment 또는 backup ingestion을 활성화할 때 `deploy/launchd/dev.homeops.ingestion-reporter.plist.example`을 로그인한 운영자의 LaunchAgents directory로 복사하고, `REPLACE_ME`만 해당 account name으로 바꾸며 private copy를 load 전에 lint하세요. 이 plist는 load 시와 매 5분마다 고정 `runtime-config/current/scripts/report-homeops-event.py --drain` 명령을 실행합니다. event JSON, 임의 argument, secret, host path를 받지 않습니다. 활성화 전에 참조한 `~/Library/Logs/HomeOps` directory를 제한적인 소유권으로 만드세요.

LaunchAgent를 load하고 보존된 test event가 API recovery 뒤 drain되는지 확인하는 일은 별도 host acceptance gate입니다. repository 예시는 기존 LaunchAgent를 자동 설치·load·수정하지 않습니다.

## 5. application stack 시작

runtime Compose bundle은 `runtime-config.Dockerfile`에서 build합니다. 전용 PostgreSQL, Spring API, Nginx/React Web service를 실행합니다. publish하는 두 port는 loopback에 bind하고 database는 internal로 유지해야 합니다.

초기 database는 API가 schema validation을 통과하기 전에 one-shot `migration` profile이 필요합니다. 이후 migration은 additive이며 backward compatible해야 합니다. HomeOps database 자동 backup은 의도적으로 제외했으므로, destructive migration에는 별도 승인된 logical snapshot 또는 모든 HomeOps history를 버린다는 명시적 결정이 필요합니다.

mutable tag로 바꾸지 마세요. API와 Web은 immutable `@sha256:` reference를 사용해야 하고 두 image의 `org.opencontainers.image.revision` label은 같은 40글자 full commit SHA를 가리켜야 합니다.

## 6. Tailscale Serve 구성

loopback Web binding 하나를 가리키는 tailnet 전용 HTTPS origin을 설정합니다. Funnel을 활성화하지 마세요. 다음을 확인합니다.

- tailnet identity header가 Nginx에 도착함
- allowlisted login만 인증된 API response를 받음
- 누락되거나 다른 login은 `401` 또는 `403`을 받음
- 직접 LAN 및 public Internet 접근이 실패함
- Agent listener는 loopback 전용이며 client certificate를 요구함

Tailscale CLI 문법은 바뀔 수 있습니다. 최종 명령은 현재 공식 Serve 문서를 사용하고, 활성화 전후 `tailscale serve status`를 검사하세요.

## 7. iPhone PWA 설치

iPhone을 Tailscale에 연결하고 Safari에서 Serve HTTPS origin을 연 뒤 **Add to Home Screen**을 사용합니다. fresh launch, safe-area layout, background/foreground refresh, network loss, stale-state warning, update prompting을 검증하세요. API가 cache data만으로 healthy로 표시되어서는 안 됩니다.

## 8. 선택적 자동 배포

자동 배포는 기존 home-server project와 같은 contract를 따릅니다. 검토된 `main`, 전체 validation, ARM64 image, immutable API/Web/runtime-config digest, 공유 full-SHA revision identity, GHCR, Tailscale, restricted SSH, one-shot migration, health, tailnet smoke, digest-pinned rollback입니다.

`docs/configuration.md`에 나열한 repository 값을 구성하고, `deploy/bootstrap/deploy-homeops-ci.sh.example`로 `deploy-homeops-v2` project 전용 forced-command SSH key를 설치하며, bootstrap이 수동 staging 및 rollback drill을 통과하기 전까지 `MAC_MINI_DEPLOY_ENABLED`를 false로 둡니다. 이전 v1 command grammar는 의도적으로 거부하므로, 이 workflow를 활성화하기 전에 설치한 bootstrap을 갱신하세요.

예시 bootstrap에는 placeholder가 있으며 바꾸지 않은 채로 설치하면 안전하지 않습니다.
