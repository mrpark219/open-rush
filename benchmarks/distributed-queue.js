import http from 'k6/http';
import {sleep} from 'k6';
import execution from 'k6/execution';
import {Counter, Rate, Trend} from 'k6/metrics';

const baseUrls = __ENV.BASE_URLS.split(',');
const rate = Number(__ENV.RATE);
const durationSeconds = Number(__ENV.DURATION_SECONDS || 30);
const maximumUsers = rate * durationSeconds;
const failures = new Rate('journey_failed');
const completed = new Counter('reservations_completed');
const completedDuringArrival = new Counter('reservations_completed_during_arrival');
const started = new Counter('journeys_started');
const totalDuration = new Trend('journey_duration', true);
const reservationDuration = new Trend('reservation_duration', true);
const pollingRequests = new Counter('polling_requests');

export const options = {
    scenarios: {
        traffic: {
            executor: 'constant-arrival-rate',
            rate,
            timeUnit: '1s',
            duration: `${durationSeconds}s`,
            preAllocatedVUs: Number(__ENV.VUS || 2000),
            maxVUs: Number(__ENV.VUS || 2000),
            gracefulStop: '40s',
        },
    },
    summaryTrendStats: ['avg', 'p(95)', 'p(99)', 'max'],
    thresholds: {
        dropped_iterations: ['count==0'],
        journey_failed: ['rate<=0.001'],
        reservation_duration: ['p(95)<500'],
        journey_duration: ['p(95)<1000'],
    },
};

export default function () {
    const index = execution.scenario.iterationInTest;
    if (index >= maximumUsers) {
        return;
    }

    const userId = index + 1;
    const startedAt = Date.now();
    const registrationUrl = baseUrls[index % baseUrls.length];
    const reservationUrl = baseUrls[(index + 1) % baseUrls.length];
    const parameters = {headers: {'Content-Type': 'application/json'}, timeout: '2s'};
    started.add(1);

    let response = http.post(`${registrationUrl}/reservation-queue`, JSON.stringify({userId}),
        {...parameters, tags: {name: 'queue_join'}});
    if (response.status !== 200) {
        failures.add(true);
        return;
    }

    const ticketId = response.json('ticketId');
    while (response.json('status') === 'WAITING' && Date.now() - startedAt < 30000) {
        sleep(0.1);
        pollingRequests.add(1);
        response = http.get(`${reservationUrl}/reservation-queue/${ticketId}`,
            {timeout: '2s', tags: {name: 'queue_status'}});
        if (response.status !== 200) {
            failures.add(true);
            return;
        }
    }

    if (response.json('status') !== 'ADMITTED') {
        failures.add(true);
        return;
    }

    const reservation = {userId, performanceId: userId % 2 === 1 ? 1 : 2, seatIds: [userId]};
    response = http.post(`${reservationUrl}/reservations`, JSON.stringify(reservation), {
        headers: {'Content-Type': 'application/json', 'X-Queue-Ticket': ticketId},
        timeout: '2s', tags: {name: 'reservation_create'},
    });
    reservationDuration.add(response.timings.duration);
    failures.add(response.status !== 201);

    if (response.status === 201) {
        completed.add(1);
        if (Date.now() < execution.scenario.startTime + durationSeconds * 1000) {
            completedDuringArrival.add(1);
        }
        totalDuration.add(Date.now() - startedAt);
    }
}

export function handleSummary(data) {
    const count = name => data.metrics[name]?.values.count || 0;
    const percentile = name => data.metrics[name]?.values['p(95)'] || 0;
    const report = {
        instances: baseUrls.length, targetRate: rate, durationSeconds,
        started: count('journeys_started'), completed: count('reservations_completed'),
        successfulDuringArrivalPerSecond: count('reservations_completed_during_arrival') / durationSeconds,
        completedAfterArrival: count('reservations_completed') - count('reservations_completed_during_arrival'),
        failureRate: data.metrics.journey_failed?.values.rate || 0,
        dropped: count('dropped_iterations'), pollingRequests: count('polling_requests'),
        reservationP95: percentile('reservation_duration'), journeyP95: percentile('journey_duration'),
        ...data,
    };
    return {
        [__ENV.SUMMARY]: JSON.stringify(report, null, 2),
        stdout: JSON.stringify({instances: report.instances, rate, started: report.started,
            completed: report.completed, failed: report.failureRate, dropped: report.dropped,
            reservationP95: report.reservationP95, journeyP95: report.journeyP95}) + '\n',
    };
}
