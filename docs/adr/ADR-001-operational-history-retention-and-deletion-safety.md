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

- `deployment.event_key`와 `backup_run.event_key` unique row는 delayed/replayed ingestion을 막는 현재 duplicate barrier입니다.
- `notification_event.incident_id`는 `incident(id)`를 참조하고, notification root/child provenance는 V7 self-FK로 보호됩니다.
- Activity cursor는 PostgreSQL visibility snapshot, pagination position과 filter scope를 보존하지만 bounded validity/expiry를 검증하지 않습니다.
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

현재 Activity cursor에는 server-enforced expiry가 없으므로 Activity source automatic hard-delete는 승인되지 않습니다. Cursor가 생성될 때 보이던 row를 cursor chain이 유효한 동안 삭제하면 PostgreSQL visibility predicate만으로는 previously-visible event의 존재를 복구할 수 없습니다.

Hard-delete 전에는 다음 중 하나가 필요합니다.

1. 선호안: server-owned issued/expiry semantics를 가진 bounded cursor validity를 추가합니다. Expired cursor는 deterministic한 existing invalid-cursor client error로 끝나고, retention cutoff보다 오래 유효한 cursor가 없어야 합니다. `ALL`과 single-type filter scope binding 및 legacy cursor compatibility를 함께 검증합니다.
2. 대안: 삭제 candidate가 어떤 valid Activity cursor에서도 참조될 수 없음을 형식적으로 보장하는 다른 bounded contract를 채택합니다.

### 4. Deployment / backup idempotency prerequisite

`deployment`와 `backup_run` business history를 삭제하기 전에 row history와 별개인 durable replay authority가 필요합니다. 선호 방향은 minimal ingestion idempotency tombstone/ledger입니다.

Future implementation은 다음을 모두 만족해야 합니다.

- 삭제한 event의 canonical `event_key` 재사용을 계속 거부합니다.
- business payload와 private metadata를 tombstone에 복제하지 않습니다.
- business history retention과 duplicate barrier lifetime을 분리합니다.
- delayed reporter retry가 historical event를 새 event로 부활시키지 않습니다.
- tombstone retention은 upstream replay horizon을 증명한 뒤 별도로 결정합니다.

Business row를 삭제해 V1 unique barrier를 잃는 방식은 허용하지 않습니다.

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
- Automatic deletion of Activity sources는 disabled 상태입니다.
- Privileged control audit는 generic retention 범위 밖입니다.
- Production destructive activation은 별도 승인 전까지 허용되지 않습니다.
- Phase 1~5의 기존 COMPLETE 상태와 existing bounded retention semantics는 변경되지 않습니다.

## 후속 순서

각 단계는 별도 Issue/PR로 다룹니다.

1. Activity cursor bounded validity/expiry contract와 구현
2. Deployment/backup ingestion idempotency tombstone 설계와 구현
3. Evidence에 따라 가장 단순한 eligible Activity source retention 구현
4. Deployment/backup retention 구현
5. Notification dependency contract 이후 incident retention 구현
6. 별도 Security/Audit Decision을 통한 `container_action_audit` retention 검토
7. 별도 production destructive-activation acceptance gate

## 명시적 비범위

이 ADR은 DELETE SQL, retention scheduler/property, migration/schema/FK, cursor format/TTL, tombstone, API/UI, notification retention, Agent, workflow/classifier 또는 production configuration/data 변경을 구현하거나 승인하지 않습니다.
