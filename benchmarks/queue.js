import { sleep } from 'k6';
import execution from 'k6/execution';
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { positiveInteger, post } from './requests.js';
import { reservation } from './scenarios.js';

const scenario = __ENV.CASE || 'queue-baseline';
const queued = scenario === 'queue';
const requests = positiveInteger(__ENV.REQUESTS || '120', 'REQUESTS');
const vus = Math.min(requests, positiveInteger(__ENV.VUS || '120', 'VUS'));
const arrivalSeconds = positiveInteger(__ENV.ARRIVAL_SECONDS || '2', 'ARRIVAL_SECONDS');
const holdTimeout = `${positiveInteger(__ENV.HOLD_TIMEOUT_MS || '1000', 'HOLD_TIMEOUT_MS')}ms`;
const warmup = __ENV.RUN_LABEL === 'warmup';
const arrivals = new Counter('queue_arrivals');
const holdAttempts = new Counter('queue_hold_attempts');
const holdSuccesses = new Counter('queue_hold_successes');
const successesDuringArrival = new Counter('queue_successes_during_arrival');
const holdFailures = new Counter('queue_hold_failures');
const intakeFailures = new Counter('queue_intake_failures');
const queueRejections = new Counter('queue_rejections');
const healthChecks = new Counter('queue_health_checks');
const healthFailures = new Counter('queue_health_failures');
const unexpected = new Counter('queue_unexpected_responses');
const waitTime = new Trend('queue_wait_ms', true);
const holdTime = new Trend('queue_hold_ms', true);
const totalTime = new Trend('queue_total_ms', true);

export const options = {
  scenarios: {
    traffic: {
      executor: 'constant-arrival-rate',
      rate: requests,
      timeUnit: `${arrivalSeconds}s`,
      duration: `${arrivalSeconds}s`,
      preAllocatedVUs: vus,
      gracefulStop: '130s',
    },
    health: { executor: 'constant-vus', vus: 1, duration: `${arrivalSeconds}s`, exec: 'probe' },
  },
  summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    dropped_iterations: ['count == 0'],
    queue_arrivals: [`count == ${requests}`],
    queue_health_checks: ['count > 0'],
    queue_unexpected_responses: ['count == 0'],
    ...(warmup ? { queue_hold_successes: [`count == ${requests}`] } : {}),
  },
};

export function setup() {
  if (!queued) return;
  const response = post('/reservations', reservation(requests + 1, 1), { timeout: holdTimeout });
  if (response.status !== 429 || response.json('code') !== 'QUEUE_ADMISSION_REQUIRED') {
    throw new Error('입장 허가가 없는 선점 요청이 거절되지 않았습니다');
  }
}

export default function () {
  const userId = execution.scenario.iterationInTest + 1;
  // k6 can start an extra iteration at the duration boundary.
  if (userId > requests) return;
  const startedAt = Date.now();
  arrivals.add(1);
  const ticket = queued ? admission(userId, startedAt) : null;
  if (queued && ticket === null) return;

  holdAttempts.add(1);
  const response = post('/reservations', reservation(userId, userId), {
    headers: ticket ? { 'X-Queue-Ticket': ticket } : {},
    timeout: holdTimeout,
  });
  holdTime.add(response.timings.duration);
  if (response.status === 201) {
    holdSuccesses.add(1);
    totalTime.add(Date.now() - startedAt);
    if (Date.now() < execution.scenario.startTime + arrivalSeconds * 1000) successesDuringArrival.add(1);
  } else if (queued && response.status === 429 && response.json('code') === 'QUEUE_ADMISSION_REQUIRED') {
    queueRejections.add(1);
  } else {
    classifyFailure(response, holdFailures);
  }
}

function admission(userId, startedAt) {
  let response = post('/reservation-queue', { userId }, { timeout: '2s' });
  if (response.status === 429 && response.json('code') === 'QUEUE_FULL') {
    queueRejections.add(1);
    return null;
  }
  if (response.status !== 200) return classifyFailure(response, intakeFailures);

  const ticketId = response.json('ticketId');
  if (typeof ticketId !== 'string') {
    unexpected.add(1);
    return null;
  }
  while (response.json('status') === 'WAITING' && Date.now() - startedAt < 120_000) {
    sleep(0.25);
    response = http.get(`http://localhost:18080/reservation-queue/${ticketId}`, { timeout: '2s' });
    if (response.status === 404 && response.json('code') === 'QUEUE_TICKET_NOT_FOUND') {
      queueRejections.add(1);
      return null;
    }
    if (response.status !== 200) return classifyFailure(response, intakeFailures);
  }
  if (response.json('status') === 'ADMITTED') {
    waitTime.add(Date.now() - startedAt);
    return ticketId;
  }
  if (response.json('status') === 'WAITING') queueRejections.add(1);
  else unexpected.add(1);
  return null;
}

function classifyFailure(response, failures) {
  if (response.status === 0 || response.status >= 500) failures.add(1);
  else unexpected.add(1);
  return null;
}

export function probe() {
  const response = http.get('http://localhost:18080/actuator/health', { timeout: '1s' });
  healthChecks.add(1);
  healthFailures.add(response.status === 200 ? 0 : 1);
  sleep(0.25);
}

export function handleSummary(data) {
  const metrics = data.metrics;
  const count = (name) => metrics[name]?.values.count ?? 0;
  const p95 = (name) => metrics[name]?.values['p(95)']?.toFixed(1) ?? 'n/a';
  const successRate = metrics.queue_hold_successes?.values.rate ?? 0;
  const report = {
    scenario, requests, vus, arrivalSeconds, holdTimeout,
    admissionRate: __ENV.QUEUE_ADMISSIONS_PER_SECOND, maxInFlight: __ENV.QUEUE_MAX_IN_FLIGHT,
    dbPoolSize: __ENV.BENCH_DB_POOL_SIZE, ...data,
  };
  return {
    [__ENV.SUMMARY || 'summary.json']: JSON.stringify(report, null, 2),
    stdout: [
      `${scenario} 유입 목표 ${(requests / arrivalSeconds).toFixed(1)} TPS / 시작 ${count('queue_arrivals')}건 / k6 미전송 ${count('dropped_iterations')}건`,
      `선점 호출 ${count('queue_hold_attempts')}건 / 성공 ${count('queue_hold_successes')}건 / 유입 중 성공 TPS ${(count('queue_successes_during_arrival') / arrivalSeconds).toFixed(1)} / 전체 성공 TPS ${successRate.toFixed(1)}`,
      `선점 오류·타임아웃 ${count('queue_hold_failures')}건 / 대기열 오류·타임아웃 ${count('queue_intake_failures')}건 / 대기열 만료·거절 ${count('queue_rejections')}건 / 헬스 체크 실패 ${count('queue_health_failures')}/${count('queue_health_checks')}`,
      `p95 대기 ${p95('queue_wait_ms')} ms / 선점 ${p95('queue_hold_ms')} ms / 전체 ${p95('queue_total_ms')} ms\n`,
    ].join('\n'),
  };
}
