import http from 'k6/http';
import execution from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';
import { selectScenario } from './scenarios.js';

const requestCount = positiveInteger(__ENV.REQUESTS || '1000', 'REQUESTS');
const users = positiveInteger(__ENV.VUS || '16', 'VUS');
const scenarioName = __ENV.CASE || 'low';
const scenario = selectScenario(scenarioName);
const expectedSuccesses = scenario.expectedSuccesses(requestCount);

const successes = new Counter('reservation_successes');
const rejections = new Counter('reservation_rejections');
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
    reservation_successes: [`count == ${expectedSuccesses}`],
    reservation_rejections: [`count == ${requestCount - expectedSuccesses}`],
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
  const rejected = isBusinessRejection(response);

  successes.add(successful ? 1 : 0);
  rejections.add(rejected ? 1 : 0);
  unexpectedResponses.add(!successful && !rejected);
}

export function handleSummary(data) {
  const metrics = data.metrics;
  const duration = metrics.http_req_duration.values;
  const summary = {
    scenario: scenarioName,
    requests: requestCount,
    users,
    expectedSuccesses,
    ...data,
  };

  return {
    [__ENV.SUMMARY || 'summary.json']: JSON.stringify(summary, null, 2),
    stdout: [
      `${scenarioName}: ${metrics.reservation_successes.values.count} successes, ${metrics.reservation_rejections.values.count} rejections`,
      `RPS ${metrics.http_reqs.values.rate.toFixed(1)} / success RPS ${metrics.reservation_successes.values.rate.toFixed(1)}`,
      `p95 ${duration['p(95)'].toFixed(1)} ms / p99 ${duration['p(99)'].toFixed(1)} ms`,
      `Unexpected responses: ${(metrics.unexpected_responses.values.rate * 100).toFixed(1)}%\n`,
    ].join('\n'),
  };
}

function isBusinessRejection(response) {
  if (response.status !== 409) return false;

  try {
    return response.json('code') === 'RESERVATION_RULE_VIOLATION';
  } catch (_) {
    return false;
  }
}

function positiveInteger(value, name) {
  if (!/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(Number(value))) {
    throw new Error(`${name} must be a positive integer`);
  }

  return Number(value);
}
