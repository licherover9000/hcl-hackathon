import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, getSession, inr } from '../api';

export const Codes = ({ codes }) =>
  (codes || '').split('|').filter(Boolean).map((c) => (
    <span key={c} className={`pill ${c.startsWith('HOLD') || c === 'PRE_LEGAL_NOTICE' ? 'bad' : 'warn'}`}>{c}</span>
  ));

export const Bucket = ({ b }) => <span className={`pill b-${b}`}>{b}</span>;

export default function Worklist() {
  const user = getSession().user;
  const [mode, setMode] = useState('rules');
  const [queue, setQueue] = useState('');
  const [queues, setQueues] = useState([]);
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const navigate = useNavigate();

  useEffect(() => {
    if (user.role !== 'COLLECTOR') api.queues().then(setQueues).catch(() => {});
  }, [user.role]);

  useEffect(() => {
    setError('');
    api.worklist(mode, queue).then(setData).catch((e) => setError(e.message));
  }, [mode, queue]);

  const rows = data?.rows || [];
  const totalDues = rows.reduce((s, r) => s + Number(r.total_dues || 0), 0);
  const escalated = rows.filter((r) => /BROKEN_PTP_REPEAT|HIGH_VALUE/.test(r.codes || '')).length;

  return (
    <>
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-end' }}>
        <div>
          <p className="sub">Day-end as of {data?.asOfDate || '…'} · {user.role === 'COLLECTOR' ? 'loans assigned to me' : 'all queues'}</p>
          <h1>{user.role === 'COLLECTOR' ? 'My worklist' : 'Team worklists'}</h1>
        </div>
        <div className="row" style={{ alignItems: 'center' }}>
          {user.role !== 'COLLECTOR' && (
            <select value={queue} onChange={(e) => setQueue(e.target.value)} aria-label="Queue">
              <option value="">All queues</option>
              {queues.map((q) => <option key={q.queue} value={q.queue}>{q.queue} ({q.loans})</option>)}
            </select>
          )}
          <div className="toggle" role="group" aria-label="Ranking">
            <button className={mode === 'rules' ? 'on' : ''} onClick={() => setMode('rules')}>Rules order</button>
            <button className={mode === 'ai' ? 'on' : ''} onClick={() => setMode('ai')}>AI order</button>
          </div>
        </div>
      </div>
      {error && <div className="msg bad">{error}</div>}
      {data?.message && <div className="msg warn">{data.message}</div>}
      <div className="stats">
        <div className="stat"><div className="k">Accounts</div><div className="v">{rows.length}</div></div>
        <div className="stat"><div className="k">Total dues</div><div className="v">{inr(totalDues)}</div></div>
        <div className="stat"><div className="k">Escalated</div><div className="v">{escalated}</div></div>
        <div className="stat"><div className="k">Ranking</div><div className="v">{data?.mode === 'ai' ? 'AI' : 'Rules'}</div></div>
      </div>
      <div className="tablebox">
        <table>
          <thead>
            <tr>
              <th>Rank</th><th>Loan</th><th>Borrower</th><th>Bucket</th><th className="num">DPD</th>
              <th className="num">Overdue</th><th className="num">Total dues</th><th>Action</th><th>Queue</th><th>Flags</th>
              {mode === 'ai' && <th className="num">Miss risk</th>}
              {user.role !== 'COLLECTOR' && <th>Assigned</th>}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.loan_id} className="click" onClick={() => navigate(`/loans/${r.loan_id}`)}>
                <td><strong>{r.shown_rank ?? '–'}</strong></td>
                <td>{r.loan_id}</td>
                <td>{r.full_name}<br /><span className="sub">{r.mobile}</span></td>
                <td><Bucket b={r.bucket} /></td>
                <td className="num">{r.dpd}</td>
                <td className="num">{inr(r.overdue_amount)}</td>
                <td className="num">{inr(r.total_dues)}</td>
                <td>{mode === 'ai' && r.ai_strategy ? r.ai_strategy : r.contact_strategy}</td>
                <td>{r.shown_queue}</td>
                <td><Codes codes={mode === 'ai' && r.ai_codes ? r.ai_codes : r.codes} /></td>
                {mode === 'ai' && <td className="num">{r.p_miss_next != null ? `${Math.round(r.p_miss_next * 100)}%` : '–'}</td>}
                {user.role !== 'COLLECTOR' && <td>{r.assigned_to || '–'}</td>}
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={12} className="sub">No accounts. Run the day-end first, or nothing is assigned to you.</td></tr>}
          </tbody>
        </table>
      </div>
      <div className="msg info">
        <strong>Ranking rule:</strong> escalated accounts first (broken promises or high value), then days past due, then overdue amount,
        then loan ID. AI order sorts by expected recovery = (1 − miss risk) × (overdue + EMI); ties keep the rules order.
      </div>
    </>
  );
}
