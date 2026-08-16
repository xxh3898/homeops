## 목적

<!-- 이 PR이 해결하는 문제와 필요한 이유를 적어주세요. -->

## 변경

<!-- 실제로 변경한 핵심 사항을 적어주세요. -->

## 범위

<!-- 관련 항목만 표시하세요. -->

- [ ] Backend
- [ ] Frontend
- [ ] Agent
- [ ] Infrastructure / CI/CD
- [ ] Database
- [ ] Documentation
- [ ] GitHub configuration
- [ ] Production operations

## 검증

<!-- 실제 수행한 검사와 결과만 구체적인 command, test, run URL 또는 evidence로 적어주세요. 미실행 항목은 이유를 남기세요. -->

## Production 영향

<!-- 각 항목을 YES, NO, CONDITIONAL, N/A 중 하나로 판정하고 짧은 근거를 적어주세요. -->

| 항목 | 판정 | 설명 |
|---|---|---|
| Application deploy |  |  |
| Agent artifact publish |  |  |
| Agent rollout |  |  |
| DB migration |  |  |
| GitHub configuration |  |  |
| Production runtime |  |  |

## Security

<!-- Secret, credential, private metadata, network boundary, permission 영향을 적으세요. 없으면 `없음`. 실제 민감 값은 기록하지 마세요. -->

## Merge Gate

- [ ] Required checks PASS
- [ ] Actionable unresolved review thread 0
- [ ] 필요한 production precondition 확인 또는 N/A 근거 작성
- [ ] Rollback / recovery 필요 여부 확인 또는 N/A 근거 작성

## Post-merge Acceptance

<!-- Production 영향이 있으면 application health, Tailnet readiness, Agent identity, migration, Activity, monitoring 등 merge 후 확인할 항목을 적으세요. 영향이 없으면 `N/A`. -->

## 관련 Issue / ADR

- Closes #
- Related #
- ADR:
