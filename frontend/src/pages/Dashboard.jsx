import { useEffect, useState } from 'react';
import { api, inr } from '../api';

const BUCKETS = ['CURRENT', 'SMA_0', 'SMA_1', 'SMA_2', 'NPA'];

export default function Dashboard() {
  const [filters, setFilters] = useState({ from: '', to: '', branch: '', product: '' });
  const [d, setD] = useState(null);
  const [error, setError] = useState('');

  useEffect(() => {
    api.dashboard(filters).then(setD).catch((e) => setError(e.message));
  }, [filters]);

  if (error) return <div className="msg bad">{error}</div>;
  if (!d) return <p className="sub">Loading…</p>;
  if (!d.buckets) return <div className="msg warn">{d.message}</div>;
  const byBucket = Object.fromEntries(d.buckets.map((b) => [b.bucket, b]));
  const m = d.rollRate.matrix;

  return (
    <>
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-end' }}>
        <div>
          <p className="sub">As of {d.asOfDate}</p>
          <h1>Collections dashboard</h1>
        </div>
        <div className="row">
          <select aria-label="Branch" value={filters.branch} onChange={(e) => setFilters({ ...filters, branch: e.target.value })}>
            <option value="">All branches</option>{d.branches.map((b) => <option key={b}>{b}</option>)}
          </select>
          <select aria-label="Product" value={filters.product} onChange={(e) => setFilters({ ...filters, product: e.target.value })}>
            <option value="">All products</option>{d.products.map((p) => <option key={p}>{p}</option>)}
          </select>
          <select aria-label="From date" value={filters.from} onChange={(e) => setFilters({ ...filters, from: e.target.value })}>
            <option value="">From: previous run</option>{d.availableDates.map((x) => <option key={x}>{x}</option>)}
          </select>
          <select aria-label="To date" value={filters.to} onChange={(e) => setFilters({ ...filters, to: e.target.value })}>
            <option value="">To: latest run</option>{d.availableDates.map((x) => <option key={x}>{x}</option>)}
          </select>
        </div>
      </div>
      <div className="stats">
        {BUCKETS.map((b) => (
          <div key={b} className="stat">
            <div className="k"><span className={`pill b-${b}`}>{b}</span></div>
            <div className="v">{byBucket[b]?.loans ?? 0} loans</div>
            <div className="sub">{inr(byBucket[b]?.total_dues ?? 0)} dues</div>
          </div>
        ))}
      </div>
      <div className="row">
        <div className="grow tablebox">
          <table className="matrix">
            <thead>
              <tr><th>Loans: {d.rollRate.fromDate} (rows) → {d.rollRate.toDate} (columns)</th>{BUCKETS.map((b) => <th key={b}>{b}</th>)}</tr>
            </thead>
            <tbody>
              {BUCKETS.map((r, ri) => (
                <tr key={r}>
                  <th>{r}</th>
                  {BUCKETS.map((c, ci) => (
                    <td key={c} className={ri === ci ? 'diag' : ci > ri && m[r][c] > 0 ? 'worse' : ''}>{m[r][c]}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div style={{ width: 300, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div className="stat"><div className="k">Roll rate SMA_0 → SMA_1</div><div className="v">{d.rollRate.rollRateSma0ToSma1Pct ?? '–'}%</div><div className="sub">target below 20%</div></div>
          <div className="stat"><div className="k">Loans moved to a worse bucket</div><div className="v">{d.rollRate.loansMovedToWorseBucket}</div></div>
          <div className="stat"><div className="k">PTP kept rate</div><div className="v">{d.ptp.keptRatePct ?? '–'}%</div><div className="sub">kept {d.ptp.kept} · broken {d.ptp.broken} · open {d.ptp.open}</div></div>
          <div className="stat">
            <div className="k">Contacts blocked by fair-practice rules</div>
            <div className="v">{d.contactsBlocked.reduce((s, x) => s + Number(x.attempts), 0)}</div>
            <div className="sub">{d.contactsBlocked.map((x) => `${x.reason}: ${x.attempts}`).join(' · ')}</div>
          </div>
          <div className="stat">
            <div className="k">Settlements by status</div>
            <div className="sub">{d.settlementsByStatus.map((x) => `${x.status}: ${x.requests}`).join(' · ') || 'none'}</div>
          </div>
        </div>
      </div>
      <p className="sub">Figures are aggregates of the day-end snapshots for the two dates, the contacts table and the settlements table.</p>
    </>
  );
}
