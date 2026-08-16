# HomeOps

HomeOps는 Docker Desktop을 실행하는 Apple Silicon Mac용 모바일 우선 셀프 호스팅 운영 대시보드입니다. 임의 셸 또는 Docker 명령 인터페이스를 노출하지 않고, 호스트 메트릭, 컨테이너 상태, 배포 메타데이터, 백업 결과 메타데이터, 인시던트 이력을 한곳에서 확인하는 것을 목표로 합니다.

소스는 공개되어 있지만 지원하는 배포 경계는 비공개입니다. 한 명의 관리자가 Tailscale Serve를 통해 PWA에 접근하는 구조이며, HomeOps를 인터넷에 직접 공개해서는 안 됩니다.

## 상태

HomeOps는 정식 출시 전 소프트웨어입니다. 현재 production에서는 읽기 전용 호스트 메트릭과 컨테이너 인벤토리, HMAC 인증 배포·백업 결과 수집, 허용 목록 기반 HTTP 서비스 점검과 인시던트 이력, 페이지네이션을 적용한 모바일 활동 타임라인이 활성화되어 있습니다. 새 설치에서 secret 또는 정확한 origin 허용 목록이 비어 있으면 해당 입력은 계속 fail closed합니다.

읽기 전용 Phase 1은 일부만 완료됐습니다. CPU, memory, disk의 bounded metric history API/UI는 구현됐지만 별도 container detail과 redacted tail log는 아직 없으며, 알림과 제한된 컨테이너 제어도 후속 마일스톤입니다. 라벨 허용 목록, 작업 잠금, 멱등성, 감사 제어가 완성되기 전까지 컨테이너 시작·중지·재시작은 의도적으로 제외합니다.

macOS Agent는 production에서 운영 중입니다. 변경 불가능한 GHCR artifact, 제한된 전용 SSH key, `current`/`previous` rollback과 fresh snapshot proof를 사용하며 live rollback·roll-forward acceptance도 완료했습니다. `main`의 full validation과 application release는 유지하지만 persistent Agent artifact는 Agent release-affecting path가 바뀔 때만 발행됩니다. 현재 `HOMEOPS_AGENT_ROLLOUT_ENABLED=false`는 검증된 capability를 미완료로 돌리는 값이 아니라 자동 CI rollout을 닫아 둔 operational kill switch입니다. 향후 마일스톤을 지원되는 동작으로 보기 전에 [구현 로드맵](docs/roadmap.md)을 확인하세요.

## 아키텍처

- `frontend`: React 19, TypeScript, Vite, TanStack Query, Tailwind CSS, PWA
- `backend`: Java 21, Spring Boot 4.1, PostgreSQL, Flyway, 서버 측 세션
- `agent`: 실제 호스트 메트릭과 제한된 Docker Engine 읽기를 담당하는 macOS Go process
- `deploy`: 일반적인 Docker Compose, Nginx, LaunchAgent 예시

runtime-config image에는 신뢰하는 프로젝트의 배포·백업 worker가 쓰는 범위 제한 호스트 측 event reporter도 포함합니다. 전송 전에 spool에 저장하고 HMAC signing을 담당하므로 caller interface를 통해 secret이 전달되지 않습니다.

API container에는 Docker socket을 절대 mount하지 않습니다. 사용자 수준의 native Agent가 호스트와 Docker Engine을 읽은 뒤 loopback mTLS ingress를 통해 범위가 제한된 snapshot을 보냅니다.

## 지원 환경

- Apple Silicon Mac
- macOS 26 또는 호환되는 지원 버전
- Docker Desktop
- Tailscale Serve
- Docker Compose

Linux agent, Kubernetes, 인터넷 공개, 다중 사용자 계정, 웹 터미널, 임의 Docker 명령, HomeOps 데이터베이스 자동 백업은 초기 범위에 포함하지 않습니다.

## 기본 안전 설정

- Tailnet 전용 브라우저 접근
- 정확한 identity allowlist와 서버 측 세션
- 브라우저 요청 CSRF 방어
- 전용 PostgreSQL 및 Docker network
- 데이터베이스 host port 미공개
- Web 및 API container에 Docker socket 미사용
- 읽기 전용 컨테이너 인벤토리
- service worker에서 API response cache 미사용
- telemetry 미수집

## 로컬 개발

개발 환경은 `compose.dev.yaml`로 정의합니다. `.env.dev.example`을 Git에서 제외된 `.env.dev.local`로 복사하고, 필수 placeholder를 모두 바꾼 뒤 repository runbook에 따라 stack을 시작하세요.

운영 network, volume, credential, certificate, backup path를 재사용하지 마세요.

고정된 frontend dependency graph에는 커밋된 `package-lock.json`이 필요합니다. 첫 CI 실행 전에 repository의 Node 24/npm 11 toolchain으로 생성하고, `docs/operations.md`의 순차 검증 gate를 따르세요.

## 현재 API 범위

- `GET /api/v1/session`
- `GET /api/v1/system/summary`
- `GET /api/v1/system/metrics/history`: `1h`, `6h`, `24h`, `7d`로 범위를 제한한 host metric aggregate 제공
- `GET /api/v1/agent/status`
- `GET /api/v1/containers`: Agent freshness metadata와 읽기 전용 인벤토리 제공
- `GET /api/v1/activity`: 범위가 제한된 안정 cursor 제공
- `GET /api/v1/services`, `/status`, `/incidents`
- `POST /api/v1/services`: 관리자 인증과 CSRF로 보호
- `POST /api/v1/internal/agent/snapshots`: loopback mTLS ingress를 통해서만 사용 가능
- `POST /api/v1/internal/ingestion/deployments`, `/backups`: production의 신뢰하는 reporter에서 활성화. ingestion secret이 비어 있는 새 설치에서는 fail closed
- `GET /actuator/health/readiness`

이 마일스톤에는 컨테이너 변경, 임의 명령, 로그, 알림 전송, 일반 네트워크 요청 endpoint가 없습니다.

## 데이터 손실 정책

HomeOps는 자체 PostgreSQL 데이터베이스를 자동 백업하지 않습니다. 데이터베이스 volume을 잃으면 메트릭 이력, 인시던트, 감사 이력, 데이터베이스 기반 설정이 사라집니다. 필수 배포 설정은 환경 및 구성 파일에서 재구성할 수 있어야 합니다.

파괴적인 schema migration에는 별도 승인된 일회성 logical snapshot 또는 데이터베이스를 초기화하고 이력을 잃는다는 명시적 결정이 필요합니다.

## 문서

- `docs/architecture.md`
- `docs/installation.md`
- `docs/configuration.md`
- `docs/security.md`
- `docs/operations.md`
- `docs/roadmap.md`

## 라이선스

Apache License 2.0입니다. `LICENSE`를 참고하세요.
