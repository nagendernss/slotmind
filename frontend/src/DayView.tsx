import { useEffect, useMemo, useState } from 'react';
import type { Ctx } from './ctx';
import type { ScheduleSlot } from './types';
import { addMinutes, astatOf, fmtTime, minutesOf, pstatOf, shortName, specOf, typeOf } from './format';

const LEGEND = [
  { label: 'Routine', bg: '#EAF0FD', bd: '1px solid #1D4ED855' },
  { label: 'Follow-up', bg: '#E3F4EF', bd: '1px solid #0B6E5A55' },
  { label: 'Urgent', bg: '#FDECEB', bd: '1px solid #B4231855' },
  { label: 'Being moved', bg: '#FEF0E1', bd: '1px dashed #DC6803' },
  { label: 'Open', bg: '#FFFFFF', bd: '1px dashed #47CD89' },
  { label: 'Held for a suggestion', bg: '#FFFAEB', bd: '1px dashed #DC6803' },
  { label: 'Cancelled', bg: 'repeating-linear-gradient(135deg,#F2F4F7 0 4px,#E4E7EC 4px 6px)', bd: '1px solid #D0D5DD' },
];

function slotLook(s: ScheduleSlot, providerOffline: boolean) {
  let bg = '#F2F4F7', fg = '#475467', bd = '1px solid #E4E7EC', label = 'Booked', deco = 'none', hatch = false;
  if (s.status === 'CANCELLED') {
    hatch = true; bd = '1px solid #D0D5DD'; label = 'Cancelled'; deco = 'line-through';
  } else if (s.status === 'OPEN') {
    if (s.heldBySuggestionId) { bg = '#FFFAEB'; fg = '#93370D'; bd = '1px dashed #DC6803'; label = 'Held'; }
    else if (providerOffline) { bg = '#F9FAFB'; fg = '#475467'; bd = '1px dashed #D0D5DD'; label = 'Unavailable'; }
    else { bg = '#FFFFFF'; fg = '#067647'; bd = '1px dashed #47CD89'; label = 'Open'; }
  } else if (s.appointment) {
    const a = s.appointment;
    const T = typeOf(a.type);
    if (a.status === 'COMPLETED') { bg = '#F2F4F7'; fg = '#475467'; bd = '1px solid #E4E7EC'; }
    else if (a.status === 'RESCHEDULE_PENDING') { bg = '#FEF0E1'; fg = '#93370D'; bd = '1px dashed #DC6803'; }
    else { bg = T.bg; fg = T.fg; bd = '1px solid ' + T.fg + '55'; }
    label = shortName(a.patientName);
  }
  return { bg, fg, bd, label, deco, hatch };
}

export function DayView({ ctx }: { ctx: Ctx }) {
  const rows = ctx.data.schedule?.providers ?? [];
  const [selId, setSelId] = useState<string | null>(null);
  const [nowMin, setNowMin] = useState(() => { const d = new Date(); return d.getHours() * 60 + d.getMinutes(); });
  useEffect(() => {
    const id = window.setInterval(() => { const d = new Date(); setNowMin(d.getHours() * 60 + d.getMinutes()); }, 30000);
    return () => window.clearInterval(id);
  }, []);

  const { start, end } = useMemo(() => {
    let lo = Infinity, hi = -Infinity;
    rows.forEach((r) => r.slots.forEach((s) => {
      const m = minutesOf(s.startTime);
      lo = Math.min(lo, m); hi = Math.max(hi, m + (s.durationMinutes || 30));
    }));
    if (!isFinite(lo)) { lo = 9 * 60; hi = 17 * 60; }
    let a = Math.floor(lo / 60) * 60, b = Math.ceil(hi / 60) * 60;
    if (b - a < 120) b = a + 120;
    return { start: a, end: b };
  }, [rows]);
  const span = end - start;
  const pct = (m: number) => ((m - start) / span) * 100;
  const hours: { left: number; label: string }[] = [];
  for (let m = start; m <= end; m += 60) {
    const h = Math.floor(m / 60) % 24;
    hours.push({ left: pct(m), label: ((h % 12) || 12) + (h >= 12 ? 'p' : 'a') });
  }
  const showNow = nowMin >= start && nowMin <= end;

  let sel: { slot: ScheduleSlot; providerName: string } | null = null;
  for (const r of rows) {
    const s = r.slots.find((x) => x.id === selId);
    if (s) { sel = { slot: s, providerName: r.provider.name }; break; }
  }

  return (
    <div className="stack">
      <div className="pagehead">
        <div>
          <h1>Day view</h1>
          <p>Every provider's slots for today. Select a slot to cancel it or get a new time for its patient.</p>
        </div>
        <ul aria-label="Legend" className="legend">
          {LEGEND.map((g) => <li key={g.label}><span className="sw" style={{ background: g.bg, border: g.bd }} />{g.label}</li>)}
        </ul>
      </div>

      <div className="day">
        <div className="day-inner">
          <div className="day-row" style={{ borderBottomColor: '#EEF0F3' }}>
            <div className="day-prov small" style={{ fontWeight: 600, color: '#475467', padding: '10px 16px' }}>Provider</div>
            <div className="day-axis">
              {hours.map((h) => <span key={h.left} className="tick-label mono" style={{ left: h.left + '%' }}>{h.label}</span>)}
            </div>
          </div>
          {rows.length === 0 && <p className="muted" style={{ padding: 24, textAlign: 'center' }}>No slots scheduled today.</p>}
          {rows.map((r) => {
            const S = pstatOf(r.provider.status);
            const off = r.provider.status === 'OFFLINE';
            return (
              <div key={r.provider.id} className={'day-row' + (off ? ' off' : '')}>
                <div className="day-prov">
                  <span style={{ fontWeight: 600, fontSize: 13 }}>{r.provider.name}</span>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, color: '#475467', flexWrap: 'wrap' }}>
                    <span className="dot" style={{ background: S.dot }} />
                    <span style={{ color: S.fg, fontWeight: 600 }}>{S.label}</span> · {specOf(r.provider.specialty)}
                  </span>
                  {r.provider.clinicName && <span className="small muted">{r.provider.clinicName}</span>}
                </div>
                <div className="day-track">
                  {hours.map((h) => <span key={h.left} aria-hidden="true" className="tick" style={{ left: h.left + '%' }} />)}
                  {showNow && <span aria-hidden="true" className="nowline" style={{ left: pct(nowMin) + '%' }} />}
                  {r.slots.map((s) => {
                    const L = slotLook(s, off);
                    const m = minutesOf(s.startTime);
                    const selected = selId === s.id;
                    return (
                      <button key={s.id} type="button" className={'slotbtn' + (L.hatch ? ' hatch' : '')} aria-pressed={selected}
                        aria-label={`${fmtTime(s.startTime)}, ${r.provider.name}, ${L.label}`}
                        onClick={() => setSelId(selected ? null : s.id)}
                        style={{
                          left: pct(m) + '%', width: `calc(${((s.durationMinutes || 30) / span) * 100}% - 3px)`,
                          background: L.hatch ? undefined : L.bg, border: L.bd, color: L.fg,
                        }}>
                        <span className="st mono">{fmtTime(s.startTime).replace(/ (AM|PM)$/, '')}</span>
                        <span className="sl" style={{ textDecoration: L.deco }}>{L.label}</span>
                      </button>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </div>
      </div>

      <div className="card" style={{ padding: '16px 20px' }}>
        {!sel ? (
          <p className="muted">Select a slot above to see its details. The red line marks the current time.</p>
        ) : (() => {
          const s = sel.slot;
          const a = s.appointment;
          const stMap: Record<string, [string, string, string]> = { OPEN: ['Open', '#067647', '#E5F5EC'], BOOKED: ['Booked', '#1D4ED8', '#EAF0FD'], CANCELLED: ['Cancelled', '#475467', '#F2F4F7'] };
          const m = stMap[s.status] ?? [s.status, '#475467', '#F2F4F7'];
          const who = a ? `${a.patientName} · ${typeOf(a.type).label} · ${astatOf(a.status).label}`
            : s.heldBySuggestionId ? 'Held by a pending suggestion' : 'No patient booked';
          const canSuggest = !!a && (a.status === 'CONFIRMED' || a.status === 'RESCHEDULED');
          const canCancel = s.status !== 'CANCELLED';
          return (
            <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '12px 24px' }}>
              <div style={{ flex: '1 1 320px', display: 'flex', flexDirection: 'column', gap: 4 }}>
                <div className="row-wrap">
                  <span className="mono small muted">{s.id}</span>
                  <span className="badge" style={{ background: m[2], color: m[1] }}>{m[0]}</span>
                  {s.roomName && <span className="small muted">{s.roomName}</span>}
                  {s.clinicName && <span className="small muted">· {s.clinicName}</span>}
                </div>
                <div style={{ fontSize: 17, fontWeight: 600 }}>{fmtTime(s.startTime)} – {addMinutes(s.startTime, s.durationMinutes)} · {sel.providerName}</div>
                <div className="muted">{who}</div>
              </div>
              <div className="row-wrap" style={{ gap: 8 }}>
                {canSuggest && a && (
                  <button type="button" className="btn" onClick={() => { ctx.suggestAnother(a.id); ctx.goTab('queue'); }}>Suggest new time</button>
                )}
                {a && <a className="btn ghost" href={`#/portal/${a.id}`}>Patient view</a>}
                {canCancel && (
                  <button type="button" className="btn danger" disabled={!!ctx.busy['s-' + s.id]} onClick={() => ctx.cancelSlot(s.id)}>Cancel slot</button>
                )}
                <button type="button" className="btn ghost" onClick={() => setSelId(null)}>Close</button>
              </div>
            </div>
          );
        })()}
      </div>
    </div>
  );
}
