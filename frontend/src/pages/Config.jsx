import { useCallback, useEffect, useState } from 'react';
import { api, getSession } from '../api';

export default function Config() {
  const user = getSession().user;
  const isHead = user.role === 'HEAD';
  const [data, setData] = useState(null);
  const [edit, setEdit] = useState(null);
  const [msg, setMsg] = useState(null);

  const load = useCallback(() => {
    api.config().then(setData).catch((e) => setMsg({ ok: false, text: e.message }));
  }, []);
  useEffect(load, [load]);

  const save = async (e) => {
    e.preventDefault();
    try {
      const r = await api.updateConfig(edit.key, edit.value, edit.reason);
      setMsg({ ok: true, text: `${r.key}: ${r.oldValue} → ${r.newValue}. Applies from the next day-end; change is in the audit trail.` });
      setEdit(null);
      load();
    } catch (err) {
      setMsg({ ok: false, text: `${err.status}: ${err.message}` });
    }
  };

  if (!data) return <p className="sub">Loading…</p>;
  const eff = data.effective;

  return (
    <>
      <div>
        <h1>Rules config</h1>
        <p className="sub">Loaded from case_03_rules_config_V1.0.json into the rules_config table. {isHead ? 'You can edit values.' : 'Only the Head of Collections can edit.'}</p>
      </div>
      {msg && <div className={`msg ${msg.ok ? 'good' : 'bad'}`}>{msg.text}</div>}
      <div className="row">
        <div className="grow tablebox">
          <table>
            <thead><tr><th>Setting (from the rules file)</th><th>Value</th><th>Last changed by</th>{isHead && <th />}</tr></thead>
            <tbody>
              {data.settings.map((s) => (
                <tr key={s.config_key}>
                  <td>{s.config_key}</td><td>{s.config_value}</td><td>{s.updated_by}</td>
                  {isHead && <td><button className="secondary" onClick={() => setEdit({ key: s.config_key, value: s.config_value, reason: '' })}>Edit</button></td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div style={{ width: 360, display: 'flex', flexDirection: 'column', gap: 16 }}>
          {edit && (
            <form className="card form" onSubmit={save}>
              <h2>Edit {edit.key}</h2>
              <label>New value<input value={edit.value} onChange={(e) => setEdit({ ...edit, value: e.target.value })} /></label>
              <label>Reason (at least 10 characters)<textarea value={edit.reason} onChange={(e) => setEdit({ ...edit, reason: e.target.value })} /></label>
              <div className="row"><button>Save change</button><button type="button" className="secondary" onClick={() => setEdit(null)}>Cancel</button></div>
            </form>
          )}
          <div className="card">
            <h2>Values the engine uses</h2>
            <table>
              <tbody>
                {Object.entries(eff).filter(([k]) => k !== 'defaultsUsed').map(([k, v]) => (
                  <tr key={k}><td>{k}</td><td className="num">{String(v)}</td></tr>
                ))}
              </tbody>
            </table>
            {eff.defaultsUsed?.length > 0 && <p className="sub">Case defaults used (no matching key in the file): {eff.defaultsUsed.join(', ')}</p>}
          </div>
        </div>
      </div>
    </>
  );
}
