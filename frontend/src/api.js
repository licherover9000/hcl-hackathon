// All HTTP calls to the Spring Boot API. The token is kept in sessionStorage (cleared when the tab closes).
// Errors come back as { status, code, message } and are thrown as ApiError so screens can show the reason.

const BASE = import.meta.env.VITE_API_URL || '/api';

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export function getSession() {
  try {
    return JSON.parse(sessionStorage.getItem('rp_session') || 'null');
  } catch {
    return null;
  }
}

export function setSession(s) {
  if (s) sessionStorage.setItem('rp_session', JSON.stringify(s));
  else sessionStorage.removeItem('rp_session');
}

async function request(method, path, body, isForm = false) {
  const session = getSession();
  const headers = {};
  if (session?.token) headers.Authorization = `Bearer ${session.token}`;
  if (body && !isForm) headers['Content-Type'] = 'application/json';
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body ? (isForm ? body : JSON.stringify(body)) : undefined,
  });
  if (res.status === 401 && path !== '/auth/login') {
    setSession(null);
    window.location.assign('/login');
  }
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  if (!res.ok) {
    throw new ApiError(res.status, data?.code || 'ERROR', data?.message || `Request failed (${res.status})`);
  }
  return data;
}

export const api = {
  login: (userId, password) => request('POST', '/auth/login', { userId, password }),
  worklist: (mode, queue) => request('GET', `/worklist?mode=${mode}${queue ? `&queue=${encodeURIComponent(queue)}` : ''}`),
  queues: () => request('GET', '/queues'),
  account: (loanId) => request('GET', `/loans/${encodeURIComponent(loanId)}`),
  logContact: (loanId, body) => request('POST', `/loans/${encodeURIComponent(loanId)}/contacts`, body),
  reassign: (loanId, toUserId, reason) => request('POST', `/loans/${encodeURIComponent(loanId)}/reassign`, { toUserId, reason }),
  settlements: (status) => request('GET', `/settlements${status ? `?status=${status}` : ''}`),
  propose: (loanId, offerAmount, reason) => request('POST', '/settlements', { loanId, offerAmount, reason }),
  decide: (requestId, decision, comment, version) =>
    request('POST', `/settlements/${encodeURIComponent(requestId)}/decision`, { decision, comment, version }),
  seed: () => request('POST', '/admin/seed'),
  importPayments: (file) => {
    const fd = new FormData();
    fd.append('file', file);
    return request('POST', '/admin/import/payments', fd, true);
  },
  importEvaluation: () => request('POST', '/admin/import/evaluation'),
  dayEnd: (asOfDate) => request('POST', '/admin/day-end', { asOfDate }),
  settlementFile: (evalFile) => request('POST', `/admin/settlement-file?eval=${evalFile}`),
  runs: () => request('GET', '/admin/runs'),
  outbox: () => request('GET', '/admin/outbox'),
  config: () => request('GET', '/config'),
  updateConfig: (key, value, reason) => request('PUT', `/config/${encodeURIComponent(key)}`, { value, reason }),
  dashboard: (params) => request('GET', `/dashboard?${new URLSearchParams(params).toString()}`),
  audit: (params) => request('GET', `/audit?${new URLSearchParams(params).toString()}`),
};

// Downloads go through fetch so the token is sent in the header, then saved as a file.
export async function download(path, filename) {
  const session = getSession();
  const res = await fetch(BASE + path, { headers: { Authorization: `Bearer ${session?.token}` } });
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new ApiError(res.status, data.code, data.message || 'Download failed');
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

export const inr = (v) =>
  v === null || v === undefined || v === ''
    ? '–'
    : '₹' + Number(v).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
