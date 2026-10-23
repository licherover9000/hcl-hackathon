import { useEffect, useState } from 'react';
import { api, download } from '../api';

export default function Audit() {
  const [filters, setFilters] = useState({ entity: '', entityId: '', userId: '' });
  const [applied, setApplied] = useState(filters);
  const [rows, setRows] = useState([]);
  const [error, setError] = useState('');

  useEffect(() => {
    api.audit({ ...applied, limit: 300 }).then(setRows).catch((e) => setError(e.message));
  }, [applied]);

  const qs = new URLSearchParams(applied).toString();

  return (
    <>
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-end' }}>
        <div>
          <h1>Audit trail</h1>
          <p className="sub">Append-only: there is no edit or delete, and the database trigger rejects UPDATE and DELETE on this table.</p>
        </div>
        <button className="secondary" onClick={() => download(`/audit/export?${qs}`, 'audit_export.csv').catch((e) => setError(e.message))}>Export CSV</button>
      </div>
      {error && <div className="msg bad">{error}</div>}
      <form className="row" onSubmit={(e) => { e.preventDefault(); setApplied(filters); }}>
        <label>Entity
          <select value={filters.entity} onChange={(e) => setFilters({ ...filters, entity: e.target.value })}>
            <option value="">Any</option>
            {['LOAN', 'CONTACT', 'SETTLEMENT', 'CONFIG', 'DAY_END', 'DATA'].map((x) => <option key={x}>{x}</option>)}
          </select>
        </label>
        <label>ID (loan, request, setting)<input value={filters.entityId} onChange={(e) => setFilters({ ...filters, entityId: e.target.value })} /></label>
        <label>User<input value={filters.userId} onChange={(e) => setFilters({ ...filters, userId: e.target.value })} /></label>
        <button style={{ alignSelf: 'flex-end' }}>Filter</button>
      </form>
      <div className="tablebox">
        <table>
          <thead><tr><th>When</th><th>User · role</th><th>Entity</th><th>Action</th><th>Before</th><th>After</th><th>Comment</th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id} className={r.action.includes('BLOCKED') ? 'worse' : ''}>
                <td style={{ whiteSpace: 'nowrap' }}>{String(r.ts).slice(0, 19).replace('T', ' ')}</td>
                <td>{r.user_id} · {r.role}</td>
                <td>{r.entity} {r.entity_id}</td>
                <td>{r.action}</td>
                <td style={{ maxWidth: 220, wordBreak: 'break-word' }}>{r.before_json}</td>
                <td style={{ maxWidth: 260, wordBreak: 'break-word' }}>{r.after_json}</td>
                <td>{r.comment}</td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td colSpan={7} className="sub">No audit rows match.</td></tr>}
          </tbody>
        </table>
      </div>
    </>
  );
}
