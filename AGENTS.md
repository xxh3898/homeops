# HomeOps repository 실행 계약

## 1. 적용 범위와 우선순위

이 문서는 HomeOps repository 전체에 적용한다. Global `AGENTS.md`의 운영·data·Secret 보호를 약화하지 않으며, Issue, accepted ADR, repository template과 충돌하면 더 엄격한 안전 경계를 따른다.

HomeOps 작업의 canonical sources는 다음 순서로 확인한다.

1. 사용자의 현재 명시적 지시와 production stop boundary
2. 이 `AGENTS.md`
3. accepted ADR와 architecture/security/data contract
4. GitHub Issue의 IN/OUT scope와 Acceptance Criteria
5. Issue/PR template
6. roadmap와 현재 source/test

중대한 계약 충돌이나 미결 Product/Architecture/Data/Security 결정은 추측하지 않고 `DECISION_REQUIRED`로 멈춘다.

## 2. Branch와 PR 경계

- `dev`는 integration branch다.
- `main`은 Release / Production 승격 branch다.
- 일반 Issue는 최신 `dev`에서 전용 work branch를 만들고 work branch → `dev` PR 하나로 구현한다.
- `dev → main` PR은 이미 검증된 integration state만 승격하며 새 기능이나 unrelated fix를 추가하지 않는다.
- `main`, `dev`에 direct commit/push하지 않는다. Force push와 history rewrite도 금지한다.
- Release PR이 open인 동안 `dev`를 advance해 release scope를 바꾸지 않는다.
- Release merge 뒤 `main`이 `dev`보다 앞서면 feature PR에 synchronization을 섞거나 direct push하지 않고 별도 synchronization PR/gate로 먼저 맞춘다.
- Branch protection이 없더라도 이 계약을 우회하지 않는다. Ruleset 변경은 별도 GitHub configuration 작업이다.

작업 전 repository root, current branch/HEAD, working tree, `origin/main`/`origin/dev` ancestry, open PR과 Issue dependency를 확인한다. Unrelated local 변경을 덮어쓰지 않으며 필요하면 isolated worktree를 사용한다.

## 3. Issue → READY PR workflow

HomeOps의 기본 협업 흐름은 다음과 같다.

1. GPT 또는 사람이 실제 repository 상태를 조사해 Issue를 실행 계약으로 작성한다.
2. Issue에는 문제·목표, IN/OUT scope, observable Acceptance Criteria, dependency/precondition, impact, test, branch/delivery/production contract를 기록한다.
3. Codex는 `$goal-driven-development #<issue>` 호출 범위 안에서 구현, local validation, intentional commit, work branch push, Draft PR과 Hosted CI repair를 수행한다.
4. Codex는 current HEAD의 required checks, mergeability, scope, docs, classification과 actionable unresolved thread 0을 확인한 뒤에만 PR을 READY로 전환한다.
5. PR authoring과 분리된 GPT/사람 reviewer가 Issue, diff, tests, Hosted CI와 risk를 독립 검토한다.
6. 사용자가 직접 merge 여부를 결정한다.

Codex는 PR merge, Issue 임의 close, Release 생성, production deploy, migration 실행, Secret 작업, feature activation 또는 production acceptance drill을 수행하지 않는다.

## 4. 결과 상태

- `READY`: Issue contract, local/Hosted validation, docs, scope와 review gate를 모두 충족하고 PR이 Draft가 아니다.
- `DRAFT_BLOCKED`: 구현 방향은 명확하지만 CI, 권한, dependency, safe workspace 또는 external gate 때문에 완료할 수 없다. 가능한 evidence와 Draft PR을 보존한다.
- `DECISION_REQUIRED`: Product, Architecture, Data, Security, scope 또는 branch/release 결정이 필요하다. 임의 구현하지 않는다.

Base에 accepted implementation이 이미 존재해 새 diff가 필요 없으면 Issue와 branch 상태를 확인한 뒤 `READY` 또는 `DECISION_REQUIRED` 조건에 따라 판정한다.

하나라도 READY 조건이 미충족이면 READY라고 보고하지 않는다.

## 5. Implementation 경계

- One Issue = one work branch = one coherent Feature PR을 기본으로 한다.
- Acceptance에 필요한 최소 변경만 수행하고 독립 Issue를 합치지 않는다.
- Existing architecture, package, DTO, test와 script convention을 우선한다.
- Workflow, dependency, migration, public API, Agent protocol과 production configuration은 Issue IN scope일 때만 변경한다.
- Base에 merge된 Flyway migration은 수정하지 않는다. 새 migration은 forward-only additive를 우선하며 clean/upgrade compatibility를 검증한다.
- Agent validation과 persistent Agent release eligibility를 구분한다. Changed-path classifier의 `agent`, `agent_artifact`, `agent_release` 결과를 PR에 기록한다.
- Actual Secret, webhook, credential, private host/account/address, Tailnet metadata와 private path를 Issue, PR, fixture, log 또는 error에 기록하지 않는다.

## 6. Validation과 evidence

검증은 static → focused → related regression → repository full gate 순서로 실행한다.

- YAML/Markdown/template 변경은 syntax, unique ID/label, required field, code fence와 local link를 검증한다.
- Code 변경은 normal/failure/auth/validation/concurrency와 Issue-specific regression을 검증한다.
- Migration은 clean install과 upgrade path를 검증한다.
- Infrastructure 변경은 classifier, Agent release classifier, Compose render, deploy/runtime와 rollback contract를 검증한다.
- `git diff --check`, conflict marker, changed-file scope, generated drift와 Secret/private material scan은 항상 확인한다.
- PR body의 PASS는 실제 실행한 command 또는 현재 HEAD의 Hosted run으로만 기록한다. 미실행은 `NOT RUN — 이유`로 남긴다.

Hosted CI 실패는 최대 세 번의 root-cause repair cycle 안에서 최소 수정한다. Code로 해결할 수 없거나 required check가 실패하면 Draft를 유지하고 `DRAFT_BLOCKED`로 보고한다.

## 7. PR contract와 review gate

Repository PR template의 모든 section을 유지하고 N/A에는 이유를 적는다. 최소한 다음을 실제 evidence로 채운다.

- Related/Closes Issue와 ADR
- Summary, Included Scope, Explicitly Out of Scope, Implementation
- Focused/regression/full local/Hosted validation
- API, Data/Migration, Security/Privacy, Documentation impact
- Production impact matrix와 Agent release classification
- Risk, Rollback/Recovery, Merge Gate, Post-merge Acceptance, Follow-up

READY 전 최신 HEAD에서 다음을 재확인한다.

- target이 일반 Issue는 `dev`, release는 `main`
- required checks SUCCESS
- mergeable
- actionable unresolved review thread 0
- Issue Acceptance Criteria 충족
- source/docs/generated contract 동기화
- out-of-scope, Secret, 실제 사용자 data 미포함
- migration, Agent release와 production precondition 명시

## 8. Production 분리

- Feature PR → `dev` merge는 production deploy 권한이 아니다.
- `dev → main` merge는 `.github/workflows/deploy.yml`에 따라 application publish/deploy를 발생시킬 수 있으므로 별도 Release / Production Gate가 필요하다.
- Agent artifact publish는 verified `agent_release=true`일 때만 eligible하고 automatic rollout kill switch는 별도 운영 상태다.
- DB migration 적용, Secret 설치·조회·회전, notification enable, Agent rollout, container mutation과 production acceptance는 별도 승인된 Ops workflow로 수행한다.
- Post-merge acceptance 결과를 source implementation, production activation과 혼동하지 않는다.
