// Load test (PLAN Phase 8): 300 virtual patients join over 2 minutes and keep their live stream open;
// a receptionist calls the next patient every 20 seconds.
//
//   docker run --rm -i --add-host=host.docker.internal:host-gateway -v "$PWD/load:/load" \
//     grafana/k6:1.8.3 run /load/join-and-poll.js
//
// Env: BASE (default http://host.docker.internal:8081), CLINIC (sharma-clinic), QUEUE (dr-sharma),
// STAFF_USER / STAFF_PASSWORD (default: the dev seed receptionist from application-dev.yml).
// The app must run with the dev profile and room for 300 more open tokens
// (--baari.queue.max-open-tokens=2000). Afterwards run load/verify-numbering.sql.
//
// Every patient sends its own X-Forwarded-For address, so the per-IP rate limits see 300 different
// phones, as in a real waiting room. The app reads that header because it expects to sit behind
// Caddy, which in production overwrites whatever a client sends (ADR 0014).
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

const BASE = __ENV.BASE || 'http://host.docker.internal:8081';
const CLINIC = __ENV.CLINIC || 'sharma-clinic';
const QUEUE = __ENV.QUEUE || 'dr-sharma';
const STAFF_USER = __ENV.STAFF_USER || 'reception';
const STAFF_PASSWORD = __ENV.STAFF_PASSWORD || 'dev-reception-pass';

export const options = {
  scenarios: {
    patients: {
      executor: 'per-vu-iterations',
      exec: 'patient',
      vus: 300,
      iterations: 1,
      maxDuration: '6m',
      startTime: '0s',
    },
    reception: {
      executor: 'constant-arrival-rate',
      exec: 'reception',
      rate: 1,
      timeUnit: '20s',
      duration: '4m',
      preAllocatedVUs: 1,
      startTime: '10s',
    },
  },
  thresholds: {
    'http_req_duration{name:join}': ['p(95)<300'],
    'http_req_failed{name:join}': ['rate==0'],
    server_errors: ['count==0'],
  },
};

import { Counter } from 'k6/metrics';
const serverErrors = new Counter('server_errors');

function count5xx(res) {
  if (res.status >= 500) {
    serverErrors.add(1, { url: res.request.url });
  }
}

/** Logs the receptionist in and returns the CSRF header for API calls. */
function staffLogin(queueId) {
  const page = http.get(`${BASE}/login`);
  const csrf = page.html().find('input[name=_csrf]').attr('value');
  http.post(`${BASE}/login`, { username: STAFF_USER, password: STAFF_PASSWORD, _csrf: csrf });
  // the queue's console page carries the CSRF token for the console's API calls
  const console = http.get(`${BASE}/staff/queues/${queueId}`);
  return {
    name: console.html().find('meta[name=csrf-header]').attr('content'),
    value: console.html().find('meta[name=csrf-token]').attr('content'),
  };
}

export function setup() {
  const queue = http.get(`${BASE}/api/public/queues/${CLINIC}/${QUEUE}`).json();
  const csrf = staffLogin(queue.queueId);
  // open today's queue (409 if it is open already: fine)
  const opened = http.post(`${BASE}/api/staff/queues/${queue.queueId}/days/today/open`, null, {
    headers: { [csrf.name]: csrf.value },
  });
  if (opened.status !== 200 && opened.status !== 409) {
    throw new Error(`could not open today's queue: HTTP ${opened.status}`);
  }
  return { queueId: queue.queueId };
}

export function patient(data) {
  const vu = exec.vu.idInTest;
  // spread 300 joins over 2 minutes
  sleep((vu % 300) * (120 / 300));
  const ip = `10.${(vu >> 8) & 255}.${vu & 255}.${1 + (vu % 250)}`;
  const headers = { 'Content-Type': 'application/json', 'X-Forwarded-For': ip };
  const body = JSON.stringify({
    name: `Load patient ${vu}`,
    visitType: 'NEW',
    consentVersion: 'v1',
    idempotencyKey: crypto.randomUUID(),
  });
  const res = http.post(`${BASE}/api/public/queues/${data.queueId}/tokens`, body, { headers, tags: { name: 'join' } });
  count5xx(res);
  if (!check(res, { 'joined (201)': (r) => r.status === 201 })) {
    return;
  }
  const code = res.json('publicCode');
  // the patient's page: one read, then the live stream held open (SSE) until the test ends
  count5xx(http.get(`${BASE}/api/public/tokens/${code}`, { headers: { 'X-Forwarded-For': ip }, tags: { name: 'token' } }));
  const stream = http.get(`${BASE}/api/public/tokens/${code}/stream`, {
    headers: { 'X-Forwarded-For': ip, Accept: 'text/event-stream' },
    timeout: '150s',
    tags: { name: 'sse' },
    responseCallback: http.expectedStatuses(0, 200),
  });
  count5xx(stream);
}

let staff = null;

export function reception(data) {
  if (staff === null) {
    staff = staffLogin(data.queueId);
  }
  const headers = { [staff.name]: staff.value, 'Content-Type': 'application/json' };
  const called = http.post(`${BASE}/api/staff/queues/${data.queueId}/call-next`, null, { headers, tags: { name: 'call' } });
  count5xx(called);
  if (called.status === 200) {
    const id = called.json('id');
    count5xx(http.post(`${BASE}/api/staff/tokens/${id}/start`, null, { headers, tags: { name: 'start' } }));
    count5xx(http.post(`${BASE}/api/staff/tokens/${id}/complete`, null, { headers, tags: { name: 'complete' } }));
  }
}
