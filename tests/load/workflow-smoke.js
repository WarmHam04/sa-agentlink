import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8096';
const tenant = __ENV.TENANT_ID || 'load-test';
const token = __ENV.ACCESS_TOKEN || '';
const rate = Number(__ENV.RATE || 5);
const duration = __ENV.DURATION || '30s';
const rejected = new Counter('agentlink_rejected_requests');

export const options = {
  scenarios: {
    workflow_admission: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Math.max(10, rate * 2),
      maxVUs: Math.max(20, rate * 4),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    agentlink_rejected_requests: ['count==0'],
    dropped_iterations: ['count==0'],
  },
};

export default function () {
  const contextId = `k6-${__VU}-${__ITER}-${Date.now()}`;
  const headers = {
    'Content-Type': 'application/json',
    'X-AgentLink-Tenant': tenant,
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const response = http.post(`${baseUrl}/api/v1/workflows`, JSON.stringify({
    contextId,
    goal: 'load-test admission and persistence',
    nodes: [{
      nodeId: 'analysis',
      skill: 'analysis',
      instruction: 'return a small deterministic load-test artifact',
      dependsOn: [],
    }],
  }), { headers, tags: { operation: 'create-workflow' } });

  rejected.add(response.status === 429 || response.status === 409);
  check(response, {
    'workflow accepted': (result) => result.status === 202,
    'context preserved': (result) => result.status === 202 && result.json('contextId') === contextId,
  });
}
