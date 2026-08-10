# 보안 정책

## 지원 버전

첫 정식 출시 전까지 기본 branch의 최신 commit만 보안 수정 대상입니다. 정식 출시 전 interface는 하위 호환성 없이 바뀔 수 있습니다.

## 취약점 제보

exploit, secret, private address, Tailnet 상세 정보, container 환경, credential이 포함된 log 발췌, 호스트별 path가 포함된 공개 issue를 열지 마세요. 공개 repository가 생성된 뒤에는 repository 소유자의 비공개 GitHub security advisory 채널을 사용하세요.

## 보안 경계

HomeOps는 비공개 tailnet과 한 명의 관리자를 전제로 설계했습니다. 소스가 공개되어 있다고 해서 인터넷 직접 공개가 안전해지는 것은 아닙니다. 이 프로젝트는 다음을 제공하지 않습니다.

- 공개 상태 페이지
- multi-tenant 격리
- 임의 shell, Compose, Docker 명령 실행
- macOS 계정 또는 Docker Engine이 침해된 뒤의 보호

native Agent는 강한 Docker 제어 권한을 가질 수 있습니다. `sudo` 없이 실행하고, 노출하려는 Docker socket과 path만 설정하며, 문서화된 allowlist contract가 완성될 때까지 제어 기능을 비활성으로 두세요.
