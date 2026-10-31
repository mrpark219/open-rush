const HOT_SEAT_COUNT = 16;
const PURCHASE_LIMIT = 4;

const scenarios = {
  low: {
    request: (index) => reservation(index + 1, index + 1),
    expectedSuccesses: (requests) => requests,
  },
  hot: {
    request: (index) => reservation(index + 1, index % HOT_SEAT_COUNT + 1),
    expectedSuccesses: (requests) => Math.min(requests, HOT_SEAT_COUNT),
  },
  quota: {
    request: (index) => reservation(1, index + 1),
    expectedSuccesses: (requests) => Math.min(requests, PURCHASE_LIMIT),
  },
};

export function selectScenario(name) {
  if (!Object.prototype.hasOwnProperty.call(scenarios, name)) {
    throw new Error(`Unknown scenario: ${name}. Choose low, hot or quota.`);
  }

  return scenarios[name];
}

function reservation(userId, seatId) {
  return {
    userId,
    performanceId: seatId % 2 === 1 ? 1 : 2,
    seatIds: [seatId],
  };
}
