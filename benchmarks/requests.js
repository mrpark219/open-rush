import http from 'k6/http';

export function post(path, body) {
  return http.post(`http://localhost:18080${path}`, body === undefined ? null : JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json' },
    timeout: '15s',
  });
}

export function isBusinessRejection(response) {
  if (response.status !== 409) return false;

  try {
    return response.json('code') === 'RESERVATION_RULE_VIOLATION';
  } catch (_) {
    return false;
  }
}

export function positiveInteger(value, name) {
  if (!/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(Number(value))) {
    throw new Error(`${name} must be a positive integer`);
  }

  return Number(value);
}
