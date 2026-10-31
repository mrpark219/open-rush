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

`hot`과 `quota`는 소진 후 거절이 대부분이므로 전체 RPS를 지속적인 선점 처리량으로 해석하지 않습니다. [이전 측정 기록](../docs/benchmarks/2026-10-30-pessimistic.md)
