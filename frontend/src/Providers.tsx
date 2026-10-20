import { useState, type FormEvent } from 'react';
import type { Ctx } from './ctx';
import type { PreferredWindow, ProviderStatus } from './types';
import { api } from './api';
import { ACCENT, PREF_LABEL, PSTAT, astatOf, fmtTime, initials, specOf, timeMs } from './format';

const STATUSES: ProviderStatus[] = ['AVAILABLE', 'BUSY', 'OFFLINE'];

export function ProvidersView({ ctx }: { ctx: Ctx }) {
  const { data } = ctx;
  const now = Date.now();
  return (
    <div className="stack">
      <div className="pagehead">
        <div>
          <h1>Providers</h1>
          <p>Status and workload for today. Setting a provider to Offline moves all of their confirmed patients into the re-schedule queue.</p>
        </div>
      </div>
      <div className="pgrid">
        {data.providers.map((p) => {
          const off = p.status === 'OFFLINE';
          const max = p.maxDailyAppointments || 0;
          const load = max ? Math.min(100, Math.round((p.bookedToday / max) * 100)) : 0;
          const row = data.schedule?.providers.find((r) => r.provider.id === p.id);
          const upcoming = (row?.slots ?? [])
            .filter((s) => timeMs(s.startTime) > now)
            .sort((a, b) => timeMs(a.startTime) - timeMs(b.startTime))
            .slice(0, 4);
          return (
            <section key={p.id} aria-label={p.name} className="pcard">
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <span className={'avatar lg' + (off ? ' off' : '')}>{initials(p.name)}</span>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontWeight: 600, fontSize: 15 }}>{p.name}</div>
                  <div className="small muted">{specOf(p.specialty)} · <span className="mono">{p.id}</span>{p.clinicName ? ` · ${p.clinicName}` : ''}</div>
                </div>
              </div>
              <div role="group" aria-label={`Status of ${p.name}`} className="seg fill">
                {STATUSES.map((k) => (
                  <button key={k} type="button" aria-pressed={p.status === k} disabled={!!ctx.busy['p-' + p.id]}
                    style={p.status === k ? { color: PSTAT[k].fg } : undefined}
                    onClick={() => p.status !== k && ctx.setProviderStatus(p.id, k)}>
                    {PSTAT[k].label}
                  </button>
                ))}
              </div>
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: '#475467' }}>
                  <span>Load today</span>
                  <span><strong style={{ color: '#101828' }}>{load}%</strong> · {p.bookedToday} of {max} appointments</span>
                </div>
                <div className="bar" style={{ height: 8 }} role="progressbar" aria-valuenow={p.bookedToday} aria-valuemin={0} aria-valuemax={max} aria-label="Load today">
                  <div style={{ width: load + '%', background: load >= 80 ? '#B42318' : load >= 50 ? '#B54708' : ACCENT }} />
                </div>
              </div>
              <ul className="upcoming">
                {upcoming.length === 0 && <li className="muted">No upcoming slots.</li>}
                {upcoming.map((s) => {
                  // A slot whose visit is COMPLETED is still BOOKED: show it as completed, never as "Open".
                  const a = s.status !== 'CANCELLED' ? s.appointment : null;
                  const tag: [string, string, string] = s.status === 'CANCELLED' ? ['Cancelled', '#475467', '#F2F4F7']
                    : a ? [astatOf(a.status).label, astatOf(a.status).fg, astatOf(a.status).bg]
                      : s.status === 'BOOKED' ? ['Booked', '#475467', '#F2F4F7']
                        : s.heldBySuggestionId ? ['Held', '#93370D', '#FFFAEB'] : ['Open', '#067647', '#E5F5EC'];
                  const who = a ? a.patientName : s.status === 'CANCELLED' ? 'Cancelled slot' : s.status === 'BOOKED' ? 'Booked slot' : 'Open slot';
                  return (
                    <li key={s.id}>
                      <span className="mono muted" style={{ width: 70 }}>{fmtTime(s.startTime)}</span>
                      <span className="ellipsis" style={{ flex: 1, minWidth: 0 }}>{who}</span>
                      <span className="tag" style={{ background: tag[2], color: tag[1] }}>{tag[0]}</span>
                    </li>
                  );
                })}
              </ul>
            </section>
          );
        })}
      </div>
      <PreferenceEditor ctx={ctx} />
    </div>
  );
}

function PreferenceEditor({ ctx }: { ctx: Ctx }) {
  const [name, setName] = useState('');
  const [current, setCurrent] = useState<string | null>(null);
  const [win, setWin] = useState<PreferredWindow>('MORNING');
  const [err, setErr] = useState<string | null>(null);

  const lookup = async () => {
    if (!name.trim()) { setErr('Enter a patient name.'); return; }
    setErr(null);
    try {
      const p = await api.getPreference(name.trim());
      setCurrent(`${PREF_LABEL[p.preferredWindow] ?? p.preferredWindow}${p.source ? ` (${p.source.toLowerCase()})` : ''}`);
      if (p.preferredWindow) setWin(p.preferredWindow);
    } catch (e) { setCurrent(null); setErr(e instanceof Error ? e.message : 'Not found'); }
  };
  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (!name.trim()) { setErr('Enter a patient name.'); return; }
    try {
      await api.setPreference(name.trim(), win);
      setErr(null);
      setCurrent(`${PREF_LABEL[win]} (explicit)`);
      ctx.toast(`Saved: ${name.trim()} prefers ${PREF_LABEL[win].toLowerCase()}`);
    } catch (e2) { setErr(e2 instanceof Error ? e2.message : 'Could not save'); }
  };

  return (
    <form className="form" onSubmit={save} noValidate>
      <h2>Patient time preference</h2>
      <p className="small muted">Used by every strategy as a tie-break, and passed to the AI advisor.</p>
      <div className="field">
        <label htmlFor="pref-name">Patient name</label>
        <input id="pref-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Emily Watson" />
        {current && <span className="small muted">Current: {current}</span>}
      </div>
      <div role="group" aria-label="Preferred window" className="row-wrap">
        {(['MORNING', 'AFTERNOON', 'ANY'] as PreferredWindow[]).map((w) => (
          <button key={w} type="button" className="pick" aria-pressed={win === w} onClick={() => setWin(w)}>{PREF_LABEL[w]}</button>
        ))}
      </div>
      {err && <p role="alert" className="ferr">{err}</p>}
      <div className="row-wrap" style={{ gap: 8 }}>
        <button type="button" className="btn" onClick={lookup}>Look up</button>
        <button type="submit" className="btn primary">Save preference</button>
      </div>
    </form>
  );
}
