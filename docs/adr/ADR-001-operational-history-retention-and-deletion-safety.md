# ADR-001: Operational History Retention and Deletion Safety

- 상태: Accepted
- 결정일: 2026-08-25
- 범위: Activity 장기 운영 이력의 보존·삭제 안전성

## 배경

HomeOps Activity는 다음 다섯 PostgreSQL source를 하나의 visibility-snapshot timeline으로 projection합니다.

- `deployment`
- `backup_run`
- `incident`
- `agent_event`
- `container_action_audit`

현재 일부 derived data와 protocol ledger에는 별도 automatic retention이 있지만, 위 Activity source를 위한 generic hard-delete runtime은 없습니다. 장기 이력 row를 age만으로 삭제하면 Activity display 이외의 durable contract도 함께 사라지거나 약해질 수 있습니다.

- V12 `ingestion_event_key_ledger`의 source-scoped event key가 delayed/replayed deployment·backup ingestion의 durable resurrection barrier입니다. Business table의 unique `event_key`는 current row invariant와 defense-in-depth로 유지합니다.
- `notification_event.incident_id`는 `incident(id)`를 참조하고, notification root/child provenance는 V7 self-FK로 보호됩니다.
- Activity cursor는 PostgreSQL visibility snapshot, pagination position과 filter scope를 보존하는 versioned HMAC token이며, first-page snapshot 기준 최대 1시간의 server-enforced validity를 가집니다. Signing authority는 process-local이라 application restart가 기존 cursor를 더 일찍 invalid 처리할 수 있습니다.
- `container_action_audit`는 Activity source인 동시에 privileged control의 idempotency 및 audit authority입니다.
- HomeOps PostgreSQL에는 recurring/offsite backup이 제공되지 않으므로 hard-delete는 application release만으로 승인할 수 없는 destructive operation입니다.

## 결정

### 1. Retention class

| Class | Data | 현재 정책 |
|---|---|---|
| Existing bounded/derived retention | `host_metric_aggregate`, `processed_agent_snapshot`, `health_check_result`, terminal `notification_event` | 각 기존 retention·cleanup contract를 그대로 유지합니다. 이 ADR은 기간이나 의미를 변경하지 않습니다. |
| Operational Activity history | `deployment`, `backup_run`, `incident`, `agent_event` | `AUTO DELETE = DISABLED`. 명시적인 source policy와 아래 prerequisite가 충족될 때까지 bounded하지 않은 상태로 보존합니다. |
| Privileged audit | `container_action_audit` | Generic Activity retention으로 삭제하는 것을 금지합니다. Finite retention은 별도 Security/Audit Decision만 허용합니다. |

Operational Activity history의 현재 무기한 보존은 영구 보존 Product requirement가 아닙니다. 안전한 finite-retention prerequisite가 아직 충족되지 않았을 때 삭제하지 않는 fail-closed default입니다. Source별 명시적 policy가 없으면 삭제하지 않습니다.

### 2. Retention duration

이 ADR은 새로운 `30d`, `90d`, `1y` 같은 기간을 정하지 않습니다. Source별 finite retention 기간은 다음 항목을 근거로 별도 Decision에서 정합니다.

- operational/debug value
- replay/idempotency horizon
- Activity cursor lifetime
- FK와 producer dependency lifetime
- recovery capability
- privileged audit requirement

### 3. Activity cursor prerequisite

Activity cursor source는 process-local 256-bit 이상 random key의 HMAC-SHA-256으로 payload authenticity를 확인하고, first-page `snapshotAt` 기준 정확히 1시간의 maximum validity를 강제합니다. Continuation cursor는 원래 `snapshotAt`을 유지하므로 pagination이 validity를 연장하지 않으며, application restart는 key rotation으로 이전 cursor를 deterministic하게 invalid 처리합니다. Unsigned legacy cursor도 수락하지 않습니다.

이 bounded validity source prerequisite가 구현됐어도 Activity source automatic hard-delete는 승인되지 않습니다. Cursor가 생성될 때 보이던 row를 cursor chain이 유효한 동안 삭제하면 PostgreSQL visibility predicate만으로는 previously-visible event의 존재를 복구할 수 없습니다.

다음 hard-delete safety invariant가 우선합니다.

> A row MUST NOT be physically hard-deleted while it can be referenced by any Activity cursor that the server may still accept.

Future implementation은 다음 세 경계를 별도로 정의해야 합니다.

- **Logical visibility cutoff:** 새로 발급하는 cursor와 첫 page query가 더 이상 오래된 row를 visibility snapshot에 포함하지 않는 경계입니다. Row가 이 cutoff를 넘었다는 사실만으로 physical deletion을 허용하지 않습니다.
- **Cursor validity/invalidation boundary:** 해당 row를 관측할 수 있었던 모든 cursor가 expire되거나 server에서 deterministic하게 invalid 처리되는 경계입니다.
- **Physical deletion eligibility:** 앞의 두 경계를 통과하고 source별 idempotency, dependency와 recovery prerequisite도 충족한 뒤에만 row가 hard-delete candidate가 되는 단계입니다.

선호하는 bounded model은 다음 순서를 보장합니다.

1. Row가 logical visibility cutoff를 넘으면 그 뒤 새로 발급하는 cursor에는 해당 row를 포함하지 않습니다.
2. 해당 row를 관측했을 가능성이 있는 기존 cursor는 physical deletion 전에 expire되거나 deterministic하게 invalid 처리합니다.
3. 그 뒤에만 해당 row를 physical hard-delete candidate로 분류합니다.

Expired 또는 invalidated cursor는 deterministic한 existing invalid-cursor client error로 끝나야 하며, `ALL`과 single-type filter scope binding 및 unsigned legacy cursor rejection을 함께 검증합니다. Cursor TTL이 retention duration보다 짧다는 비교만으로는 이 ordering과 invariant를 증명할 수 없습니다. 위 invariant를 형식적으로 증명하는 다른 bounded mechanism도 허용하지만, server가 여전히 수락할 cursor와 physical deletion이 겹칠 수 없어야 합니다.

### 4. Deployment / backup idempotency prerequisite

V12는 `deployment`와 `backup_run` business history와 별개인 minimal durable replay authority인 `ingestion_event_key_ledger`를 추가합니다. `(source_type, event_key)`만 저장하며 source는 `DEPLOYMENT`와 `BACKUP`으로 제한합니다. Existing business key를 migration에서 backfill하고, DB-level business INSERT fence가 application version과 관계없이 ledger key를 먼저 reserve합니다. Reservation, business insert와 notification intent는 같은 transaction에서 commit/rollback됩니다.

구현된 contract는 다음을 만족합니다.

- 삭제한 event의 canonical `event_key` 재사용을 계속 거부합니다.
- business payload와 private metadata를 tombstone에 복제하지 않습니다.
- business history retention과 duplicate barrier lifetime을 분리합니다.
- delayed reporter retry가 historical event를 새 event로 부활시키지 않습니다.
- V12 DB 위의 pre-V12 writer도 ledger reservation 없이 deployment/backup business row를 만들 수 없습니다.
- ledger에는 FK, digest, business UUID, timestamp 또는 expiry가 없으며 source별 동일 literal key는 독립 namespace로 유지합니다.
- ledger retention은 upstream replay horizon을 증명한 뒤 별도로 결정합니다. 현재 cleanup은 없고 fail-closed로 보존합니다.

Current business row가 있으면 기존 digest·lifecycle resolver가 detailed authority입니다. Ledger만 있고 business row가 없으면 기존 ingestion-conflict taxonomy로 fail closed하며 business row나 notification을 부활시키지 않습니다. 이 prerequisite 완료는 business row hard-delete를 승인하지 않습니다.

### 5. Incident prerequisite

Incident finite retention은 최소 다음 계약을 요구합니다.

- `OPEN`과 `ACKNOWLEDGED` incident는 삭제하지 않습니다.
- `RESOLVED` incident만 candidate가 될 수 있습니다.
- `notification_event.incident_id` dependency를 명시적으로 해소합니다.
- notification root/child provenance를 accidental cascade로 삭제하지 않습니다.
- deleted incident가 producer replay 또는 recovery lifecycle을 다시 활성화하지 않게 합니다.
- Activity cursor prerequisite를 충족합니다.

FK 제거 또는 `ON DELETE CASCADE` 추가는 기본 해결책이 아니며 필요하면 별도 Data Decision으로 다룹니다.

### 6. Agent event prerequisite

`agent_event`에는 deployment/backup과 같은 external `event_key` duplicate barrier가 없습니다. 그러나 Activity source이므로 cursor prerequisite를 충족하기 전에는 자동 삭제하지 않습니다. Future implementation에서 가장 단순한 첫 candidate가 될 수 있지만, 이 ADR은 기간이나 cleanup job을 승인하지 않습니다.

### 7. Container control audit boundary

Generic Activity retention scheduler는 `container_action_audit`를 조회·삭제 대상으로 사용하면 안 됩니다. Finite retention은 별도 Security/Audit Decision에서 최소 다음을 다시 결정해야 합니다.

- control idempotency key lifetime
- audit와 incident investigation value
- principal privacy minimization
- production acceptance evidence requirement
- Activity cursor lifetime
- recovery 또는 audit export 필요 여부

### 8. Notification retention boundary

Existing `notification_event` terminal retention은 이 ADR과 별개이며 현재 status별 기간, parent 보존과 cleanup semantics를 변경하지 않습니다. Operational history retention이 notification retention을 암묵적으로 늘리거나 줄이지 않습니다. 반대로 incident row는 notification dependency가 실제로 해소되기 전까지 삭제하지 않습니다.

## Future deletion execution contract

Source별 finite retention이 별도 승인되더라도 cleanup은 다음 원칙을 지켜야 합니다.

- request/API path가 아닌 bounded scheduled batch로 실행합니다.
- deterministic oldest-first ordering과 run/transaction당 최대 row 수를 둡니다.
- current, open, nonterminal row는 fail closed로 제외합니다.
- FK/dependency가 남은 row는 skip하거나 실패시키고 cascade로 숨기지 않습니다.
- server-owned timestamp만 cutoff authority로 사용합니다.
- source별 cleanup을 독립적으로 disable할 수 있게 합니다.
- retry/restart에 안전하고 동일 row의 두 번째 delete를 correctness failure로 만들지 않습니다.
- raw payload와 private metadata를 log/metric에 기록하지 않습니다.
- deleted count와 dependency 때문에 skip한 count 같은 bounded aggregate evidence만 허용합니다.

Batch size, schedule과 retention duration은 future implementation detail이며 이 ADR에서 정하지 않습니다.

## Production destructive-activation gate

Retention code가 future release에 포함돼도 production deletion은 자동으로 승인되지 않습니다. 첫 hard-delete activation은 별도 Ops Gate에서 최소 다음을 확인합니다.

1. target database와 application release identity
2. candidate row count와 age distribution의 read-only preflight
3. active/nonterminal/dependency row count
4. default-disabled destructive cleanup switch
5. pre-delete logical backup 또는 동등하게 승인된 recovery artifact
6. isolated restore verification 또는 명시적인 recoverability acceptance
7. 첫 실행의 작은 bounded batch
8. 전후 Activity/API/application health regression과 unexpected deletion 0

Rollback은 삭제한 row를 자동으로 되살린다고 주장하지 않습니다. Accepted recovery artifact를 이용한 restore 또는 forward recovery 경계를 사전에 정의해야 하며, HomeOps 자체 recurring/offsite backup 부재를 acceptance evidence에서 숨기지 않습니다.

## 결과

- Operational Activity retention policy는 결정됐지만 deletion runtime은 구현되지 않았습니다.
- Activity cursor source에는 tamper-resistant payload와 first-page 기준 최대 1시간의 server-verifiable validity가 구현됐습니다.
- Deployment/backup event-key replay authority는 V12 minimal ledger로 business history와 분리됐습니다.
- Automatic deletion of Activity sources는 disabled 상태입니다.
- Privileged control audit는 generic retention 범위 밖입니다.
- Production destructive activation은 별도 승인 전까지 허용되지 않습니다.
- Phase 1~5의 기존 COMPLETE 상태와 existing bounded retention semantics는 변경되지 않습니다.

## 후속 순서

각 단계는 별도 Issue/PR로 다룹니다.

1. 완료: Activity cursor bounded validity/expiry contract와 source 구현
2. 완료: Deployment/backup ingestion idempotency ledger 설계와 구현
3. Evidence에 따라 가장 단순한 eligible Activity source retention 구현
4. Deployment/backup retention 구현
5. Notification dependency contract 이후 incident retention 구현
6. 별도 Security/Audit Decision을 통한 `container_action_audit` retention 검토
7. 별도 production destructive-activation acceptance gate

## 명시적 비범위

이 ADR과 현재 prerequisite source는 Activity history DELETE SQL, retention scheduler/property, ledger cleanup/expiry, retention API/UI, incident/notification FK 변경, notification retention 변경, Agent, workflow/classifier 또는 production configuration/data 변경을 구현하거나 승인하지 않습니다.
