import { useEffect, useState, type FormEvent } from 'react';
import type { Ctx } from './ctx';
import type { AppointmentStatus, AppointmentType, AppointmentView, SlotView } from './types';
import { api } from './api';
import { TYPE, astatOf, fmtTime, timeMs, typeOf } from './format';
import { Plus } from './icons';

type Filter = 'ALL' | AppointmentStatus;
const FDEFS: [Filter, string][] = [['ALL', 'All'], ['CONFIRMED', 'Confirmed'], ['RESCHEDULE_PENDING', 'Reschedule pending'], ['RESCHEDULED', 'Rescheduled'], ['COMPLETED', 'Completed']];

export function AppointmentsView({ ctx }: { ctx: Ctx }) {
  const { data } = ctx;
  const [filter, setFilter] = useState<Filter>('ALL');
  const [rows, setRows] = useState<AppointmentView[] | null>(null);
  const [showForm, setShowForm] = useState(false);

  // Server-side filter via GET /appointments?status=, re-run whenever the poll refreshes.
  useEffect(() => {
    let alive = true;
    if (filter === 'ALL') { setRows(null); return; }
    api.listAppointments(filter).then((r) => { if (alive) setRows(r ?? []); }).catch(() => {
      if (alive) setRows(data.appointments.filter((a) => a.status === filter));
    });
    return () => { alive = false; };
  }, [filter, data.appointments]);

  const list = (filter === 'ALL' ? data.appointments : rows ?? data.appointments.filter((a) => a.status === filter))
    .slice().sort((x, y) => timeMs(x.slot?.startTime) - timeMs(y.slot?.startTime));

  return (
    <div className="stack">
      <div className="pagehead">
        <div>
          <h1>Appointments</h1>
          <p>Today's bookings across CareBridge clinics.</p>
        </div>
        <button type="button" className="btn primary" aria-expanded={showForm} onClick={() => setShowForm((v) => !v)}>
          <Plus /> {showForm ? 'Close form' : 'Book appointment'}
        </button>
      </div>

      {showForm && <BookingForm ctx={ctx} onDone={() => setShowForm(false)} />}

      <div role="group" aria-label="Filter by status" className="row-wrap">
        {FDEFS.map(([id, label]) => (
          <button key={id} type="button" className="chip" aria-pressed={filter === id} onClick={() => setFilter(id)}>
            {label} · {id === 'ALL' ? data.appointments.length : data.appointments.filter((a) => a.status === id).length}
          </button>
        ))}
      </div>

      <div className="table">
        <div className="trow head appt-grid">
          <span>Patient</span><span>Type</span><span>Provider</span><span>Time</span><span>Status</span><span style={{ textAlign: 'right' }}>Action</span>
        </div>
        {list.map((a) => {
          const T = typeOf(a.type), A = astatOf(a.status);
          const canSuggest = a.status === 'CONFIRMED' || a.status === 'RESCHEDULED';
          const canComplete = a.status === 'CONFIRMED' || a.status === 'RESCHEDULED';
          return (
            <div key={a.id} className="trow appt-grid">
              <span style={{ display: 'flex', flexDirection: 'column' }}>
                <span style={{ fontWeight: 600 }}>{a.patientName}</span>
                <span className="mono" style={{ fontSize: 11, color: '#475467' }}>{a.id}{a.contact ? ' · ' + a.contact : ''}</span>
              </span>
              <span><span className="badge" style={{ background: T.bg, color: T.fg }}>{T.label}</span></span>
              <span style={{ color: '#344054' }}>{a.providerName}{a.slot?.clinicName ? <span className="small muted"> · {a.slot.clinicName}</span> : null}</span>
              <span className="mono" style={{ fontSize: 13 }}>{fmtTime(a.slot?.startTime)}</span>
              <span><span className="badge" style={{ background: A.bg, color: A.fg }}>{A.label}</span></span>
              <span className="cell-actions">
                {canSuggest && (
                  <button type="button" className="btn sm" onClick={() => { ctx.suggestAnother(a.id); ctx.goTab('queue'); }}>Suggest new time</button>
                )}
                {canComplete && (
                  <button type="button" className="btn sm" disabled={!!ctx.busy['c-' + a.id]} onClick={() => ctx.complete(a.id)}>Mark completed</button>
                )}
                {a.status === 'RESCHEDULE_PENDING' && (
                  <button type="button" className="btn sm link" onClick={() => ctx.goTab('queue')}>Open in queue</button>
                )}
                <a className="btn sm link" href={`#/portal/${a.id}`}>Patient view</a>
              </span>
            </div>
          );
        })}
        {list.length === 0 && <p className="muted" style={{ padding: '24px 16px', textAlign: 'center' }}>No appointments with this status.</p>}
      </div>
    </div>
  );
}

function BookingForm({ ctx, onDone }: { ctx: Ctx; onDone: () => void }) {
  const providers = ctx.data.providers;
  const firstOnline = providers.find((p) => p.status !== 'OFFLINE')?.id ?? '';
  const [name, setName] = useState('');
  const [contact, setContact] = useState('');
  const [type, setType] = useState<AppointmentType>('ROUTINE');
  const [providerId, setProviderId] = useState(firstOnline);
  const [slotId, setSlotId] = useState('');
  const [slots, setSlots] = useState<SlotView[] | null>(null);
  const [errors, setErrors] = useState<{ name?: string; provider?: string; slot?: string; form?: string }>({});
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!providerId) { setSlots([]); return; }
    let alive = true;
    api.listSlots(providerId, 'OPEN').then((s) => {
      if (!alive) return;
      const now = Date.now();
      setSlots((s ?? []).filter((x) => x.status === 'OPEN' && timeMs(x.startTime) > now).sort((a, b) => timeMs(a.startTime) - timeMs(b.startTime)));
    }).catch(() => { if (alive) setSlots([]); });
    return () => { alive = false; };
  }, [providerId, ctx.data.schedule]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const errs: typeof errors = {};
    if (!name.trim()) errs.name = 'Enter the patient’s name.';
    if (!providerId) errs.provider = 'Choose a provider.';
    if (!slotId) errs.slot = 'Choose an open slot.';
    setErrors(errs);
    if (Object.keys(errs).length) return;
    setSubmitting(true);
    const err = await ctx.book({ patientName: name.trim(), contact: contact.trim() || undefined, type, providerId, slotId });
    setSubmitting(false);
    if (err) { setErrors({ form: err }); setSlotId(''); return; }
    onDone();
  };

  return (
    <form onSubmit={submit} className="form fadein" noValidate>
      <h2>Book an appointment</h2>
      <div className="fields-2">
        <div className="field">
          <label htmlFor="pt-name">Patient name</label>
          <input id="pt-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="First and last name"
            aria-invalid={!!errors.name} aria-describedby={errors.name ? 'pt-name-err' : undefined} />
          {errors.name && <span id="pt-name-err" className="ferr">{errors.name}</span>}
        </div>
        <div className="field">
          <label htmlFor="pt-contact">Contact (phone or email)</label>
          <input id="pt-contact" value={contact} onChange={(e) => setContact(e.target.value)} placeholder="+1 555 0100 or name@example.com" />
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
      <div role="group" aria-label="Provider" className="field">
        <span className="flabel">Provider</span>
        <div className="row-wrap">
          {providers.map((p) => {
            const off = p.status === 'OFFLINE';
            return (
              <button key={p.id} type="button" className="pick" aria-pressed={providerId === p.id} disabled={off}
                onClick={() => { if (p.id !== providerId) { setProviderId(p.id); setSlotId(''); setSlots(null); } }}>
                {p.name}{off ? ' (offline)' : ''}
              </button>
            );
          })}
        </div>
        {errors.provider && <span className="ferr">{errors.provider}</span>}
      </div>
      <div role="group" aria-label="Open slot" className="field">
        <span className="flabel">Open slot</span>
        <div className="row-wrap">
          {slots === null && <><div className="sk" style={{ height: 44, width: 120 }} /><div className="sk" style={{ height: 44, width: 120 }} /></>}
          {slots?.map((s) => (
            <button key={s.id} type="button" className="pick mono" aria-pressed={slotId === s.id} style={{ fontWeight: 500 }}
              onClick={() => { setSlotId(s.id); setErrors((e) => ({ ...e, slot: undefined, form: undefined })); }}>
              {fmtTime(s.startTime)} · {s.durationMinutes}m{s.roomName ? ` · ${s.roomName}` : ''}
            </button>
          ))}
          {slots?.length === 0 && <span className="muted" style={{ fontSize: 13, padding: '10px 0' }}>No open slots left today for this provider.</span>}
        </div>
        {errors.slot && <span className="ferr">{errors.slot}</span>}
      </div>
      {errors.form && <p role="alert" className="ferr" style={{ fontSize: 13 }}>{errors.form}</p>}
      <div className="row-wrap" style={{ gap: 8 }}>
        <button type="submit" className="btn primary" disabled={submitting}>{submitting ? 'Booking…' : 'Book appointment'}</button>
        <button type="button" className="btn" onClick={onDone}>Cancel</button>
      </div>
    </form>
  );
}
