import { useState, type FormEvent } from 'react';
import type { Ctx } from './ctx';
import type { AppointmentType, PreferredWindow, Specialty } from './types';
import { PREF_LABEL, SPEC, TYPE, ago, fmtTime, specOf, timeMs, typeOf } from './format';
import { Plus } from './icons';

const WSTAT: Record<string, [string, string, string]> = {
  WAITING: ['Waiting', '#475467', '#F2F4F7'],
  OFFERED: ['Offer ready', '#B54708', '#FEF0E1'],
  BOOKED: ['Booked', '#067647', '#E5F5EC'],
  DECLINED: ['Declined', '#912018', '#FEF3F2'],
};

export function WaitlistView({ ctx }: { ctx: Ctx }) {
  const { data } = ctx;
  const [showForm, setShowForm] = useState(false);
  const order: Record<string, number> = { OFFERED: 0, WAITING: 1, BOOKED: 2, DECLINED: 3 };
  const rows = [...data.waitlist].sort((a, b) => (order[a.status] ?? 9) - (order[b.status] ?? 9) || timeMs(a.createdAt) - timeMs(b.createdAt));
  const notes = [...data.notifications].sort((a, b) => timeMs(b.createdAt) - timeMs(a.createdAt));

  return (
    <div className="stack">
      <div className="pagehead">
        <div>
          <h1>Waitlist</h1>
          <p>Patients who want an earlier time. When a matching slot frees up, SlotMind offers it here — confirm with the patient, then accept.</p>
        </div>
        <button type="button" className="btn primary" aria-expanded={showForm} onClick={() => setShowForm((v) => !v)}>
          <Plus /> {showForm ? 'Close form' : 'Add to waitlist'}
        </button>
      </div>

      {showForm && <WaitlistForm ctx={ctx} onDone={() => setShowForm(false)} />}

      <div className="table">
        <div className="trow head wait-grid">
          <span>Patient</span><span>Type</span><span>Needs</span><span>Prefers</span><span>Waiting</span><span>Status</span><span style={{ textAlign: 'right' }}>Offer</span>
        </div>
        {rows.map((w) => {
          const T = typeOf(w.type);
          const S = WSTAT[w.status] ?? [w.status, '#475467', '#F2F4F7'];
          const busy = !!ctx.busy['w-' + w.id];
          return (
            <div key={w.id} className="trow wait-grid">
              <span style={{ display: 'flex', flexDirection: 'column' }}>
                <span style={{ fontWeight: 600 }}>{w.patientName}</span>
                {w.contact && <span className="small muted">{w.contact}</span>}
              </span>
              <span><span className="badge" style={{ background: T.bg, color: T.fg }}>{T.label}</span></span>
              <span style={{ color: '#344054' }}>{specOf(w.specialty)}</span>
              <span style={{ color: '#344054' }}>{PREF_LABEL[w.preferredWindow] ?? w.preferredWindow}</span>
              <span className="muted">{ago(w.createdAt)}</span>
              <span><span className="badge" style={{ background: S[2], color: S[1] }}>{S[0]}</span></span>
              <span className="cell-actions" style={{ alignItems: 'center' }}>
                {w.status === 'OFFERED' && w.offeredSlot ? (
                  <>
                    <span style={{ fontSize: 13 }}><strong>{fmtTime(w.offeredSlot.startTime)}</strong> · {w.offeredSlot.providerName}</span>
                    <button type="button" className="btn sm" disabled={busy} onClick={() => ctx.waitlistDecline(w.id)}>Decline</button>
                    <button type="button" className="btn sm primary" disabled={busy} onClick={() => ctx.waitlistAccept(w.id)}>Accept</button>
                  </>
                ) : w.status === 'BOOKED' && w.offeredSlot ? (
                  <span className="small muted">Booked {fmtTime(w.offeredSlot.startTime)} · {w.offeredSlot.providerName}</span>
                ) : w.status === 'WAITING' ? (
                  <span className="small muted">No matching slot yet</span>
                ) : null}
              </span>
            </div>
          );
        })}
        {rows.length === 0 && <p className="muted" style={{ padding: '24px 16px', textAlign: 'center' }}>Nobody is on the waitlist.</p>}
      </div>

      <section className="card" aria-labelledby="notif-h">
        <div className="card-head" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <h2 id="notif-h" style={{ flex: 1 }}>Notifications sent</h2>
          <span className="small muted">SMS / email confirmations after each booking change</span>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <div className="trow head notif-grid"><span>Time</span><span>Channel</span><span>To</span><span>Message</span><span>Status</span></div>
          {notes.map((n) => (
            <div key={n.id} className="trow notif-grid" style={{ fontSize: 13 }}>
              <span className="mono">{fmtTime(n.createdAt)}</span>
              <span><span className="badge" style={{ background: '#EEF0F3', color: '#344054' }}>{n.channel}</span></span>
              <span className="ellipsis">{n.recipient}</span>
              <span style={{ color: '#344054' }}>{n.message}</span>
              <span><span className="badge" style={n.status === 'FAILED' ? { background: '#FEF3F2', color: '#B42318' } : { background: '#E5F5EC', color: '#067647' }}>{n.status === 'FAILED' ? 'Failed' : 'Sent'}</span></span>
            </div>
          ))}
          {notes.length === 0 && <p className="muted" style={{ padding: '20px 16px', textAlign: 'center' }}>No notifications yet. One is sent when a new time is accepted.</p>}
        </div>
      </section>
    </div>
  );
}

function WaitlistForm({ ctx, onDone }: { ctx: Ctx; onDone: () => void }) {
  const [name, setName] = useState('');
  const [contact, setContact] = useState('');
  const [type, setType] = useState<AppointmentType>('ROUTINE');
  const [specialty, setSpecialty] = useState<Specialty>('GENERAL');
  const [win, setWin] = useState<PreferredWindow>('ANY');
  const [err, setErr] = useState<{ name?: string; form?: string }>({});
  const [submitting, setSubmitting] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) { setErr({ name: 'Enter the patient’s name.' }); return; }
    setSubmitting(true);
    const r = await ctx.addWaitlist({ patientName: name.trim(), contact: contact.trim() || undefined, type, specialty, preferredWindow: win });
    setSubmitting(false);
    if (r) { setErr({ form: r }); return; }
    onDone();
  };

  return (
    <form className="form fadein" onSubmit={submit} noValidate>
      <h2>Add to waitlist</h2>
      <div className="fields-2">
        <div className="field">
          <label htmlFor="wl-name">Patient name</label>
          <input id="wl-name" value={name} onChange={(e) => setName(e.target.value)} aria-invalid={!!err.name} aria-describedby={err.name ? 'wl-name-err' : undefined} />
          {err.name && <span id="wl-name-err" className="ferr">{err.name}</span>}
        </div>
        <div className="field">
          <label htmlFor="wl-contact">Contact (phone or email)</label>
          <input id="wl-contact" value={contact} onChange={(e) => setContact(e.target.value)} />
        </div>
      </div>
      <div role="group" aria-label="Appointment type" className="field">
        <span className="flabel">Type</span>
        <div className="row-wrap">
          {(Object.keys(TYPE) as AppointmentType[]).map((k) => (
            <button key={k} type="button" className="pick" aria-pressed={type === k} onClick={() => setType(k)}>{TYPE[k].label}</button>
          ))}
        </div>
      </div>
      <div role="group" aria-label="Specialty" className="field">
        <span className="flabel">Specialty</span>
        <div className="row-wrap">
          {(Object.keys(SPEC) as Specialty[]).map((k) => (
            <button key={k} type="button" className="pick" aria-pressed={specialty === k} onClick={() => setSpecialty(k)}>{SPEC[k]}</button>
          ))}
        </div>
      </div>
      <div role="group" aria-label="Preferred window" className="field">
        <span className="flabel">Preferred window</span>
        <div className="row-wrap">
          {(['MORNING', 'AFTERNOON', 'ANY'] as PreferredWindow[]).map((k) => (
            <button key={k} type="button" className="pick" aria-pressed={win === k} onClick={() => setWin(k)}>{PREF_LABEL[k]}</button>
          ))}
        </div>
      </div>
      {err.form && <p role="alert" className="ferr" style={{ fontSize: 13 }}>{err.form}</p>}
      <div className="row-wrap" style={{ gap: 8 }}>
        <button type="submit" className="btn primary" disabled={submitting}>{submitting ? 'Adding…' : 'Add to waitlist'}</button>
        <button type="button" className="btn" onClick={onDone}>Cancel</button>
      </div>
    </form>
  );
}
