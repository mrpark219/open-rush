# 예매 성능 테스트

JDK 25, Docker Compose, k6, curl이 필요합니다.

```sh
./benchmarks/run.sh
```

전용 DB(`55432`)와 API(`18080`) 준비 → 워밍업 500회 → 시나리오별 1,000회 요청 → DB 정합성 검증 → API·DB 종료까지 실행합니다. 매 시나리오 전에 전용 DB의 공연·예매 데이터를 초기화합니다. 이미 실행 중이던 전용 DB는 종료하지 않습니다.

| 시나리오 | 요청 분포 | 예상 성공 |
| --- | --- | ---: |
| low | 서로 다른 사용자·좌석 | 전체 요청 |
| hot | 사용자들이 인기 좌석 16개에 요청 | 최대 16건 |
| quota | 한 사용자가 서로 다른 회차·좌석에 요청 | 최대 4건 |

한 시나리오만 실행하거나 요청 수·동시 사용자를 바꿀 수 있습니다.

```sh
REQUESTS=3000 VUS=32 ./benchmarks/run.sh hot
```

터미널에 RPS·성공 RPS·p95/p99를 표시합니다. 결과 JSON과 API 로그는 `build/benchmark/<실행시각>/`에 저장합니다. 409 중 정책 거절만 정상으로 집계하며, 오류 응답·성공 수 불일치·DB 정합성 오류는 실행 실패로 처리합니다.

`scenarios.js`는 요청 분포, `reservations.js`는 HTTP 호출·측정을 담당합니다. `seed.sql`과 `verify.sql`은 데이터 준비·검증용입니다.

`hot`과 `quota`는 소진 후 거절이 대부분이므로 전체 RPS를 지속적인 선점 처리량으로 해석하지 않습니다. [전략별 측정 결과](../docs/decisions/001-reservation-concurrency.md)

## 반환 후 재선점

```sh
REQUESTS=120 VUS=16 WARMUP_REQUESTS=12 ./benchmarks/run.sh rehold
```

`rehold`는 별도로 실행합니다. `REQUESTS`와 `WARMUP_REQUESTS`는 HTTP 요청 수가 아닌 전체 시나리오 반복 수이며 각각 3 이상이어야 합니다. 반복마다 별도 좌석을 사용하고 다음 세 흐름을 순환합니다.

| 흐름 | 기대 결과 |
| --- | --- |
| 선점 → 취소 → 다른 사용자의 재선점·확정 | 기존 예매는 `CANCELLED`, 새 예매는 `CONFIRMED` |
| 선점 → 스케줄러 만료 → 다른 사용자의 재선점·확정 | 기존 예매는 `EXPIRED`, 새 예매는 `CONFIRMED` |
| 선점 → 확정 → 다른 사용자의 선점 시도 | 정책상 `409`로 거절되며 기존 예매가 좌석을 유지 |

실행 중 선점 시간을 5초로 설정하고 만료 스케줄러를 250ms 간격으로 실행합니다. 확정은 `benchmark` 프로필과 `reservation.benchmark.rehold-enabled` 설정을 함께 켠 로컬 API에서 호출합니다.
만료 시각부터 재선점 응답까지 걸린 시간은 만료 처리, 최대 100ms의 폴링 간격, 재선점 API 응답 시간을 포함합니다.
`verify-rehold.sql`은 예매 이력, 최종 좌석 소유자와 사용자별 구매 한도를 확인합니다.
