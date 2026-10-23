const request = require('supertest');
const app = require('../src/app');

describe('GET /api/health', () => {
  it('returns 200 with status UP, a message and an ISO timestamp', async () => {
    const res = await request(app).get('/api/health');

    expect(res.status).toBe(200);
    expect(res.body.status).toBe('UP');
    expect(res.body.message).toBe('Backend server is running smoothly');
    // Round-tripping through Date proves the timestamp is a valid ISO string.
    expect(new Date(res.body.timestamp).toISOString()).toBe(res.body.timestamp);
  });
});

describe('GET /api/info', () => {
  it('returns 200 with the project info', async () => {
    const res = await request(app).get('/api/info');

    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      project: 'HCL Hackathon Solution',
      status: 'Active',
      version: '1.0.0'
    });
  });
});
