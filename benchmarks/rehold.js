import {sleep} from 'k6';
import execution from 'k6/execution';
import http from 'k6/http';
import {Counter, Rate, Trend} from 'k6/metrics';
import {isBusinessRejection, positiveInteger, post} from './requests.js';
import {reservation} from './scenarios.js';

const iterations = positiveInteger(__ENV.REQUESTS || '1000', 'REQUESTS');
const vus = Math.min(iterations, positiveInteger(__ENV.VUS || '16', 'VUS'));
const paths = ['cancel', 'expire'];
const completed = {
    cancel: new Counter('rehold_after_cancel'),
    expire: new Counter('rehold_after_expiration'),
};
const failures = new Rate('rehold_failures');
const expiryDelay = new Trend('rehold_expiry_to_rehold_ms', true);

export const options = {
    scenarios: {
        rehold: {
            executor: 'shared-iterations',
            vus,
            iterations,
            maxDuration: `${Math.max(240, Math.ceil(iterations / vus) * 15)}s`,
        },
    },
    summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        rehold_after_cancel: [`count == ${Math.ceil(iterations / 2)}`],
        rehold_after_expiration: [`count == ${Math.floor(iterations / 2)}`],
        rehold_failures: ['rate == 0'],
        iterations: [`count == ${iterations}`],
    },
};

http.setResponseCallback(http.expectedStatuses(201, 204, 409));

export default function () {
    const index = execution.scenario.iterationInTest;
    const path = paths[index % paths.length];
    const seatId = index + 1;
    const firstUser = index * 2 + 1;
    const secondUser = firstUser + 1;
    let succeeded = false;

    try {
        const first = readHold(hold(firstUser, seatId));
        if (path === 'cancel') {
            expectStatus(post(`/reservations/${first.reservationId}/cancel-hold`, {userId: firstUser}), 204, '선점 취소');
        }
        const second = path === 'expire' ? holdAfterExpiration(first, secondUser, seatId) : readHold(hold(secondUser, seatId));
        if (!isBusinessRejection(hold(firstUser, seatId))) throw new Error('다른 사용자가 홀드한 좌석의 중복 선점이 허용됐습니다');
        expectStatus(post(`/reservations/${second.reservationId}/cancel-hold`, {userId: secondUser}), 204, '재선점 취소');
        succeeded = true;
    } catch (error) {
        console.error(`${path} seat=${seatId}: ${error.message}`);
    } finally {
        completed[path].add(succeeded ? 1 : 0);
        failures.add(!succeeded);
    }
}

function hold(userId, seatId) {
    return post('/reservations', reservation(userId, seatId));
}

function readHold(response) {
    expectStatus(response, 201, '좌석 선점');
    const body = response.json();
    if (!Number.isSafeInteger(body.reservationId) || body.reservationId <= 0) throw new Error('유효하지 않은 예매 ID');
    return body;
}

function holdAfterExpiration(first, userId, seatId) {
    const expiresAt = Date.parse(first.expiresAt);
    if (!Number.isFinite(expiresAt)) throw new Error('유효하지 않은 선점 만료 시각');
    sleep(Math.max(0, expiresAt - Date.now()) / 1000);

    const deadline = expiresAt + 10_000;
    while (Date.now() < deadline) {
        const response = hold(userId, seatId);
        if (response.status === 201) {
            const delay = Date.now() - expiresAt;
            if (delay > 10_000) break;
            expiryDelay.add(Math.max(0, delay));
            return readHold(response);
        }
        if (!isBusinessRejection(response)) throw new Error(`만료 후 재선점 응답 ${response.status}`);
        sleep(0.1);
    }
    throw new Error('만료 시각으로부터 10초 이내에 좌석을 다시 선점하지 못했습니다');
}

function expectStatus(response, expected, operation) {
    if (response.status !== expected) throw new Error(`${operation} 응답 ${response.status} (기대 ${expected})`);
}

export function handleSummary(data) {
    const metrics = data.metrics;
    const count = (name) => metrics[name]?.values.count ?? 0;
    const p95 = metrics.rehold_expiry_to_rehold_ms?.values['p(95)'];
    return {
        [__ENV.SUMMARY || 'summary.json']: JSON.stringify({scenario: 'rehold', iterations, vus, ...data}, null, 2),
        stdout: [
            `rehold: 취소 후 재선점 ${count('rehold_after_cancel')}건 / 만료 후 재선점 ${count('rehold_after_expiration')}건`,
            `만료 시각 → 재선점 응답 p95: ${p95 === undefined ? 'n/a' : `${p95.toFixed(1)} ms`}`,
            `실패율: ${((metrics.rehold_failures?.values.rate ?? 0) * 100).toFixed(1)}%\n`,
        ].join('\n'),
    };
}
