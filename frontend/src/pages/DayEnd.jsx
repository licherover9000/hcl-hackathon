import { useCallback, useEffect, useState } from 'react';
import { api, download, getSession } from '../api';

export default function DayEnd() {
  const user = getSession().user;
  const canRun = user.role === 'MANAGER' || user.role === 'HEAD';
  const [asOf, setAsOf] = useState('2026-03-31');
  const [runs, setRuns] = useState([]);
  const [outbox, setOutbox] = useState(null);
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');

  const load = useCallback(() => {
    api.runs().then(setRuns).catch((e) => setError(e.message));
    api.outbox().then(setOutbox).catch(() => {});
  }, []);
  useEffect(load, [load]);

  const act = async (label, fn) => {
    setBusy(label);
    setError('');
    try {
      setResult({ label, data: await fn() });
      load();
    } catch (e) {
      setError(`${label}: ${e.message}`);
    } finally {
      setBusy('');
    }
  };

  return (
    <>
      <h1>Day-end and data</h1>
      {error && <div className="msg bad">{error}</div>}
      {canRun && (
        <div className="row">
          <div className="card form grow">
            <h2>1. Load data</h2>
            <p className="sub">Seed loads every file in the data folder (safe to repeat). Payment files can also be uploaded; duplicate payment IDs are skipped.</p>
            <div className="row">
              <button disabled={!!busy} onClick={() => act('Seed', api.seed)}>Load seed data</button>
              <button className="secondary" disabled={!!busy} onClick={() => act('Evaluation files', api.importEvaluation)}>Import evaluation files</button>
            </div>
            <label>Upload a payment file (CSV)
              <input type="file" accept=".csv" onChange={(e) => e.target.files[0] && act('Payment import', () => api.importPayments(e.target.files[0]))} />
            </label>
          </div>
          <div className="card form grow">
            <h2>2. Run day-end</h2>
            <label>As-of date<input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} /></label>
            <div className="row">
              <button disabled={!!busy} onClick={() => act('Day-end', () => api.dayEnd(asOf))}>{busy === 'Day-end' ? 'Running…' : 'Run day-end'}</button>
              <button className="secondary" disabled={!!busy} onClick={() => act('Settlement file', () => api.settlementFile(asOf > '2026-03-31'))}>Evaluate settlement request file</button>
            </div>
            <p className="sub">Re-running the same date gives the same result. Outputs: collections_output.csv, settlement_decisions.csv (and risk_scores.csv when AI is up).</p>
          </div>
        </div>
      )}
      {result && (
        <div className="card">
          <h2>{result.label}: done</h2>
          <pre className="letter">{JSON.stringify(result.data, null, 2)}</pre>
        </div>
      )}
      <div className="row">
        {['collections_output.csv', 'settlement_decisions.csv', 'risk_scores.csv'].map((f) => (
          <button key={f} className="secondary" onClick={() => download(`/admin/outputs/${f}`, f).catch((e) => setError(e.message))}>Download {f}</button>
        ))}
      </div>
      <div className="tablebox">
        <table>
          <thead><tr><th>Run</th><th>As-of date</th><th>Run by</th><th>Started</th><th className="num">Loans</th><th>AI</th><th>Status</th></tr></thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.run_id}>
                <td>{r.run_id}</td><td>{r.as_of_date}</td><td>{r.run_by}</td><td>{String(r.started_at).slice(0, 19).replace('T', ' ')}</td>
                <td className="num">{r.loans}</td><td>{r.ai_available ? 'used' : 'rules only'}</td><td>{r.status}</td>
              </tr>
            ))}
            {runs.length === 0 && <tr><td colSpan={7} className="sub">No runs yet.</td></tr>}
          </tbody>
        </table>
      </div>
      {outbox && (
        <div className="row">
          <div className="grow tablebox">
            <table>
              <thead><tr><th>Outbox ({outbox.asOfDate}): loan</th><th>Channel</th><th>Lang</th><th>Message (written, not sent)</th></tr></thead>
              <tbody>
                {(outbox.messages || []).slice(0, 40).map((m, i) => (
                  <tr key={i}><td>{m.loan_id}</td><td>{m.channel}</td><td>{m.language}</td><td>{m.message}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="grow card">
            <h2>Letters</h2>
            {(outbox.letters || []).slice(0, 3).map((l, i) => <pre key={i} className="letter" style={{ marginBottom: 8 }}>{l.body}</pre>)}
            {(outbox.settlementLetters || []).slice(0, 2).map((l) => <pre key={l.request_id} className="letter" style={{ marginBottom: 8 }}>{l.letter_text}</pre>)}
          </div>
        </div>
      )}
    </>
  );
}
