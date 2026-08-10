# 기여 안내

HomeOps는 `dev`를 통합 branch, `main`을 릴리스 branch로 사용합니다.

1. 최신 `dev`에서 범위가 명확한 branch를 만듭니다.
2. 운영 동작을 다루는 test를 추가하거나 갱신합니다.
3. 통합 작업은 `dev`를 대상으로 pull request를 엽니다.
4. 검토를 마친 `dev` → `main` pull request로 변경 사항을 릴리스합니다.

fork에서 열린 pull request는 읽기 전용 validation job만 실행합니다. 배포 credential을 받지 않으며 HomeOps 운영자의 Mac에서 실행하지 않습니다.

실제 `.env` 값, certificate, host name, 개인 email 주소, private IP 주소, Tailnet 데이터, webhook URL, Docker inspect 출력, container 환경 값을 commit, issue, test fixture에 포함하지 마세요.
