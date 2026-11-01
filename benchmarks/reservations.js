import http from 'k6/http';
import execution from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';
import { selectScenario } from './scenarios.js';

const requestCount = positiveInteger(__ENV.REQUESTS || '1000', 'REQUESTS');
const users = positiveInteger(__ENV.VUS || '16', 'VUS');
const scenarioName = __ENV.CASE || 'low';
const scenario = selectScenario(scenarioName);
const strategy = __ENV.STRATEGY || 'pessimistic';
if (!['pessimistic', 'optimistic'].includes(strategy)) throw new Error(`Unknown strategy: ${strategy}`);
const maximumSuccesses = scenario.expectedSuccesses(requestCount);
const minimumSuccesses = strategy === 'optimistic' && scenarioName === 'quota' ? 1 : maximumSuccesses;

const successes = new Counter('reservation_successes');
const rejections = new Counter('reservation_rejections');
const conflicts = new Counter('concurrency_conflicts');
const unexpectedResponses = new Rate('unexpected_responses');

export const options = {
  scenarios: {
    reservations: {
      executor: 'shared-iterations',
      vus: users,
      iterations: requestCount,
      maxDuration: '4m',
    },
  },
  summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    reservation_successes: [`count >= ${minimumSuccesses}`, `count <= ${maximumSuccesses}`],
    concurrency_conflicts: strategy === 'pessimistic' ? ['count == 0'] : ['count >= 0'],
    unexpected_responses: ['rate == 0'],
    iterations: [`count == ${requestCount}`],
  },
};

http.setResponseCallback(http.expectedStatuses(201, 409));

export default function () {
  const request = scenario.request(execution.scenario.iterationInTest);
  const response = http.post('http://localhost:18080/reservations', JSON.stringify(request), {
    headers: { 'Content-Type': 'application/json' },
    timeout: '15s',
  });

  const successful = response.status === 201;
  const errorCode = readConflictCode(response);
  const rejected = errorCode === 'RESERVATION_RULE_VIOLATION';
  const conflicted = errorCode === 'CONCURRENCY_CONFLICT';

  successes.add(successful ? 1 : 0);
  rejections.add(rejected ? 1 : 0);
  conflicts.add(conflicted ? 1 : 0);
  unexpectedResponses.add(!successful && !rejected && !conflicted);
}

export function handleSummary(data) {
  const metrics = data.metrics;
  const duration = metrics.http_req_duration.values;
  const summary = {
    scenario: scenarioName,
    strategy,
    requests: requestCount,
    users,
    minimumSuccesses,
    maximumSuccesses,
    successfulReservations: metrics.reservation_successes.values.count,
    ...data,
  };

  return {
    [__ENV.SUMMARY || 'summary.json']: JSON.stringify(summary, null, 2),
    stdout: [
      `${scenarioName} (${strategy}): ${metrics.reservation_successes.values.count} successes, ${metrics.reservation_rejections.values.count} rejections, ${metrics.concurrency_conflicts.values.count} conflicts`,
      `RPS ${metrics.http_reqs.values.rate.toFixed(1)} / success RPS ${metrics.reservation_successes.values.rate.toFixed(1)}`,
      `p95 ${duration['p(95)'].toFixed(1)} ms / p99 ${duration['p(99)'].toFixed(1)} ms`,
      `Unexpected responses: ${(metrics.unexpected_responses.values.rate * 100).toFixed(1)}%\n`,
    ].join('\n'),
  };
}

function readConflictCode(response) {
  if (response.status !== 409) return null;

  try {
    return response.json('code');
  } catch (_) {
    return null;
  }
}

function positiveInteger(value, name) {
  if (!/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(Number(value))) {
    throw new Error(`${name} must be a positive integer`);
  }

  return Number(value);
}
