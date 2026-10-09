// k6 smoke test: login, dashboard, invoice list, member list.
//
//   k6 run -e BASE_URL=https://staging.example.com -e EMAIL=admin@example.com -e PASSWORD='...' backend/perf/smoke.k6.js
//
// Use a staging community with realistic data (see docs/performance.md for the seed). Never point this at production with real
// credentials in the shell history: pass them through the environment of the CI job.
//
// Thresholds are the targets from the blueprint: list endpoints p95 < 300 ms, under 1% errors. A failed threshold exits non-zero.
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const EMAIL = __ENV.EMAIL;
const PASSWORD = __ENV.PASSWORD;

const dashboard = new Trend('dashboard_ms', true);
const invoices = new Trend('invoice_list_ms', true);
const members = new Trend('member_list_ms', true);

export const options = {
  scenarios: {
    smoke: { executor: 'constant-vus', vus: 5, duration: '1m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    invoice_list_ms: ['p(95)<300'],
    member_list_ms: ['p(95)<300'],
    dashboard_ms: ['p(95)<500'],
    'http_req_duration{endpoint:login}': ['p(95)<1500'], // BCrypt cost 12 is slow on purpose
  },
};

export function setup() {
  if (!EMAIL || !PASSWORD) throw new Error('Set EMAIL and PASSWORD');
  const res = http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({ email: EMAIL, password: PASSWORD }), {
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: 'login' },
  });
  check(res, { 'login 200': (r) => r.status === 200 });
  return { token: res.json('accessToken') };
}

export default function (data) {
  const auth = { headers: { Authorization: `Bearer ${data.token}` } };

  group('dashboard', () => {
    const r = http.get(`${BASE}/api/v1/community/dashboard`, auth);
    dashboard.add(r.timings.duration);
    check(r, { 'dashboard 200': (x) => x.status === 200, 'has members': (x) => x.json('members') !== undefined });
  });
  group('invoice list', () => {
    const r = http.get(`${BASE}/api/v1/community/invoices?size=20`, auth);
    invoices.add(r.timings.duration);
    check(r, { 'invoices 200': (x) => x.status === 200 });
    const overdue = http.get(`${BASE}/api/v1/community/invoices?status=OVERDUE&size=20`, auth);
    invoices.add(overdue.timings.duration);
    check(overdue, { 'overdue 200': (x) => x.status === 200 });
  });
  group('member list', () => {
    const r = http.get(`${BASE}/api/v1/community/members?size=20`, auth);
    members.add(r.timings.duration);
    check(r, { 'members 200': (x) => x.status === 200 });
  });
  sleep(1);
}
