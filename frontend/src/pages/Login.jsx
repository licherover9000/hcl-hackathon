import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, setSession } from '../api';

export default function Login() {
  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('demo123');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      const res = await api.login(userId.trim(), password);
      setSession(res);
      navigate('/');
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login">
      <div className="left">
        <div>
          <p className="sub" style={{ color: '#8fb3ff', letterSpacing: 3, fontWeight: 600 }}>NARMADA FINANCE BANK</p>
          <h1 style={{ color: '#f7f6f2', fontSize: 46 }}>RecoverPlus</h1>
          <p style={{ color: '#c9d3e6', fontSize: 18, lineHeight: 1.5 }}>
            Collections early warning, worklists and settlements in one place.
          </p>
        </div>
        <div style={{ color: '#c9d3e6', fontSize: 14, lineHeight: 1.7 }}>
          <strong style={{ color: '#f7f6f2' }}>One login, four views</strong><br />
          Collector: worklist, account view, contacts, settlement proposals<br />
          Collections Manager: approvals up to 20%, operations, dashboard<br />
          Head of Collections: approvals above 20% up to 40%, rules config<br />
          Auditor: read-only accounts and audit trail
        </div>
      </div>
      <div className="right">
        <form className="card form" style={{ width: 400, padding: 32 }} onSubmit={submit}>
          <h2 style={{ fontSize: 24 }}>Sign in</h2>
          <label>User ID<input value={userId} onChange={(e) => setUserId(e.target.value)} placeholder="e.g. U401" required /></label>
          <label>Password<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required /></label>
          {error && <div className="msg bad">{error}</div>}
          <button disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
          <p className="sub">Demo users come from the users file; the demo password is set at seed time (default demo123).</p>
        </form>
      </div>
    </div>
  );
}
