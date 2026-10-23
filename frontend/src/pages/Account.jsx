import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, getSession, inr } from '../api';
import { Bucket, Codes } from './Worklist';

function nowLocal() {
  const d = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export default function Account() {
  const { loanId } = useParams();
  const user = getSession().user;
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [form, setForm] = useState({ channel: 'CALL', contactTs: nowLocal(), outcome: 'PTP', ptpDate: '', ptpAmount: '', notes: '' });
  const [contactMsg, setContactMsg] = useState(null);
  const [offer, setOffer] = useState({ amount: '', reason: '' });
  const [offerMsg, setOfferMsg] = useState(null);

  const load = useCallback(() => {
    api.account(loanId).then(setData).catch((e) => setError(`${e.status === 403 ? 'Access denied (403): ' : ''}${e.message}`));
  }, [loanId]);
  useEffect(load, [load]);

  const canAct = user.role === 'COLLECTOR' || user.role === 'MANAGER';

  const submitContact = async (e) => {
    e.preventDefault();
    setContactMsg(null);
    try {
      const body = {
        channel: form.channel,
        outcome: form.outcome,
        contactTs: form.contactTs ? `${form.contactTs}:00+05:30` : null,
        notes: form.notes,
        ...(form.outcome === 'PTP' ? { ptpDate: form.ptpDate, ptpAmount: Number(form.ptpAmount) } : {}),
      };
      const r = await api.logContact(loanId, body);
      setContactMsg({ ok: true, text: `Saved ${r.contactId}` });
      load();
    } catch (err) {
      setContactMsg({ ok: false, text: `Blocked (${err.status}): ${err.message}` });
    }
  };

  const submitOffer = async (e) => {
    e.preventDefault();
    setOfferMsg(null);
    try {
      const r = await api.propose(loanId, Number(offer.amount), offer.reason);
      setOfferMsg({ ok: r.route !== 'REJECT', text: `${r.requestId}: discount ${r.discountPct}% → ${r.route}${r.reasonCode ? ` (${r.reasonCode})` : ''}, status ${r.status}` });
      load();
    } catch (err) {
      setOfferMsg({ ok: false, text: `${err.status}: ${err.message}` });
    }
  };

  if (error) return <div className="msg bad">{error}</div>;
  if (!data) return <p className="sub">Loading…</p>;
  const { loan, position: p } = data;
  const dues = Number(p.total_dues || 0);
  const discount = offer.amount ? Math.max(0, ((dues - Number(offer.amount)) / dues) * 100) : null;

  return (
    <>
      <div>
        <p className="sub">{loan.product} · {inr(loan.principal)} · EMI {inr(loan.emi_amount)} · branch {loan.branch} · as of {data.asOfDate}</p>
        <h1>{loan.loan_id} · {loan.full_name} · {loan.mobile}</h1>
      </div>
      <div className="stats">
        <div className="stat"><div className="k">Bucket · DPD</div><div className="v"><Bucket b={p.bucket} /> {p.dpd}</div></div>
        <div className="stat"><div className="k">Overdue</div><div className="v">{inr(p.overdue_amount)}</div></div>
        <div className="stat"><div className="k">Penal charges</div><div className="v">{inr(p.penal_charges)}</div></div>
        <div className="stat"><div className="k">Total dues</div><div className="v">{inr(p.total_dues)}</div></div>
        <div className="stat"><div className="k">Action · queue</div><div className="v" style={{ fontSize: 17 }}>{p.contact_strategy}<br />{p.queue || '–'}</div></div>
      </div>
      <div className="msg info">
        <strong>Why this action:</strong> {data.explanation.join(' ')} <Codes codes={p.codes} />
      </div>
      {p.p_miss_next != null && (
        <div className="msg info">
          <strong>AI:</strong> miss risk {Math.round(p.p_miss_next * 100)}% · expected recovery {inr(p.expected_recovery)} · top factors:{' '}
          {(p.top_factors || '').split('|').join(', ')}
        </div>
      )}
      <div className="row">
        <div className="grow" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div className="tablebox">
            <table>
              <thead>
                <tr><th>#</th><th>Due date</th><th className="num">EMI</th><th className="num">Paid (FIFO)</th><th className="num">Unpaid</th><th>Status</th><th className="num">Days late</th><th className="num">Penal</th></tr>
              </thead>
              <tbody>
                {data.instalments.map((i) => (
                  <tr key={i.instalmentNo}>
                    <td>{i.instalmentNo}</td><td>{i.dueDate}</td><td className="num">{inr(i.emi)}</td><td className="num">{inr(i.paid)}</td>
                    <td className="num">{inr(i.unpaid)}</td>
                    <td><span className={`pill ${i.status === 'OVERDUE' ? 'bad' : i.status === 'PAID' ? 'good' : ''}`}>{i.status}</span></td>
                    <td className="num">{i.daysOverdue || ''}</td><td className="num">{Number(i.penal) ? inr(i.penal) : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="tablebox">
            <table>
              <thead><tr><th>When</th><th>Channel</th><th>Outcome</th><th>Promise</th><th>Status</th><th>By</th></tr></thead>
              <tbody>
                {data.contacts.map((c) => (
                  <tr key={c.contact_id}>
                    <td>{String(c.contact_ts).replace('T', ' ').slice(0, 16)}</td><td>{c.channel}</td><td>{c.outcome}</td>
                    <td>{c.ptp_date ? `${inr(c.ptp_amount)} by ${c.ptp_date}` : ''}</td>
                    <td>{c.ptp_status && <span className={`pill ${c.ptp_status === 'BROKEN' ? 'bad' : c.ptp_status === 'KEPT' ? 'good' : ''}`}>{c.ptp_status}</span>}</td>
                    <td>{c.collector_id}</td>
                  </tr>
                ))}
                {data.contacts.length === 0 && <tr><td colSpan={6} className="sub">No contacts yet.</td></tr>}
              </tbody>
            </table>
          </div>
          <div className="tablebox">
            <table>
              <thead><tr><th>Payment</th><th>Date</th><th className="num">Amount</th></tr></thead>
              <tbody>
                {data.payments.slice(-12).reverse().map((x) => (
                  <tr key={x.paymentId}><td>{x.paymentId}</td><td>{x.date}</td><td className="num">{inr(x.amount)}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        <div style={{ width: 340, display: 'flex', flexDirection: 'column', gap: 16 }}>
          {canAct && (
            <form className="card form" onSubmit={submitContact}>
              <h2>Log contact</h2>
              <label>Channel
                <select value={form.channel} onChange={(e) => setForm({ ...form, channel: e.target.value })}>
                  <option value="CALL">Call</option><option value="FIELD_VISIT">Field visit</option>
                </select>
              </label>
              <label>Contact time (IST)<input type="datetime-local" value={form.contactTs} onChange={(e) => setForm({ ...form, contactTs: e.target.value })} /></label>
              <label>Outcome
                <select value={form.outcome} onChange={(e) => setForm({ ...form, outcome: e.target.value })}>
                  {['PTP', 'NO_ANSWER', 'REFUSED', 'CALLBACK', 'UNREACHABLE', 'WRONG_NUMBER'].map((o) => <option key={o}>{o}</option>)}
                </select>
              </label>
              {form.outcome === 'PTP' && (
                <div className="two">
                  <label>PTP date<input type="date" value={form.ptpDate} onChange={(e) => setForm({ ...form, ptpDate: e.target.value })} /></label>
                  <label>PTP amount<input type="number" step="0.01" value={form.ptpAmount} onChange={(e) => setForm({ ...form, ptpAmount: e.target.value })} /></label>
                </div>
              )}
              <label>Notes<textarea value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} /></label>
              {contactMsg && <div className={`msg ${contactMsg.ok ? 'good' : 'bad'}`}>{contactMsg.text}</div>}
              <button>Save contact</button>
            </form>
          )}
          <div className="card form">
            <h2>Settlement</h2>
            {!data.settlementEligible && <p className="sub">Not eligible: only NPA, or SMA_2 with 75+ days past due, and not on hold.</p>}
            {canAct && data.settlementEligible && (
              <form className="form" onSubmit={submitOffer}>
                <label>Offer amount<input type="number" step="0.01" value={offer.amount} onChange={(e) => setOffer({ ...offer, amount: e.target.value })} required /></label>
                {discount !== null && (
                  <p className="sub">Discount {discount.toFixed(2)}% → {discount <= 20 ? 'Collections Manager' : discount <= 40 ? 'Head of Collections' : 'rejected (above 40%)'}</p>
                )}
                <label>Reason<textarea value={offer.reason} onChange={(e) => setOffer({ ...offer, reason: e.target.value })} /></label>
                <button>Propose settlement</button>
              </form>
            )}
            {offerMsg && <div className={`msg ${offerMsg.ok ? 'good' : 'bad'}`}>{offerMsg.text}</div>}
            {data.settlements.map((s) => (
              <p key={s.request_id} className="sub">{s.request_id}: {inr(s.offer_amount)} ({s.discount_pct}%) · {s.status}</p>
            ))}
          </div>
        </div>
      </div>
    </>
  );
}
