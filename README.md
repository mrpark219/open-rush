# open-rush

동시 요청 환경에서 정확성과 성능을 비교하는 콘서트 예매 백엔드입니다. 헥사고날 아키텍처와 DDD를 지향합니다.

공연·회차·좌석 관리, 예매, 동시성 제어, 결제와 대기열을 다룹니다.

## 개발 환경 실행

JDK 25와 Docker Compose가 필요합니다. Compose로 PostgreSQL 18을 실행한 뒤 애플리케이션을 시작합니다.

```shell
docker compose up -d --wait
./gradlew bootRun
```

개발용 DB 접속 정보는 `localhost:5432`, 데이터베이스·사용자·비밀번호는 모두 `open_rush`입니다. 접속 설정을 변경할 때는 `docker-compose.yml`과 `src/main/resources/application.yaml`을 함께 수정합니다.

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

## 테스트

예매 생성 도메인 테스트:

```shell
./gradlew test --tests 'me.park.openrush.domain.*'
```

전체 테스트에는 Docker가 필요합니다. Testcontainers가 별도의 PostgreSQL을 실행합니다.

```shell
./gradlew test
```

## Decisions

검토할 문제마다 번호를 부여하고 전략별 브랜치에서 비교합니다. 새 전략은 `main`이 아닌 실험 브랜치에서 개발하고, 최종 선택한 구현만 `main`에 반영합니다.

### 001 · 예매 동시성 제어

상태: 비교 중 · 최종 선택: 미정

공통 분기점: `5a919dd42c1ded595ce9028e1a04e3492cebb1d3` (비관적 락·k6 기준 버전)

| 전략 | 브랜치 | 상태 |
| --- | --- | --- |
| 비관적 락 | `decision/001-reservation-concurrency/pessimistic-lock` | 기준 구현 |
| 낙관적 락 | `decision/001-reservation-concurrency/optimistic-lock` | 구현 완료 · 브랜치에서 비교 |
| 조건부 UPDATE | `decision/001-reservation-concurrency/conditional-update` | 준비 · 미구현 |

요청 수·동시 사용자·데이터·재시도 정책을 맞춰 정합성, 성공 RPS, p95/p99, 동시성 충돌률을 비교합니다. 결정 후 이 절에 측정 결과와 선택 이유를 기록합니다. 같은 문제를 재검토할 때는 새 번호로 이전 결정을 연결합니다.

`main`은 비관적 락 기준 버전을 유지합니다. 낙관적 락 도입 이후의 구현은 실험 브랜치에서만 진행합니다.

현재 브랜치: 비관적 락 기준 구현. `./benchmarks/run.sh`로 측정합니다.
