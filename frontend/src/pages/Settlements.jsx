import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, getSession, inr } from '../api';

const TABS = [
  { key: 'PENDING_MANAGER', label: 'Pending manager' },
  { key: 'PENDING_HEAD', label: 'Pending head' },
  { key: '', label: 'All' },
];

export default function Settlements() {
  const user = getSession().user;
  const [tab, setTab] = useState(user.role === 'HEAD' ? 'PENDING_HEAD' : user.role === 'MANAGER' ? 'PENDING_MANAGER' : '');
  const [rows, setRows] = useState([]);
  const [selected, setSelected] = useState(null);
  const [comment, setComment] = useState('');
  const [msg, setMsg] = useState(null);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    api.settlements(tab).then(setRows).catch((e) => setError(e.message));
  }, [tab]);
  useEffect(load, [load]);

  const decide = async (decision) => {
    setMsg(null);
    try {
      const r = await api.decide(selected.request_id, decision, comment, selected.version);
      setMsg({ ok: true, text: `${r.requestId} ${r.status}${r.validUntil ? `, pay by ${r.validUntil}` : ''}`, letter: r.letter });
      setSelected(null);
      setComment('');
      load();
    } catch (e) {
      setMsg({ ok: false, text: `${e.status}: ${e.message}` });
    }
  };

  const canDecide = user.role === 'MANAGER' || user.role === 'HEAD';

  return (
    <>
      <h1>{canDecide ? 'Settlement approvals' : user.role === 'COLLECTOR' ? 'My settlement proposals' : 'Settlements'}</h1>
      {canDecide && (
        <div className="toggle" role="group" aria-label="Status filter" style={{ alignSelf: 'flex-start' }}>
          {TABS.map((t) => <button key={t.key} className={tab === t.key ? 'on' : ''} onClick={() => setTab(t.key)}>{t.label}</button>)}
        </div>
      )}
      {error && <div className="msg bad">{error}</div>}
      {msg && (
        <div className={`msg ${msg.ok ? 'good' : 'bad'}`}>
          {msg.text}
          {msg.letter && <pre className="letter" style={{ marginTop: 8 }}>{msg.letter}</pre>}
        </div>
      )}
      <div className="row">
        <div className="grow tablebox">
          <table>
            <thead>
              <tr><th>Request</th><th>Loan</th><th>Date</th><th className="num">Dues at request</th><th className="num">Offer</th><th className="num">Discount</th><th>Route</th><th>Status</th><th>By</th></tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.request_id} className="click" onClick={() => { setSelected(s); setMsg(null); }}>
                  <td><strong>{s.request_id}</strong></td>
                  <td><Link to={`/loans/${s.loan_id}`} onClick={(e) => e.stopPropagation()}>{s.loan_id}</Link></td>
                  <td>{s.request_date}</td>
                  <td className="num">{inr(s.dues_at_request)}</td>
                  <td className="num">{inr(s.offer_amount)}</td>
                  <td className="num">{Number(s.discount_pct).toFixed(2)}%</td>
                  <td>{s.route}{s.reason_code ? ` (${s.reason_code})` : ''}</td>
                  <td><span className={`pill ${/APPROVED|PAID/.test(s.status) ? 'good' : /REJECT|LAPSED/.test(s.status) ? 'bad' : 'warn'}`}>{s.status}</span></td>
                  <td>{s.proposed_by}</td>
                </tr>
              ))}
              {rows.length === 0 && <tr><td colSpan={9} className="sub">Nothing here.</td></tr>}
            </tbody>
          </table>
        </div>
        {selected && canDecide && (
          <div className="card form" style={{ width: 360 }}>
            <h2>{selected.request_id} · {selected.loan_id}</h2>
            <p className="sub">Dues {inr(selected.dues_at_request)} · offer {inr(selected.offer_amount)} ({Number(selected.discount_pct).toFixed(2)}%) · proposed by {selected.proposed_by} · {selected.status}</p>
            {selected.proposal_reason && <p className="sub">Reason: {selected.proposal_reason}</p>}
            <label>Comment (at least 10 characters)<textarea value={comment} onChange={(e) => setComment(e.target.value)} /></label>
            <div className="row">
              <button className="ok" onClick={() => decide('APPROVE')}>Approve</button>
              <button className="danger" onClick={() => decide('REJECT')}>Reject</button>
            </div>
            <p className="sub">The proposer cannot decide their own request (403). If someone else acts first you get 409.</p>
          </div>
        )}
      </div>
      <div className="msg info">
        Eligible: NPA, or SMA_2 with 75+ days past due, and not on hold. Discount up to 20% → Collections Manager;
        above 20% up to 40% → Head of Collections; above 40% → rejected. Approved offers must be paid within 30 days (PAID) or they lapse.
      </div>
    </>
  );
}
