# open-rush

동시 요청 환경에서 정확성과 성능을 비교하는 콘서트 예매 백엔드입니다. 헥사고날 아키텍처와 DDD를 지향합니다.

공연·회차·좌석 관리, 예매, 동시성 제어, 결제와 대기열을 다룹니다.

## 도메인과 포트 구조

최상위 업무 패키지는 `concert`, `inventory`, `quota`, `reservation`, `payment`, `queue`입니다. 각 도메인은 `domain`, `application`, `application.provided`, `application.required`, `adapter.input`, `adapter.output`으로 구성합니다. 필요한 어댑터만 두며 도메인 간 호출은 상대 도메인의 입력 포트를 직접 사용합니다.

전역 빈 설정은 `config`, 공용 JPA 기반 코드는 `support.persistence`, 공용 HTTP 오류 처리는 `support.web`에 둡니다. 업무 예외는 해당 도메인에 유지합니다. 도메인 규칙 예외는 `domain.exception`, 애플리케이션 예외는 `application.exception`으로 통일합니다. 다른 도메인은 입력 포트와 함께 호출 결과를 나타내는 예외를 참조할 수 있습니다. 대기열 설정은 `queue.*`, 예약 홀드와 만료 설정은 `reservation.*`을 사용하며 기본값은 `application.yaml`에서 확인할 수 있습니다.

`provided`에는 애플리케이션이 제공하는 유스케이스와 입력·결과를 두고, `required`에는 유스케이스가 필요로 하는 저장소와 외부 연동 포트를 둡니다. 저장소 포트는 아래와 같이 도메인 단위로 묶습니다. 예약 생성 요청의 멱등 기록도 `ReservationRepositoryPort`에 포함합니다. PG 연동과 만료 트랜잭션 설정은 저장소와 역할이 달라 별도 포트로 유지합니다.

| 도메인        | 저장소 포트                                                                     |
|---------------|---------------------------------------------------------------------------------|
| `concert`     | `ConcertRepositoryPort` — 공연과 회차 조회                                      |
| `inventory`   | `InventoryRepositoryPort` — 좌석 조회, 홀드, 확정과 반환                        |
| `quota`       | `PurchaseQuotaRepositoryPort` — 한도 조회, 생성과 수량 변경                     |
| `reservation` | `ReservationRepositoryPort` — 예약 조회, 저장, 상태 전환, 잠금과 생성 멱등 기록 |
| `reservation` | `ReservationPaymentRepositoryPort` — 예약 결제 준비와 승인 이력 조회 및 저장    |
| `queue`       | `QueueStorePort` — 대기표와 입장 허가 상태 관리                                 |

| 도메인        | 소유 데이터와 책임                                                  |
|---------------|---------------------------------------------------------------------|
| `concert`     | 공연, 회차, 예매 기간과 공연에 설정된 구매 제한 조회                |
| `inventory`   | 회차별 좌석 가격과 재고, 홀드, 확정, 반환                           |
| `quota`       | 사용자별 공연 구매 수량 확보와 반환                                 |
| `reservation` | 예약과 가격 스냅샷, 결제 거래 이력, 예약 확정과 환불 필요 상태 관리 |
| `payment`     | 예약 정보에 의존하지 않는 PG 승인, 조회와 취소                            |
| `queue`       | 대기표와 입장 허가, Redis 저장 어댑터                              |

각 도메인의 기본 구현은 `ConcertService`, `InventoryService`, `QuotaService`, `ReservationService`, `PaymentService`, `QueueService`입니다. 예약 생성의 충돌 재시도, 만료 배치의 건별 트랜잭션, PG 호출 전후의 트랜잭션 분리가 필요한 조율 클래스는 별도로 유지합니다. `output.local` 어댑터는 사용하지 않습니다.

저장소는 도메인별 출력 포트와 JPA/Querydsl 어댑터로 연결하고, PG는 결제 도메인의 `PaymentGatewayPort`와 Demo PG 어댑터로 연결합니다. 예약은 재고와 구매 한도 도메인의 엔티티나 저장소를 직접 참조하지 않습니다. 예약 결제 거래와 처리 결과는 `reservation`이 소유하고, `payment`에는 예약 ID나 예약 처리 상태를 전달하지 않습니다. 공개 입력 포트에서는 DTO와 값 객체를 사용하며, 엔티티 간의 도메인 외부 참조는 ID로 저장합니다. 도메인 객체와 JPA 엔티티를 함께 사용하는 방식은 유지합니다.

PersistenceAdapter는 Repository에 DB 작업을 위임합니다. EntityManager, Querydsl과 직접 실행하는 SQL은 Repository 구현에서 사용합니다. 좌석과 예약 항목의 금액은 별도 값 객체 없이 원 단위 `amount: Long`으로 저장하며, 예약 합계 `totalAmount`도 Long입니다. 음수 검증과 합산 오버플로 검사는 유지합니다.

현재는 단일 DB를 사용하며 예약 생성, 재고 홀드, 한도 확보가 같은 트랜잭션에 참여합니다. 테이블과 DB 외래 키는 유지합니다. PG 호출은 DB 트랜잭션 밖에서 실행합니다. 멀티 모듈, 서비스별 DB, Outbox와 Kafka는 후속 단계입니다. 도메인 간 구현체 및 엔티티 참조를 제한하는 `DomainBoundariesTest`를 제공합니다.

DB 기본 키와 외래 키는 `id`/`...Id`, 결제 시도 UUID는 `attemptNumber`, UUID 기반 PG 주문 번호는 `pgOrderNumber`로 구분합니다. 예약 결제 요청과 응답도 `attemptNumber`를 사용합니다. Demo PG의 외부 JSON 필드 `orderId`는 해당 API 규약을 유지하고 어댑터에서 내부 주문 번호와 변환합니다. 기존 DB 컬럼은 V7 마이그레이션에서 `attempt_number`, `pg_order_number`로 이름만 변경하여 이력을 보존합니다.

## 개발 환경 실행

JDK 25와 Docker Compose가 필요합니다. Compose로 PostgreSQL 18, Redis와 Demo PG를 실행한 뒤 애플리케이션을 시작합니다.

```shell
docker compose up -d --wait
./gradlew bootRun
```

개발용 DB 접속 정보는 `localhost:5432`, 데이터베이스·사용자·비밀번호는 모두 `open_rush`입니다. 접속 설정을 변경할 때는 `docker-compose.yml`과
`src/main/resources/application.yaml`을 함께 수정합니다.

애플리케이션 시작 시 Flyway가 스키마를 적용하고 Hibernate가 엔티티 매핑을 검증합니다.

## Redis 대기열

대기열은 기존처럼 `queue.enabled=true`일 때 활성화합니다. Redis 기본 주소는 `localhost:6379`이며 `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`로 변경할 수 있습니다. 같은 Redis와 `queue.key-prefix`를 사용하는 서버는 대기표, 입장 속도, 동시 점유 한도를 공유합니다. 같은 대기열에 참여하는 서버의 `queue.*` 정책 설정도 동일하게 맞춰야 합니다.

```shell
./gradlew bootRun --args='--queue.enabled=true'
```

`POST /reservation-queue`에 `userId`를 전달해 등록하고 `GET /reservation-queue/{ticketId}`로 상태를 조회합니다. 같은 사용자의 유효한 대기표는 재사용합니다. `ADMITTED`가 되면 예약 생성 요청에 `X-Queue-Ticket` 헤더로 대기표를 전달합니다. 예약 생성 멱등성 키에 해당하는 기존 성공 결과를 반환하는 경우에는 새 입장권을 요구하지 않습니다.

Lettuce를 사용하는 `StringRedisTemplate`으로 접근하며, Lua에서 검사와 상태 변경을 함께 실행합니다. 스크립트는 시작할 때 `SCRIPT LOAD`로 미리 등록하고 `EVALSHA`로 실행합니다. Redis 재시작이나 `SCRIPT FLUSH` 이후 `NOSCRIPT`가 발생하면 다시 로드한 뒤 실행합니다. 통신 타임아웃처럼 실행 여부가 불확실한 오류에 대해서는 상태 변경 명령을 자동 재시도하지 않습니다.

대기표는 `WAITING → ADMITTED → PROCESSING`으로 전환됩니다. 대기와 미사용 입장권뿐 아니라 처리 중 점유에도 만료 기한을 두어 서버 종료 시 회수합니다. 처리 종료 시 고유 토큰으로 소유권을 확인하고 점유를 반환하며, 반환 실패는 로그로 남기고 만료 처리가 회수합니다. Redis 장애로 새 권한을 얻지 못하면 `503 QUEUE_UNAVAILABLE`을 반환합니다.

키 접두사 기본값은 `queue:{reservation}`입니다. `{reservation}` 해시 태그로 관련 키를 같은 Redis Cluster 슬롯에 배치할 수 있도록 이름을 정했습니다. 기본 개발 환경은 단일 Redis이며, 아래의 `redis-sentinel` 프로필로 Sentinel 연결도 지원합니다.

| 키 접미사 | 자료형 | 내용 |
|-----------|--------|------|
| `sequence` | String | 등록 순번 카운터 |
| `waiting` | Sorted Set | 대기표 ID와 등록 순번 |
| `waiting-expiry` | Sorted Set | 대기표 ID와 대기 만료 시각 |
| `admitted` | Sorted Set | 대기표 ID와 입장 허가 만료 시각 |
| `processing` | Sorted Set | 대기표 ID와 처리 점유 만료 시각 |
| `tickets` | Hash | 대기표 ID별 사용자, 상태, 만료 시각과 처리 토큰 |
| `users` | Hash | 사용자 ID와 현재 대기표 ID |
| `next-admission-at` | String | 다음 입장이 가능한 시각 |

시간은 Redis `TIME`을 기준으로 합니다. 대기 기한은 기본 120초, 입장 허가는 10초, 처리 점유는 30초입니다. 요청과 기본 10ms 간격 주기 작업에서 만료 항목을 정리하고 다음 사용자를 입장시킵니다. Sorted Set 항목에 키 TTL을 부여하는 방식이 아니라 만료 인덱스에서 찾아 관련 상태를 함께 삭제합니다. 한 번의 실행에서 상태별 최대 `queue.cleanup-batch-size`개(기본 100개)를 정리하며, 대량 만료 시 여러 번에 걸쳐 회수하므로 잠시 수용 한도나 대기 순번이 보수적으로 표시될 수 있습니다. 입장은 한 번의 실행에 최대 한 명이며, 유휴 시간 동안 사용하지 않은 입장 횟수를 누적하지 않습니다.

처리 중 점유의 만료는 실제 DB 트랜잭션을 중단하지 않습니다. `queue.processing-ttl-ms`는 정상적인 예약 처리 시간보다 충분히 크게 설정해야 하며, 좌석과 구매 한도의 정합성은 기존 DB 트랜잭션으로 보장합니다. 대기열은 전역 한 개이며 공연 회차별로 분리하지 않습니다. Redis Cluster는 구성하지 않습니다.

## Redis Sentinel 실행과 장애 전환

```shell
./gradlew bootJar
docker compose -f docker-compose.sentinel.yml up -d --wait
```

독립적인 `open-rush-sentinel` Compose 프로젝트로 Redis 3개(primary 1개와 replica 2개), Sentinel 3개, PostgreSQL과 앱 2개를 실행합니다. 앱 주소는 `http://127.0.0.1:18201`, `http://127.0.0.1:18202`입니다. `http/reservation-queue.http`의 `baseUrl`을 변경해 요청할 수 있습니다. 이 환경의 DB는 개발 DB와 별개이며 데이터는 자동으로 생성하지 않습니다. 예약 요청에는 이 DB에 준비한 공연과 좌석 ID를 사용해야 합니다.

`application-redis-sentinel.yaml`에서 master 이름 `open-rush`와 Sentinel 세 주소를 지정하며, `REDIS_SENTINEL_MASTER`, `REDIS_SENTINEL_NODES`로 변경할 수 있습니다. 앱은 고정된 Redis 주소 대신 Sentinel에서 현재 primary를 찾아 연결합니다. 대기열은 primary에서 읽고 쓰며 Lettuce가 연결 변경을 처리합니다. Sentinel이 반환하는 내부 호스트 이름에 접근할 수 있도록 앱도 동일한 Docker 네트워크에서 실행합니다. 이 프로필의 기본 주소를 호스트에서 `bootRun`할 때 그대로 사용할 수는 없습니다.

Sentinel quorum은 2이고 장애 판정 시간은 2초, failover timeout은 15초입니다. 이 값은 로컬 기능 검증용이며 실제 복구 시간 상한은 아닙니다. Redis는 AOF와 `min-replicas-to-write=1`을 사용해 정상 replica가 하나도 없으면 쓰기를 거절합니다. 이 설정도 비동기 복제로 인한 최근 쓰기 유실 가능성을 제거하지는 않습니다. 운영에서는 Redis와 Sentinel을 서로 독립적으로 장애가 발생하는 서버나 가용 영역에 배치해야 합니다. [Redis Sentinel 공식 문서](https://redis.io/docs/latest/operate/oss_and_stack/management/sentinel/)

Redis와 Sentinel의 실행 설정 파일은 각 노드의 `/data` 볼륨에 보관합니다. 초기화 스크립트는 파일이 없을 때만 템플릿을 복사합니다. Sentinel이 변경한 primary 주소와 Redis의 `replicaof` 설정을 재시작 때 초기값으로 덮어쓰지 않습니다. 이전 primary는 복구 후 새 primary의 replica로 합류하며 자동으로 primary 역할을 되찾지 않습니다.

자동 검증은 다음과 같이 실행합니다.

```shell
python3 benchmarks/run-sentinel.py
# 장애 전환 이후 컨테이너를 남겨 확인하려면:
python3 benchmarks/run-sentinel.py --keep
```

실행마다 별도의 Compose 프로젝트와 새 볼륨을 만듭니다. 전용 DB에 공연·좌석을 준비하고 primary를 `SIGKILL`로 강제 종료합니다. 정상 quorum 및 Sentinel 한 대 중단 조건에서 자동 승격, 두 앱의 재연결, 기존 대기표로 예약 생성, 새 쓰기, Lua 캐시 복구, 이전 primary의 replica 복귀와 설정 저장을 검증합니다. 복구 중에도 두 앱에 등록·조회 요청을 보내 HTTP 결과를 기록합니다. 테스트용 기존 대기표는 동일 연결의 `SET` 후 `WAIT 2`로 두 replica에 복제됐는지 확인한 뒤 장애를 주입합니다. 운영의 모든 쓰기에 동일한 보장을 추가한 것은 아닙니다.

결과와 로그는 `build/benchmark/sentinel-<실행시각>/`에 저장하며 기본 실행은 자신이 만든 컨테이너와 볼륨을 정리합니다. `--keep` 사용 시 결과 JSON의 `project` 이름으로 해당 환경을 식별할 수 있습니다. 실행 방법과 측정 결과는 [벤치마크 안내](benchmarks/README.md)에도 정리합니다.

## 도메인과 예매 정책

- `Concert`와 `Performance`는 공연과 회차를 나타냅니다.
- `PerformanceSeat`는 회차별 좌석 재고와 현재 점유 상태를 관리합니다.
- `Reservation`과 `ReservationItem`은 한 사용자의 예매와 좌석별 가격을 기록합니다.
- `ConcertPurchaseQuota`는 사용자별로 같은 공연의 모든 회차에서 확보한 수량을 관리합니다.
- 한 예매에는 같은 회차의 중복 없는 좌석을 1개 이상 선택하며, 공연별 1인 구매 한도를 적용합니다.
- 선점 기한은 기본 5분이며 예매 마감을 넘지 않습니다. 확정과 선점 취소는 기한 전에 검증을 통과해야 합니다.
- 예매 전체를 확정·취소·만료하며, 확정된 예약의 취소와 전액 환불을 지원합니다. 부분 취소는 지원하지 않습니다.

좌석 선점·예매 저장·구매 한도 확보를 트랜잭션으로 처리하고, 동시성 제어를 통해 예매 정합성을 보장하는 것을 목표로 합니다.

## 예매 생성 재시도

`POST /reservations`에 선택 헤더 `Idempotency-Key`를 지정할 수 있습니다. 같은 사용자가 같은 키로 같은 회차와 좌석을 요청하면 최초 성공 응답의 `201`, `Location`, 본문을 반환합니다. 좌석 순서는 바뀌어도 같은 요청으로 봅니다. 새 예매에는 유효한 입장 허가가 필요하며, 저장된 결과를 재전달할 때는 기존 입장 허가가 소비됐어도 응답을 받을 수 있습니다.

키는 공백 없는 ASCII 1~128자입니다. 같은 키를 다른 회차나 좌석에 재사용하면 `409 IDEMPOTENCY_KEY_CONFLICT`를 반환합니다. 실패해 롤백된 요청의 키는 다시 사용할 수 있습니다. 재전달 응답에는 최초 선점 시각과 상태가 들어 있습니다.

## 예매 상세 조회

`GET /reservations/{reservationId}?userId=1`로 예매의 현재 저장 상태, 선점·만료·확정 시각, 좌석 ID와 예매 금액을 조회합니다. 생성 응답의 `Location` 경로에 사용자 ID를 붙여 호출할 수 있습니다. 예매가 없거나 해당 사용자 소유가 아니면 `404`를 반환합니다. 만료 상태는 만료 작업이 처리한 뒤 조회 응답에 반영됩니다.

## 예매 선점 취소

`POST /reservations/{reservationId}/cancel-hold`에 `{"userId": 1}`을 전달합니다.

- 본인의 만료 전 `HELD` 예매만 전체 취소할 수 있습니다. 성공 시 `204`, 잘못된 요청은 `400`, 취소할 수 없는 예매는 `409`를 반환합니다.
- 예매가 `CANCELLED`로 바뀌고 선점한 좌석과 구매 한도가 반환됩니다. 이 과정은 하나의 트랜잭션으로 처리하며, 중복·동시 취소 시 한 요청만 성공합니다.
- 선점 취소 API는 PG를 호출하지 않습니다. 확정된 예약은 아래의 `/cancel` API로 취소합니다.

## 예매 선점 만료

- `HELD`이고 `expiresAt <= 현재 시각`인 예매를 `EXPIRED`로 전환하고 선점한 좌석과 구매 한도를 반환합니다.
- 애플리케이션 시작 10초 후부터 자동 실행하며, 이전 실행 완료 후 10초 뒤 다시 실행합니다. 별도의 HTTP API는 제공하지 않습니다.
- 대상을 ID 순서로 100건씩 조회하고 예매 한 건마다 독립된 트랜잭션으로 처리합니다. 조건부 UPDATE에 성공한 요청만 반환하므로 여러 서버에서 동시에 실행해도 중복 반환하지 않습니다.
- 만료 트랜잭션에는 기본 1초의 잠금 대기 제한을 적용합니다. 예매·한도·좌석 잠금 대기가 제한을 넘으면 해당 예매를 롤백하고 후속 예매를 계속 처리합니다. 제한은 개별 잠금 대기에 적용되며 전체 배치 실행 시간의 상한은 아닙니다.
- 반환 실패 시 해당 예매의 상태 변경까지 롤백하고 오류를 기록합니다. 뒤의 예매는 계속 처리하며, 실패한 예매는 다음 실행에서 재시도합니다. 이미 취소·확정·만료됐거나 아직 기한 전인 예매는 건너뜁니다.
- 확정과 선점 취소는 검증 시각을 기준으로 기한을 판단합니다. 기한 전에 검증을 통과한 요청은 행 잠금을 기다리는 동안 기한이 지나도 상태 갱신 시 예매가 여전히 `HELD`이면 성공할 수 있습니다. 다른 요청이나 만료 작업이 먼저 상태를 변경하면 대기 중이던 요청은 거절됩니다.
- 기한이 지난 뒤에도 `HELD`인 예매는 만료 작업이 상태를 전환하고 좌석과 한도를 반환합니다. 실제 반환까지는 실행 간격과 처리 시간만큼 지연될 수 있습니다.

`application.yaml`의 `reservation.expiration`에서 실행 여부(`enabled`), 완료 후 대기 간격(`fixed-delay`), 최초 대기 시간(`initial-delay`), 조회 크기(`batch-size`), 잠금 대기 제한(`lock-timeout-ms`, 양수 밀리초)을 조정할 수 있습니다. 잠금 대기 설정은 만료 트랜잭션에만 적용하며 커밋·롤백 후 풀 연결의 원래 설정으로 돌아갑니다.
벤치마크 프로필에서는 자동 만료를 비활성화합니다.

## 결제 성공 처리

`RecordPaymentSuccessUseCase`는 결제 성공과 가맹점 주문의 예매 연결을 검증한 입력을 받습니다. 예매 금액 단위는 원(KRW)입니다.
금액과 통화가 일치하면 예매 확정과 결제 결과를 같은 트랜잭션으로 기록합니다. 결제사와 결제 ID가 같고 나머지 입력도 같으면 기존 결과를 반환합니다.
금액이나 통화가 다르거나 예매를 확정할 수 없으면 `REFUND_REQUIRED`를 기록합니다. 예약 결제 승인 유스케이스는 이후 내부 환불 유스케이스를 호출합니다.

`reservation_payment_tx`는 예매별 결제 이력을 저장합니다. 승인 준비(`APPROVAL_PREPARED`), 승인 완료(`APPROVED`), 취소 준비(`CANCEL_PREPARED`), 취소 완료(`CANCELED`)는 각각 별도의 행입니다. 같은 결제 시도의 재요청에는 준비 단계에서 저장한 PG 주문 ID와 승인 멱등성 키를 재사용합니다. 과거 결제 성공 기록은 승인 완료 행으로 이전합니다.

### 예약 결제 승인 API

`POST /reservations/{reservationId}/confirm`은 `userId`, `attemptNumber`(UUID), `paymentKey`를 받습니다. 금액은 저장된 예약 금액을 사용합니다. 같은 예약과 결제 시도에는 같은 `paymentKey`를 사용해야 하며, 다른 키로 재요청하면 `409 PAYMENT_CONFLICT`를 반환합니다. 요청 예시는 [reservation-payment.http](http/reservation-payment.http)에 있습니다.

준비 이력을 커밋한 뒤 DB 트랜잭션 밖에서 PG 주문을 조회하고, 아직 승인되지 않았고 예약이 유효하면 승인합니다. 승인 응답은 `DONE`, 주문 ID, 결제 키를 검증하며 금액과 통화 불일치는 환불 필요로 기록합니다. 결과 저장과 예약 확정은 하나의 새 트랜잭션으로 처리합니다. 예약 확정 실패로 환불이 필요하면 내부 환불 유스케이스를 이어서 호출합니다. 승인과 환불이 완료된 동일 요청은 PG 호출 없이 저장된 결과를 반환합니다.

| HTTP | status 또는 code        | 의미                                            |
|------|-------------------------|-------------------------------------------------|
| 200  | `RESERVATION_CONFIRMED` | 승인 이력 저장과 예약 확정 완료                 |
| 200  | `REFUND_REQUIRED`       | PG 승인 기록은 있으나 환불이 아직 완료되지 않음 |
| 200  | `REFUNDED`              | 예약 확정 실패로 발생한 결제의 전액 환불 완료   |
| 202  | `PENDING`               | PG 승인 여부 확인 필요                          |
| 409  | `PAYMENT_CONFLICT`      | 같은 시도의 결제 키 또는 기존 승인 정보 충돌    |
| 422  | `PAYMENT_REJECTED`      | PG가 승인을 명시적으로 거절                     |

타임아웃과 PG 서버 오류가 발생하면 같은 주문을 한 번 조회합니다. 조회로도 승인 여부를 확인할 수 없으면 `PENDING`을 반환합니다. 클라이언트는 동일한 요청 본문으로 재요청하여 결과를 확인할 수 있습니다. 기존 시도는 예약이 만료되어도 조회할 수 있지만 새 승인은 하지 않습니다. 승인 결과 확인은 현재 재요청으로 복구합니다. PG 승인 후 DB 저장이 실패해도 준비 이력이 남으므로 재요청에서 주문 조회로 복구합니다.

예약 결제 유스케이스의 입력은 `RecordPaymentSuccessCommand`, 승인 후 예약 처리 결과는 `ReservationPaymentApprovalResult`로 전달합니다. 두 객체는 `reservation.application.provided`에 두며, 거래 엔티티는 이 DTO에 의존하지 않습니다. `ReservationBookingPolicy`는 예약 생성 시 예매 가능 시간과 구매 제한을 판단하는 도메인 객체입니다.

취소 준비는 승인 이력에서 `CANCEL_PREPARED` 행을 추가하는 방식입니다. 취소 시도 UUID인 `cancellationNumber`, 취소 금액, 사유와 고정 멱등 키를 보존하고, 완료 시 같은 취소 번호로 `CANCELED` 행을 추가합니다. V8 마이그레이션은 기존 취소 이력을 보존하면서 준비와 완료의 중복 저장을 제한합니다. 예약 확정 실패 보상 환불은 `REFUND_REQUIRED` 승인을 전액 환불하며, 예약 상태·좌석·구매 한도를 변경하지 않습니다. 확정 예약 취소에서는 예약 취소와 자원 반환을 먼저 커밋하고 저장된 취소 준비 이력으로 환불합니다. 환불용 Controller는 두지 않습니다. 준비 이력을 먼저 커밋하고 DB 트랜잭션 밖에서 PG 취소 API를 호출한 뒤, 예약 잠금 안에서 완료 이력을 저장합니다. 같은 승인에는 같은 환불 번호와 멱등 키를 사용하며, 동시 요청도 준비와 완료를 각각 한 행만 저장합니다. 타임아웃, PG 거절 또는 잘못된 응답에는 완료 이력을 저장하지 않고 `REFUND_REQUIRED`를 반환합니다. 미완료 환불은 실행 가능한 시각이 되면 스케줄러 또는 동일한 승인 요청으로 실행됩니다. 완료된 환불은 저장된 결과를 반환합니다. 승인 이력의 `REFUND_REQUIRED`는 당시 판단으로 보존하고 최종 환불 여부는 `CANCELED` 이력으로 확인합니다. 예약 취소 API는 전액 환불만 요청합니다.

### 확정 예약 취소 API

`POST /reservations/{reservationId}/cancel`은 `userId`와 `cancelReason`(공백 불가, 최대 255자)을 받습니다. `CancelReservationUseCase`는 예약을 잠그고 소유자와 확정 상태를 검증한 뒤, 예약 취소·구매 한도 반환·확정 좌석 반환·`CANCEL_PREPARED` 저장을 같은 트랜잭션으로 커밋합니다. 한도나 좌석 반환에 실패하면 전체가 롤백되며 PG를 호출하지 않습니다. 선점 취소는 기존 `/cancel-hold`를 사용합니다.

그다음 DB 트랜잭션 밖에서 전액 환불을 한 번 시도합니다. 성공 응답은 `200 {"reservationId":1,"status":"CANCELLED","refundStatus":"REFUNDED"}`이며, PG 장애나 거절 시에는 `refundStatus: "PENDING"`을 반환합니다. 환불 실패에도 예약과 자원 반환은 되돌리지 않습니다. 같은 사유로 취소를 재요청하면 작업 상태에 따라 완료 결과를 읽거나 실행 가능한 환불을 다시 시도합니다. 예약 자원은 최초 취소에서 한 번만 반환합니다. 사유를 변경한 재요청은 충돌로 거절합니다. 이미 확정된 무료 예약은 PG 호출 없이 취소하며 `refundStatus`는 null입니다. 무료 예약의 공개 확정 흐름은 아직 별도 구현 대상입니다.

V9는 확정 예약 취소 후에도 최초 `confirmed_at`을 보존하도록 제약을 변경합니다. 취소된 예약의 승인 재요청은 거절합니다.

### 환불 작업 저장

`reservation_refund_jobs`는 전액 환불할 승인 이력과 작업 상태를 저장합니다. `ReservationRefundJob`은 `reservation.domain`의 JPA 엔티티이며 기존 `ReservationPaymentRepositoryPort`와 PersistenceAdapter를 통해 저장합니다. 별도의 payment 도메인이나 환불용 Controller는 추가하지 않습니다.

| 필드 | 의미 |
|------|------|
| `id` | 환불 작업의 DB 기본 키 |
| `approved_payment_transaction_id` | `reservation_payment_tx`의 승인 행 참조, 승인당 작업 한 개 |
| `status` | `PENDING`, `COMPLETED`, `NEEDS_REVIEW` |
| `next_attempt_at` | 다음 실행이 가능한 시각 |
| `lease_token`, `lease_expires_at` | 실행을 맡은 시도와 점유 만료 시각 |
| `failure_count`, `last_failure` | 기록된 실패 횟수와 마지막 실패 분류 |
| `completed_at` | 환불 완료 시각 |
| `created_at`, `modified_at` | 공용 BaseEntity의 생성·수정 시각 |

금액, 결제 ID, 주문 번호와 결제 시도 번호는 참조하는 승인 이력에 있고, 취소 사유와 고정 멱등 키는 기존 `CANCEL_PREPARED`에 보존합니다. 작업 테이블에 같은 요청 정보를 중복 저장하지 않습니다. 승인당 전액 환불 작업 하나라는 현재 정책을 사용하며 부분 환불 작업 모델은 추가하지 않습니다.

- 확정 예약 취소: 취소 준비 이력, 환불 작업, 예약 취소, 좌석·구매 한도 반환을 같은 트랜잭션으로 커밋합니다. 작업 저장에 실패하면 취소와 반환도 롤백됩니다.
- 예약 확정 실패 보상: 양수 금액의 `REFUND_REQUIRED` 승인 이력을 저장할 때 작업도 함께 저장합니다. 취소 준비나 PG 환불 호출 전에 프로세스가 종료돼도 할 일이 남습니다. 0원 승인에는 환불 작업을 만들지 않습니다.
- 기존 즉시 PG 환불 호출은 유지합니다. 즉시 완료되지 않으면 예약 취소 API는 `refundStatus: "PENDING"`을 반환합니다. 확인된 `CANCELED` 이력과 작업의 `COMPLETED` 변경은 같은 트랜잭션으로 저장합니다. 어느 한쪽 저장이 실패하면 둘 다 롤백됩니다.
- 같은 예약 잠금과 승인 행의 고유 제약으로 중복 작업을 방지하고, 완료 요청이 반복돼도 작업을 다시 대기로 만들거나 완료 시각을 변경하지 않습니다.

V10은 기존 양수 금액의 환불 필요 승인과 전액 취소 준비를 작업으로 이전합니다. 대응하는 전액 취소 완료 이력이 있으면 완료 상태로 옮기며 기존 거래 행은 수정하지 않습니다. 과거 승인에 PG 결제 시도 정보가 없는 경우에도 환불 의무 자체는 작업으로 보존합니다. V11은 기존 대기 작업을 스케줄러가 가져갈 수 있도록 실행 정보를 추가합니다.

### 환불 스케줄러

`reservation.refund.enabled`의 기본값은 true입니다. `fixed-delay`, `initial-delay`의 기본값은 1초이고, 한 번 실행할 때 서버당 최대 10건을 순차 처리합니다. 환불 스케줄러는 대기열과 예약 만료 스케줄러와 별도 스레드에서 실행됩니다.

1. 실행 시각이 지난 `PENDING` 작업을 PostgreSQL의 `FOR UPDATE SKIP LOCKED`로 한 건씩 가져오고, 30초짜리 점유 토큰을 저장합니다. API의 즉시 환불도 같은 점유 규칙을 사용합니다.
2. 점유를 커밋한 뒤 기존 취소 준비 이력을 읽거나 보상 환불의 준비 이력을 생성합니다. PG는 DB 트랜잭션 밖에서 호출하며 재실행에도 동일한 취소 본문과 멱등 키를 사용합니다.
3. 점유 토큰이 일치할 때만 결과를 저장합니다. 서버가 작업 도중 종료되면 점유가 만료된 뒤 다른 실행이 가져갈 수 있습니다. 만료 이후 이전 요청이 PG에 늦게 도착할 수 있으므로 PG 멱등 키도 함께 필요합니다.
4. PG 장애나 응답 확인 실패, 결과 저장 오류는 5초, 15초, 45초, 135초를 기다린 뒤 재시도합니다. 다섯 번째 실패, PG의 확정 거절, 필수 결제 정보가 누락된 작업은 `NEEDS_REVIEW`로 남깁니다. 재시도 횟수는 실패 기록을 기준으로 하므로 실패를 기록하기 전에 서버가 종료된 경우에는 점유 만료 후 복구를 시도합니다.

대기 작업과 수동 확인 대상은 다음 쿼리로 확인할 수 있습니다.

```sql
SELECT job.id, job.status, job.next_attempt_at, job.failure_count, job.last_failure,
       payment.reservation_id, payment.attempt_number, payment.provider, payment.payment_id
FROM reservation_refund_jobs job
JOIN reservation_payment_tx payment ON payment.id = job.approved_payment_transaction_id
WHERE job.status IN ('PENDING', 'NEEDS_REVIEW')
ORDER BY job.id;
```

### Demo PG HTTP 클라이언트

`DemoPgClient`는 OpenFeign과 Apache HttpClient 5로 결제 승인, 주문 ID 조회와 전액 취소를 호출합니다. 승인과 취소에는 `Idempotency-Key`를 전달합니다. PG 오류 응답은 Feign 예외의 HTTP 상태와 본문에서 확인할 수 있습니다.

PG 취소 어댑터는 최초 승인 총액과 이번 취소 금액을 분리하고, 예상 잔액에 따라 `CANCELED` 또는 `PARTIAL_CANCELED`를 검증합니다. 전체 취소 이력 중 금액·사유·성공 상태가 대응하는 한 건을 선택하며, 동일 후보가 여러 건이면 요청을 식별할 수 없으므로 확인 대기로 남깁니다. Demo PG 응답은 요청 멱등 키를 포함하지 않습니다.

기본 주소는 `http://127.0.0.1:8082`입니다. 같은 Docker 네트워크에서는 `PAYMENT_DEMO_PG_BASE_URL=http://demo-pg:8082`를 사용합니다. `application.yaml`에서 풀 크기와 획득 대기 시간, 연결 및 읽기 타임아웃을 조정할 수 있습니다.

## 테스트

예매 생성 도메인 테스트:

```shell
./gradlew test --tests 'me.park.openrush.reservation.domain.*'
```

전체 테스트에는 Docker가 필요합니다. 기본적으로 테스트 프로세스 2개를 병렬 실행하며, 각 프로세스는 독립된 PostgreSQL 컨테이너 하나를 공유합니다. 같은 프로세스 안의 테스트는 순차 실행합니다. 따라서 다른 프로세스의 DB 초기화가 실행 중인 테스트에 영향을 주지 않습니다.

```shell
./gradlew test
```

프로세스 수는 실행 환경에 맞게 변경할 수 있습니다. 순차 실행으로 비교하려면 다음과 같이 실행합니다.

```shell
./gradlew test -PtestForks=1 --rerun
```

DB 초기화 리스너는 `src/test/resources/META-INF/spring.factories`에서 Spring 테스트의 기본 리스너로 자동 등록됩니다. `DataSource`가 있는 테스트는 매번 초기화하며, 없는 테스트는 건너뜁니다. `@DatabaseIntegrationTest` 사용 여부와 관계없이 적용됩니다.

`@DatabaseIntegrationTest`는 Spring Boot, `test` 프로필과 PostgreSQL 환경을 묶는 편의 어노테이션입니다. 공통 `test` 프로필에서 만료와 환불 스케줄러를 끄고 Hikari 최소 유휴 연결 수를 0으로 설정해, 여러 Spring 컨텍스트가 공유 DB의 연결을 미리 점유하지 않게 합니다. 매 테스트의 `@BeforeEach` 실행 전에 `DatabaseCleaner`가 `public` 스키마의 업무 테이블을 초기화하고 Flyway 이력은 보존합니다. Flyway는 애플리케이션 연결 풀과 별도 연결을 사용합니다. 실제 커밋, `REQUIRES_NEW`, 여러 스레드의 동작을 검증하므로 테스트 전체를 롤백 트랜잭션으로 감싸지 않습니다. 테스트에서 만든 트리거·함수와 실행 스레드는 해당 테스트가 `finally`에서 정리합니다.

트랜잭션 안에서 작업자들이 서로 기다리는 동시성 테스트는 실행 전에 필요한 연결 수를 확보합니다. 연결 풀이 커지는 시점과 DB 경쟁 조건 검증이 겹치지 않도록 하는 준비 단계이며, 전역 유휴 연결 수를 늘리지는 않습니다.

반복되는 공연·좌석 생성은 도메인별 Fixture를 사용하고, PG 연동 테스트는 `DemoPgStubServer`로 응답과 요청 기록을 관리합니다. 실제 예약 생성 동작이 필요한 경우에는 `CreateReservationUseCase`를 호출합니다. 공통 도구는 `src/test`에만 위치합니다.

성능 테스트 실행 방법은 [벤치마크 안내](benchmarks/README.md)를 참고합니다.

## Decisions

검토할 문제마다 번호를 부여하고 전략별 브랜치에서 비교합니다. 새 전략은 `main`이 아닌 실험 브랜치에서 개발하고, 최종 선택한 구현만 `main`에 반영합니다.

### 001 · 예매 동시성 제어

| 전략          | 32명 성공 RPS | 32명 p95 | 64명 성공 RPS | 64명 p95 |
|---------------|--------------:|---------:|--------------:|---------:|
| 비관적 락     |        904.72 |  55.55ms |        908.29 | 101.91ms |
| 낙관적 락     |        915.98 |  50.36ms |        977.63 |  97.41ms |
| 조건부 UPDATE |       1002.80 |  46.08ms |        909.75 | 115.15ms |
