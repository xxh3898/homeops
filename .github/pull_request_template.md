<!--
일반 Issue PR은 work branch → dev입니다. dev → main PR은 Release / Production 승격 경계이며 새 기능을 섞지 않습니다.
실제 수행한 검증과 확인된 영향만 기록하고 Secret, credential, private endpoint 또는 private deployment metadata를 남기지 마세요.
-->

## 관련 Issue / ADR

- Closes #
- Related #
- ADR: N/A — 이유

## Summary

<!-- 해결하는 문제, 목표와 필요한 이유를 간결하게 적으세요. -->

## Included Scope

<!-- 관련 항목만 표시하고 실제 변경을 적으세요. -->

- [ ] Backend
- [ ] Frontend
- [ ] Agent
- [ ] Infrastructure / CI/CD
- [ ] Database
- [ ] Documentation
- [ ] GitHub configuration
- [ ] Production operations

## Explicitly Out of Scope

<!-- 이번 PR에서 의도적으로 제외한 항목과 후속 경계를 적으세요. -->

## Implementation

<!-- 핵심 구현, data/control flow와 지킨 invariant를 적으세요. -->

## Validation

<!-- 실제 evidence만 기록하세요. 실행하지 않은 항목은 `NOT RUN — 이유`로 적으세요. -->

- Focused:
- Related regression:
- Full local gate:
- Hosted CI:
- Manual / browser / production: N/A — 이유

## Contract Impact

- API:
- Data / Migration:
- Security / Privacy:
- Documentation:

## Production Impact

<!-- YES, NO, CONDITIONAL, N/A 중 하나와 근거를 적으세요. -->

| 항목 | 판정 | 설명 |
|---|---|---|
| Application deploy |  |  |
| Agent artifact publish |  |  |
| Agent rollout |  |  |
| DB migration |  |  |
| GitHub configuration |  |  |
| Production runtime / data |  |  |

## Migration Impact

<!-- Migration이 없으면 `N/A — schema/data 변경 없음`. 있으면 clean/upgrade compatibility, rollback과 destructive 여부를 적으세요. -->

## Agent Release Classification

- `agent=`
- `agent_artifact=`
- `agent_release=`
- 근거:

## Risk

<!-- 알려진 failure mode, residual risk와 관찰할 signal을 적으세요. -->

## Rollback / Recovery

<!-- 실패 시 code/config/data/runtime별 복원 방법을 적으세요. N/A이면 이유를 적으세요. -->

## Merge Gate

- [ ] Issue Acceptance Criteria 충족
- [ ] Required checks가 현재 HEAD에서 PASS
- [ ] Actionable unresolved review thread 0
- [ ] Scope / generated drift / Secret scan 확인
- [ ] Migration / Agent / production precondition 확인 또는 N/A 근거 작성
- [ ] Rollback / recovery 확인 또는 N/A 근거 작성
- [ ] PR이 올바른 target branch를 사용

## Post-merge Acceptance

<!-- dev merge 뒤 확인과 별도 dev → main release/production gate를 구분하세요. 영향이 없으면 N/A와 근거를 적으세요. -->

## Follow-up

<!-- 별도 Issue, Release PR 또는 production acceptance로 남길 항목을 적으세요. 없으면 `NONE`. -->
