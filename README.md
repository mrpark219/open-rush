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
| `reservation` | `ReservationPaymentRepositoryPort` — 예약 결제 준비와 승인 이력 조회 및 저장 |
| `queue`       | `QueueStorePort` — 대기표와 입장 허가 상태 관리                                 |

| 도메인        | 소유 데이터와 책임                                            |
|---------------|---------------------------------------------------------------|
| `concert`     | 공연, 회차, 예매 기간과 공연에 설정된 구매 제한 조회          |
| `inventory`   | 회차별 좌석 가격과 재고, 홀드, 확정, 반환                     |
| `quota`       | 사용자별 공연 구매 수량 확보와 반환                           |
| `reservation` | 예약과 가격 스냅샷, 결제 거래 이력, 예약 확정과 환불 필요 상태 관리 |
| `payment`     | 예약 정보에 의존하지 않는 PG 승인과 조회 |
| `queue`       | 대기표와 입장 허가, 메모리 저장 어댑터                        |

각 도메인의 기본 구현은 `ConcertService`, `InventoryService`, `QuotaService`, `ReservationService`, `PaymentService`, `QueueService`입니다. 예약 생성의 충돌 재시도, 만료 배치의 건별 트랜잭션, PG 호출 전후의 트랜잭션 분리가 필요한 조율 클래스는 별도로 유지합니다. `output.local` 어댑터는 사용하지 않습니다.

저장소는 도메인별 출력 포트와 JPA/Querydsl 어댑터로 연결하고, PG는 결제 도메인의 `PaymentGatewayPort`와 Demo PG 어댑터로 연결합니다. 예약은 재고와 구매 한도 도메인의 엔티티나 저장소를 직접 참조하지 않습니다. 예약 결제 거래와 처리 결과는 `reservation`이 소유하고, `payment`에는 예약 ID나 예약 처리 상태를 전달하지 않습니다. 공개 입력 포트에서는 DTO와 값 객체를 사용하며, 엔티티 간의 도메인 외부 참조는 ID로 저장합니다. 도메인 객체와 JPA 엔티티를 함께 사용하는 방식은 유지합니다.

PersistenceAdapter는 Repository에 DB 작업을 위임합니다. EntityManager, Querydsl과 직접 실행하는 SQL은 Repository 구현에서 사용합니다. 좌석과 예약 항목의 금액은 별도 값 객체 없이 원 단위 `amount: Long`으로 저장하며, 예약 합계 `totalAmount`도 Long입니다. 음수 검증과 합산 오버플로 검사는 유지합니다.

현재는 단일 DB를 사용하며 예약 생성, 재고 홀드, 한도 확보가 같은 트랜잭션에 참여합니다. 테이블과 DB 외래 키는 유지합니다. PG 호출은 DB 트랜잭션 밖에서 실행합니다. 멀티 모듈, 서비스별 DB, Outbox와 Kafka는 후속 단계입니다. 도메인 간 구현체 및 엔티티 참조를 제한하는 `DomainBoundariesTest`를 제공합니다.

DB 기본 키와 외래 키는 `id`/`...Id`, 결제 시도 UUID는 `attemptNumber`, UUID 기반 PG 주문 번호는 `pgOrderNumber`로 구분합니다. 예약 결제 요청과 응답도 `attemptNumber`를 사용합니다. Demo PG의 외부 JSON 필드 `orderId`는 해당 API 규약을 유지하고 어댑터에서 내부 주문 번호와 변환합니다. 기존 DB 컬럼은 V7 마이그레이션에서 `attempt_number`, `pg_order_number`로 이름만 변경하여 이력을 보존합니다.

## 개발 환경 실행

JDK 25와 Docker Compose가 필요합니다. Compose로 PostgreSQL 18을 실행한 뒤 애플리케이션을 시작합니다.

```shell
docker compose up -d --wait
./gradlew bootRun
```

개발용 DB 접속 정보는 `localhost:5432`, 데이터베이스·사용자·비밀번호는 모두 `open_rush`입니다. 접속 설정을 변경할 때는 `docker-compose.yml`과
`src/main/resources/application.yaml`을 함께 수정합니다.

애플리케이션 시작 시 Flyway가 스키마를 적용하고 Hibernate가 엔티티 매핑을 검증합니다.

## 도메인과 예매 정책

- `Concert`와 `Performance`는 공연과 회차를 나타냅니다.
- `PerformanceSeat`는 회차별 좌석 재고와 현재 점유 상태를 관리합니다.
- `Reservation`과 `ReservationItem`은 한 사용자의 예매와 좌석별 가격을 기록합니다.
- `ConcertPurchaseQuota`는 사용자별로 같은 공연의 모든 회차에서 확보한 수량을 관리합니다.
- 한 예매에는 같은 회차의 중복 없는 좌석을 1개 이상 선택하며, 공연별 1인 구매 한도를 적용합니다.
- 선점 기한은 기본 5분이며 예매 마감을 넘지 않습니다. 만료 시각부터 확정할 수 없습니다.
- 예매 전체를 확정·취소·만료하며, 부분 취소와 확정 후 환불은 지원하지 않습니다.

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
- 결제 취소·환불과 확정된 예매의 취소는 지원하지 않습니다.

## 예매 선점 만료

- `HELD`이고 `expiresAt <= 현재 시각`인 예매를 `EXPIRED`로 전환하고 선점한 좌석과 구매 한도를 반환합니다.
- 애플리케이션 시작 10초 후부터 자동 실행하며, 이전 실행 완료 후 10초 뒤 다시 실행합니다. 별도의 HTTP API는 제공하지 않습니다.
- 대상을 ID 순서로 100건씩 조회하고 예매 한 건마다 독립된 트랜잭션으로 처리합니다. 조건부 UPDATE에 성공한 요청만 반환하므로 여러 서버에서 동시에 실행해도 중복 반환하지 않습니다.
- 만료 트랜잭션에는 기본 1초의 잠금 대기 제한을 적용합니다. 예매·한도·좌석 잠금 대기가 제한을 넘으면 해당 예매를 롤백하고 후속 예매를 계속 처리합니다. 제한은 개별 잠금 대기에 적용되며 전체 배치 실행 시간의 상한은 아닙니다.
- 반환 실패 시 해당 예매의 상태 변경까지 롤백하고 오류를 기록합니다. 뒤의 예매는 계속 처리하며, 실패한 예매는 다음 실행에서 재시도합니다. 이미 취소·확정·만료됐거나 아직 기한 전인 예매는 건너뜁니다.
- 만료 시각부터 확정·선점 취소가 거절되며, 좌석과 한도는 만료 작업이 성공한 뒤 다시 사용할 수 있습니다. 실제 반환까지는 실행 간격과 처리 시간만큼 지연될 수 있습니다.

`application.yaml`의 `reservation.expiration`에서 실행 여부(`enabled`), 완료 후 대기 간격(`fixed-delay`), 최초 대기 시간(`initial-delay`), 조회 크기(`batch-size`), 잠금 대기 제한(`lock-timeout-ms`, 양수 밀리초)을 조정할 수 있습니다. 잠금 대기 설정은 만료 트랜잭션에만 적용하며 커밋·롤백 후 풀 연결의 원래 설정으로 돌아갑니다.
벤치마크 프로필에서는 자동 만료를 비활성화합니다.

## 결제 성공 처리

`RecordPaymentSuccessUseCase`는 결제 성공과 가맹점 주문의 예매 연결을 검증한 입력을 받습니다. 예매 금액 단위는 원(KRW)입니다.
금액과 통화가 일치하면 예매 확정과 결제 결과를 같은 트랜잭션으로 기록합니다. 결제사와 결제 ID가 같고 나머지 입력도 같으면 기존 결과를 반환합니다.
금액이나 통화가 다르거나 예매를 확정할 수 없으면 `REFUND_REQUIRED`를 기록합니다. 실제 환불 호출은 후속 구현 범위입니다.

`reservation_payment_tx`는 예매별 결제 이력을 저장합니다. 승인 준비(`APPROVAL_PREPARED`), 승인 완료(`APPROVED`), 취소(`CANCELED`)는 각각 별도의 행입니다. 같은 결제 시도의 재요청에는 준비 단계에서 저장한 PG 주문 ID와 승인 멱등성 키를 재사용합니다. 과거 결제 성공 기록은 승인 완료 행으로 이전합니다.

### 예약 결제 승인 API

`POST /reservations/{reservationId}/payments/confirm`은 `userId`, `attemptNumber`(UUID), `paymentKey`를 받습니다. 금액은 저장된 예약 금액을 사용합니다. 같은 예약과 결제 시도에는 같은 `paymentKey`를 사용해야 하며, 다른 키로 재요청하면 `409 PAYMENT_CONFLICT`를 반환합니다. 요청 예시는 [reservation-payment.http](http/reservation-payment.http)에 있습니다.

준비 이력을 커밋한 뒤 DB 트랜잭션 밖에서 PG 주문을 조회하고, 아직 승인되지 않았고 예약이 유효하면 승인합니다. 승인 응답은 `DONE`, 주문 ID, 결제 키를 검증하며 금액과 통화 불일치는 환불 필요로 기록합니다. 결과 저장과 예약 확정은 하나의 새 트랜잭션으로 처리합니다. 완료된 동일 요청은 PG 호출 없이 저장된 결과를 반환합니다.

| HTTP | status 또는 code        | 의미                                                      |
|------|-------------------------|-----------------------------------------------------------|
| 200  | `RESERVATION_CONFIRMED` | 승인 이력 저장과 예약 확정 완료                           |
| 200  | `REFUND_REQUIRED`       | PG 승인 완료, 금액 불일치 또는 예약 확정 불가로 환불 필요 |
| 202  | `PENDING`               | PG 승인 여부 확인 필요                                    |
| 409  | `PAYMENT_CONFLICT`      | 같은 시도의 결제 키 또는 기존 승인 정보 충돌              |
| 422  | `PAYMENT_REJECTED`      | PG가 승인을 명시적으로 거절                               |

타임아웃과 PG 서버 오류가 발생하면 같은 주문을 한 번 조회합니다. 조회로도 승인 여부를 확인할 수 없으면 `PENDING`을 반환합니다. 클라이언트는 동일한 요청 본문으로 재요청하여 결과를 확인할 수 있습니다. 기존 시도는 예약이 만료되어도 조회할 수 있지만 새 승인은 하지 않습니다. 자동 복구 스케줄러는 아직 없습니다. PG 승인 후 DB 저장이 실패해도 준비 이력이 남으므로 재요청에서 주문 조회로 복구합니다.

예약 결제 유스케이스의 입력은 `RecordPaymentSuccessCommand`, 승인 후 예약 처리 결과는 `ReservationPaymentApprovalResult`로 전달합니다. 두 객체는 `reservation.application.provided`에 두며, 거래 엔티티는 이 DTO에 의존하지 않습니다. `ReservationBookingPolicy`는 예약 생성 시 예매 가능 시간과 구매 제한을 판단하는 도메인 객체입니다.

취소 준비는 승인 이력에서 `CANCEL_PREPARED` 행을 추가하는 방식입니다. 취소 시도 UUID인 `cancellationNumber`, 취소 금액, 사유와 고정 멱등 키를 보존하고, 완료 시 같은 취소 번호로 `CANCELED` 행을 추가합니다. V8 마이그레이션은 기존 취소 이력을 보존하면서 준비와 완료의 중복 저장을 제한합니다. 현재는 이력 생성과 저장, 조회까지 지원하며 PG 취소 API, 남은 취소 가능 금액의 동시성 제어, 재시도 실행과 복구 스케줄러는 아직 구현하지 않았습니다.

### Demo PG HTTP 클라이언트

`DemoPgClient`는 OpenFeign과 Apache HttpClient 5로 결제 승인과 주문 ID 조회를 호출합니다. 승인에는 `Idempotency-Key`를 전달합니다. PG 오류 응답은 Feign 예외의 HTTP 상태와 본문에서 확인할 수 있습니다.

기본 주소는 `http://127.0.0.1:8082`입니다. 같은 Docker 네트워크에서는 `PAYMENT_DEMO_PG_BASE_URL=http://demo-pg:8082`를 사용합니다. `application.yaml`에서 풀 크기와 획득 대기 시간, 연결 및 읽기 타임아웃을 조정할 수 있습니다.

## 테스트

예매 생성 도메인 테스트:

```shell
./gradlew test --tests 'me.park.openrush.reservation.domain.*'
```

전체 테스트에는 Docker가 필요합니다. Testcontainers가 별도의 PostgreSQL을 실행합니다.

```shell
./gradlew test
```

성능 테스트 실행 방법은 [벤치마크 안내](benchmarks/README.md)를 참고합니다.

## Decisions

검토할 문제마다 번호를 부여하고 전략별 브랜치에서 비교합니다. 새 전략은 `main`이 아닌 실험 브랜치에서 개발하고, 최종 선택한 구현만 `main`에 반영합니다.

### 001 · 예매 동시성 제어

| 전략          | 32명 성공 RPS | 32명 p95 | 64명 성공 RPS | 64명 p95 |
|---------------|--------------:|---------:|--------------:|---------:|
| 비관적 락     |        904.72 |  55.55ms |        908.29 | 101.91ms |
| 낙관적 락     |        915.98 |  50.36ms |        977.63 |  97.41ms |
| 조건부 UPDATE |       1002.80 |  46.08ms |        909.75 | 115.15ms |
