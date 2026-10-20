import { useCallback, useEffect, useState } from 'react';
import { api, ApiError } from './api';
import type { PortalView } from './types';
import { addMinutes, astatOf, fmtTime, parseLocal, typeOf } from './format';
import { Check, Logo, Speaker, Warn } from './icons';

export function Portal({ id }: { id: string }) {
  const [view, setView] = useState<PortalView | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState<{ text: string; kind: 'ok' | 'error' } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try { setView(await api.portal(id)); setErr(null); } catch (e) {
      setErr(e instanceof ApiError && e.status === 404 ? 'We couldn’t find this appointment.' : 'We can’t reach the clinic right now. Please try again shortly.');
    }
  }, [id]);

  useEffect(() => {
    load();
    const t = window.setInterval(load, 5000);
    return () => window.clearInterval(t);
  }, [load]);

  const act = async (fn: () => Promise<unknown>, ok: string) => {
    setBusy(true); setMsg(null);
    try { await fn(); setMsg({ text: ok, kind: 'ok' }); } catch (e) {
      const slot = e instanceof ApiError && e.isSlotUnavailable;
      setMsg({ text: slot ? 'Sorry, that time was just taken. We’ll find you another one.' : (e instanceof Error ? e.message : 'Something went wrong'), kind: 'error' });
    }
    setBusy(false);
    await load();
  };

  const a = view?.appointment;
  const pending = view?.suggestions.find((s) => s.status === 'PENDING');
  const day = a?.slot ? parseLocal(a.slot.startTime)?.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' }) : '';

  return (
    <div style={{ minHeight: '100vh' }}>
      <header className="header">
        <div className="header-inner" style={{ maxWidth: 560 }}>
          <div className="brand">
            <div className="brand-mark"><Logo /></div>
            <div style={{ display: 'flex', flexDirection: 'column' }}>
              <span style={{ fontWeight: 700, fontSize: 16 }}>CareBridge</span>
              <span className="small muted">Your appointment</span>
            </div>
          </div>
          <a href="#/" className="small" style={{ marginLeft: 'auto' }}>Front desk</a>
        </div>
      </header>
      <div className="portal">
        {err && <div className="alert" role="alert"><Warn size={18} color="#D92D20" /><span>{err}</span></div>}
        {!view && !err && <><div className="sk" style={{ height: 160 }} /><div className="sk" style={{ height: 200 }} /></>}
        {a && (
          <section className="card" aria-labelledby="appt-h">
            <div className="row-wrap">
              <h1 id="appt-h" style={{ fontSize: 20, fontWeight: 700, flex: 1 }}>Hi {a.patientName.split(' ')[0]}</h1>
              <span className="badge" style={{ background: astatOf(a.status).bg, color: astatOf(a.status).fg }}>{astatOf(a.status).label}</span>
            </div>
            <div>
              <div className="small muted">{day}</div>
              <div style={{ fontSize: 24, fontWeight: 700, textDecoration: pending ? 'line-through' : 'none', color: pending ? '#475467' : undefined }}>
                {fmtTime(a.slot?.startTime)}{a.slot ? ` – ${addMinutes(a.slot.startTime, a.slot.durationMinutes)}` : ''}
              </div>
              <div style={{ color: '#344054' }}>{a.providerName}{a.slot?.clinicName ? ` · ${a.slot.clinicName}` : ''}{a.slot?.roomName ? ` · ${a.slot.roomName}` : ''}</div>
            </div>
            <div className="row-wrap">
              <span className="badge" style={{ background: typeOf(a.type).bg, color: typeOf(a.type).fg }}>{typeOf(a.type).label}</span>
              <span className="mono small muted">{a.id}</span>
            </div>
          </section>
        )}

        {a && pending && pending.recommendedSlot && (
          <section className="card fadein" aria-labelledby="new-h" style={{ borderColor: '#C9D7FB' }}>
            <h2 id="new-h" style={{ fontSize: 16, fontWeight: 600 }}>We have a new time for you</h2>
            <div className="box to" style={{ flex: 'none' }}>
              <div className="lbl">Proposed</div>
              <div className="big">{fmtTime(pending.recommendedSlot.startTime)} <span style={{ fontSize: 13, fontWeight: 500, color: '#475467' }}>· {pending.recommendedSlot.durationMinutes} min</span></div>
              <div style={{ fontSize: 13, color: '#344054' }}>
                {pending.recommendedSlot.providerName}
                {pending.recommendedSlot.clinicName ? ` · ${pending.recommendedSlot.clinicName}` : ''}
              </div>
            </div>
            <div className="read">
              <div className="lbl"><Speaker /><span>Why this time</span></div>
              <p style={{ fontSize: 15 }}>{pending.reasoning}</p>
            </div>
            <button type="button" className="btn primary" disabled={busy}
              onClick={() => act(() => api.resolveSuggestion(pending.id, 'ACCEPTED', 'PATIENT'), 'Done — your appointment has been moved. A confirmation is on its way.')}>
              <Check /> Accept this time
            </button>
            <button type="button" className="btn" disabled={busy}
              onClick={() => act(() => api.resolveSuggestion(pending.id, 'REJECTED', 'PATIENT'), 'Thanks — the front desk will contact you with another option.')}>
              Not suitable
            </button>
          </section>
        )}

        {a && pending && !pending.recommendedSlot && (
          <section className="card"><div className="warnbox"><span>{pending.reasoning || 'We’re looking for a new time and will contact you shortly.'}</span></div></section>
        )}

        {a && !pending && a.status === 'RESCHEDULE_PENDING' && (
          <section className="card" aria-busy="true">
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#475467' }}>
              <span className="pulse" style={{ width: 8, height: 8, borderRadius: '50%', background: '#1D4ED8' }} />
              <span>We’re finding a new time for you…</span>
            </div>
            <div className="sk" style={{ height: 14, width: '80%' }} />
          </section>
        )}

        {a && !pending && (a.status === 'CONFIRMED' || a.status === 'RESCHEDULED') && (
          <section className="card">
            <h2 style={{ fontSize: 16, fontWeight: 600 }}>Need a different time?</h2>
            <p className="muted">We’ll suggest the next suitable opening. Nothing changes until you accept it.</p>
            <button type="button" className="btn primary" disabled={busy}
              onClick={() => act(() => api.portalReschedule(id), 'Request sent — your new option will appear here in a moment.')}>
              Request a different time
            </button>
          </section>
        )}

        {msg && (
          <div role="status" className={msg.kind === 'error' ? 'alert' : 'note'} style={msg.kind === 'ok' ? { borderColor: '#ABEFC6', background: '#ECFDF3', color: '#067647' } : undefined}>
            <span>{msg.text}</span>
          </div>
        )}
      </div>
    </div>
  );
}
