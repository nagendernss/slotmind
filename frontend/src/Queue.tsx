import { useMemo, useState } from 'react';
import type { Ctx, StreamState } from './ctx';
import type { AppointmentView, SuggestionView, TriggerReason } from './types';
import { ACCENT, ACTIVITY_DOT, fmtTime, initials, pstatOf, specOf, timeMs, trigOf, typeOf, STRATEGY_LABEL } from './format';
import { Arrow, Check, Info, Refresh, Sparkle, Speaker, Warn } from './icons';

const RANK: Record<string, number> = { URGENT: 0, FOLLOW_UP: 1, ROUTINE: 2 };

// Sub-millisecond digits of the timestamp (LocalDateTime carries microseconds/nanoseconds).
const subMs = (iso?: string | null) => {
  const m = /T\d{2}:\d{2}(?::\d{2})?\.(\d+)/.exec(iso ?? '');
  return m ? m[1].padEnd(9, '0').slice(3, 9) : '000000';
};
const idNum = (id: string) => { const m = /(\d+)$/.exec(id); return m ? +m[1] : -1; };

/** True if a was created after b: timestamp (full precision), then the id sequence as a final tie-break. */
function isNewer(a: SuggestionView, b: SuggestionView): boolean {
  const d = timeMs(a.createdAt) - timeMs(b.createdAt);
  if (d !== 0) return d > 0;
  const fa = subMs(a.createdAt), fb = subMs(b.createdAt);
  if (fa !== fb) return fa > fb;
  return idNum(a.id) > idNum(b.id);
}

export function latestSuggestionByAppt(suggestions: SuggestionView[]): Record<string, SuggestionView> {
  const latest: Record<string, SuggestionView> = {};
  for (const s of suggestions) {
    if (s.status === 'SUPERSEDED') continue;
    const cur = latest[s.appointmentId];
    if (!cur || isNewer(s, cur)) latest[s.appointmentId] = s;
  }
  return latest;
}

interface Card {
  appt: AppointmentView;
  sug?: SuggestionView;
  stream?: StreamState;
  trigger: TriggerReason;
}

export function QueueView({ ctx }: { ctx: Ctx }) {
  const { data, streams } = ctx;
  const [filter, setFilter] = useState<'ALL' | TriggerReason>('ALL');

  const cards: Card[] = useMemo(() => {
    const latest = latestSuggestionByAppt(data.suggestions);
    const list = data.appointments.filter((a) => a.status === 'RESCHEDULE_PENDING' || streams[a.id]);
    return list.map((appt) => {
      const sug = latest[appt.id];
      const stream = streams[appt.id];
      const trigger = stream?.suggestion?.triggerReason ?? sug?.triggerReason ?? 'INITIAL';
      return { appt, sug, stream, trigger: trigger as TriggerReason };
    }).sort((x, y) =>
      (RANK[x.appt.type] ?? 9) - (RANK[y.appt.type] ?? 9)
      || timeMs(x.sug?.originalSlot?.startTime ?? x.appt.slot?.startTime) - timeMs(y.sug?.originalSlot?.startTime ?? y.appt.slot?.startTime));
  }, [data.appointments, data.suggestions, streams]);

  const shown = filter === 'ALL' ? cards : cards.filter((c) => c.trigger === filter);
  const fdefs: [typeof filter, string][] = [['ALL', 'All'], ['PROVIDER_OFFLINE', 'Provider offline'], ['SLOT_CANCELLED', 'Slot cancelled'], ['INITIAL', 'Manual']];

  const history = data.suggestions
    .filter((s) => s.status === 'ACCEPTED' || s.status === 'REJECTED')
    .sort((a, b) => timeMs(b.resolvedAt ?? b.createdAt) - timeMs(a.resolvedAt ?? a.createdAt))
    .slice(0, 8);

  const strategy = data.strategy.strategy;
  const strategyNote = strategy === 'AI'
    ? 'AI advisor picks times based on urgency, keeping the same doctor where possible, and writes an explanation you can read to the patient. If the model fails, the rule-based strategy takes over.'
    : strategy === 'ROOM_CONSTRAINT'
      ? 'Room constraint: same provider first, then the same room, then the best-fit room for the visit’s equipment, earliest first.'
      : 'Rule-based: the same provider’s nearest open slot first, otherwise the nearest slot with a matching specialty.';

  const demoProvider = data.providers.find((p) => /sarah chen/i.test(p.name)) ?? data.providers.find((p) => p.id === 'PRV-001');

  return (
    <div className="cols">
      <section aria-labelledby="q-h" className="col-main">
        <div className="pagehead">
          <div>
            <h1 id="q-h">Re-schedule queue</h1>
            <p>Suggestions appear here on their own when a provider goes offline or a slot is cancelled. Call the patient first, then accept.</p>
          </div>
          <div role="group" aria-label="Filter by trigger" className="row-wrap">
            {fdefs.map(([id, label]) => (
              <button key={id} type="button" className="chip" aria-pressed={filter === id} onClick={() => setFilter(id)}>
                {label} · {id === 'ALL' ? cards.length : cards.filter((c) => c.trigger === id).length}
              </button>
            ))}
          </div>
        </div>

        <div className="note">
          <Sparkle size={16} color={ACCENT} />
          <span>{strategyNote}</span>
        </div>

        {shown.length === 0 && (
          <div className="empty">
            <div className="ic"><Check size={24} color="#067647" sw={2} /></div>
            <h2 style={{ fontSize: 17, fontWeight: 600 }}>{cards.length === 0 ? 'No patients need a new time' : 'Nothing matches this filter'}</h2>
            <p className="muted" style={{ maxWidth: 460 }}>When a provider goes offline or a slot is cancelled, SlotMind finds every affected appointment and drafts a new time for each one here.</p>
            {cards.length === 0 && demoProvider && demoProvider.status !== 'OFFLINE' && (
              <button type="button" className="btn primary" style={{ marginTop: 6 }} disabled={!!ctx.busy['p-' + demoProvider.id]}
                onClick={() => ctx.setProviderStatus(demoProvider.id, 'OFFLINE')}>
                Try it: mark {demoProvider.name} offline
              </button>
            )}
          </div>
        )}

        {shown.map((c) => <QueueCard key={c.appt.id} ctx={ctx} card={c} />)}

        {history.length > 0 && (
          <div style={{ marginTop: 8 }}>
            <h2 style={{ margin: '0 0 10px', fontSize: 14, fontWeight: 600, color: '#344054' }}>Resolved today</h2>
            <div className="card">
              {history.map((s) => {
                const ok = s.status === 'ACCEPTED';
                const G = trigOf(s.triggerReason);
                const where = s.recommendedSlot ? `${fmtTime(s.recommendedSlot.startTime)} with ${s.recommendedSlot.providerName}` : '';
                return (
                  <div key={s.id} className="hist-row">
                    <span className="badge" style={{ background: ok ? '#E5F5EC' : '#F2F4F7', color: ok ? '#067647' : '#475467' }}>{ok ? 'Accepted' : 'Rejected'}</span>
                    <span style={{ fontWeight: 600 }}>{s.patientName}</span>
                    <span className="muted" style={{ fontSize: 13, flex: '1 1 260px' }}>{ok ? `Moved to ${where}` : `Declined ${where || 'suggestion'}`}</span>
                    <span className="badge" style={{ fontWeight: 500, background: G.bg, color: G.fg }}>{G.label}</span>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </section>
      <Rail ctx={ctx} />
    </div>
  );
}

function QueueCard({ ctx, card }: { ctx: Ctx; card: Card }) {
  const { appt, sug, stream, trigger } = card;
  const T = typeOf(appt.type);
  const G = trigOf(trigger);
  const strategy = ctx.data.strategy.strategy;

  type St = 'streaming' | 'generating' | 'ready' | 'nooption' | 'rejected';
  let st: St;
  if (stream) st = 'streaming';
  else if (!sug || sug.status === 'ACCEPTED') st = 'generating';
  else if (sug.status === 'REJECTED') st = 'rejected';
  else if (!sug.recommendedSlot) st = 'nooption';
  else st = 'ready';

  const shownSug = stream?.suggestion ?? (st === 'streaming' ? undefined : sug);
  const srcStrategy = shownSug?.strategy ?? strategy;
  const fallback = !!shownSug?.fallbackUsed;
  const ruleish = fallback || srcStrategy !== 'AI';
  const sourceLabel = fallback ? 'Rule-based fallback · AI unavailable' : (STRATEGY_LABEL[srcStrategy] ?? srcStrategy);
  const sourceFg = fallback ? '#B54708' : ruleish ? '#475467' : ACCENT;

  const conflict = sug ? ctx.conflicts[sug.id] : undefined;
  const busy = sug ? !!ctx.busy[sug.id] : false;
  const orig = shownSug?.originalSlot ?? sug?.originalSlot ?? appt.slot;
  const rec = shownSug?.recommendedSlot ?? null;
  const conf = shownSug?.confidence != null ? Math.round(shownSug.confidence * 100) : 0;
  const confColor = conf >= 80 ? ACCENT : conf >= 65 ? '#B54708' : '#B42318';
  const confLabel = conf >= 80 ? 'High' : conf >= 65 ? 'Medium' : 'Low';
  const isAlt = !!(rec && rec.providerId !== appt.providerId);
  const reasoning = st === 'streaming' ? stream!.text : sug?.reasoning ?? '';
  const isStreamingText = st === 'streaming' && !stream?.suggestion;

  return (
    <article className={'qcard fadein' + (conflict ? ' conflict' : st === 'generating' ? ' gen' : '')} aria-label={appt.patientName}>
      <div className="qhead">
        <h2>{appt.patientName}</h2>
        <span className="mono small muted">{appt.id}</span>
        <span className="badge" style={{ background: T.bg, color: T.fg }}>{T.label}</span>
        <span className="badge" style={{ background: G.bg, color: G.fg }}><span className="d" />{G.label}</span>
        {shownSug?.crossClinic && <span className="badge" style={{ background: '#F2EEFC', color: '#5925DC' }}>Other clinic</span>}
        <span className="source" style={{ color: sourceFg }}>
          {fallback ? <Warn /> : !ruleish ? <Sparkle /> : null}
          {sourceLabel}
        </span>
        <a className="small" href={`#/portal/${appt.id}`} style={{ marginLeft: 4 }}>Patient view</a>
      </div>

      {st === 'generating' && (
        <div className="qbody" aria-busy="true">
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#475467', fontSize: 13 }}>
            <span className="pulse" style={{ width: 8, height: 8, borderRadius: '50%', background: ACCENT }} />
            <span>{strategy === 'AI' ? 'AI advisor is checking open slots and the provider roster…' : 'Finding the nearest matching open slot…'}</span>
          </div>
          <div className="boxes">
            <div className="sk" style={{ height: 64, flex: '1 1 180px' }} />
            <div className="sk" style={{ height: 64, flex: '1 1 180px' }} />
            <div className="sk" style={{ height: 64, flex: '1 1 140px' }} />
          </div>
          <div className="sk" style={{ height: 14, width: '90%' }} />
          <div className="sk" style={{ height: 14, width: '70%' }} />
        </div>
      )}

      {(st === 'ready' || st === 'streaming') && (
        <div className="qbody">
          <div className="boxes">
            <div className="box was">
              <div className="lbl">Was</div>
              <div className="big">{fmtTime(orig?.startTime)}</div>
              <div style={{ fontSize: 13, color: '#475467' }}>{orig?.providerName ?? appt.providerName}</div>
            </div>
            <div className="arrow" aria-hidden="true"><Arrow /></div>
            {rec ? (
              <div className={'box to' + (conflict ? ' conflict' : '')}>
                <div className="lbl">Suggested · today</div>
                <div className="big">{fmtTime(rec.startTime)} <span style={{ fontSize: 13, fontWeight: 500, color: '#475467' }}>· {rec.durationMinutes} min</span></div>
                <div className="row-wrap" style={{ fontSize: 13, color: '#344054' }}>
                  <span>{rec.providerName}</span>
                  {isAlt && <span className="tag" style={{ background: '#FEF0E1', color: '#93370D' }}>Different doctor</span>}
                  {rec.clinicName && shownSug?.crossClinic && <span className="tag" style={{ background: '#F2EEFC', color: '#5925DC' }}>{rec.clinicName}</span>}
                  {rec.roomName && <span className="small muted">· {rec.roomName}</span>}
                </div>
              </div>
            ) : (
              <div className="sk" style={{ flex: '1 1 200px', minHeight: 72 }} />
            )}
            {shownSug ? (
              <div className="box conf">
                <div className="lbl" style={{ display: 'flex', justifyContent: 'space-between' }}><span>{ruleish ? 'Rule score' : 'Confidence'}</span><span style={{ color: confColor }}>{confLabel}</span></div>
                <div className="big mono">{conf}%</div>
                <div className="bar"><div style={{ width: conf + '%', background: confColor }} /></div>
              </div>
            ) : (
              <div className="sk" style={{ flex: '1 1 150px', minHeight: 72 }} />
            )}
          </div>

          <div className={'read' + (ruleish && shownSug ? ' tmpl' : '')}>
            <div className="lbl">
              <Speaker />
              <span>{ruleish && shownSug ? 'Template text · rephrase for the patient' : 'Read to patient'}</span>
              {isStreamingText && <span style={{ marginLeft: 'auto', textTransform: 'none', letterSpacing: 0, fontWeight: 500, color: '#475467' }}>{stream?.text ? 'Streaming reasoning…' : (stream?.status || 'Streaming reasoning…')}</span>}
            </div>
            <p aria-live="polite">{reasoning}{isStreamingText && <span className="caret" aria-hidden="true" />}</p>
          </div>

          {conflict && (
            <div className="alert" role="alert">
              <Warn size={18} color="#D92D20" />
              <span><strong>{conflict}</strong>. Nothing was changed — use “Suggest another” for a fresh option.</span>
            </div>
          )}

          <div className="actions">
            <span className="hint">Accepting moves the appointment, frees the old slot and books the new one in a single step.</span>
            <button type="button" className="btn" disabled={st !== 'ready' || busy} onClick={() => sug && ctx.reject(sug)}>Reject</button>
            <button type="button" className="btn" disabled={st === 'streaming' || busy} onClick={() => ctx.suggestAnother(appt.id)}>
              <Refresh /> Suggest another
            </button>
            <button type="button" className="btn primary" disabled={st !== 'ready' || busy || !!conflict} onClick={() => sug && ctx.accept(sug)}>
              <Check /> Patient agreed · Accept
            </button>
          </div>
        </div>
      )}

      {st === 'nooption' && (
        <div className="qbody">
          <div className="warnbox">
            <Info color="#DC6803" />
            <div>
              <div style={{ fontWeight: 600 }}>No open slot fits today</div>
              <div style={{ fontSize: 15, marginTop: 4, lineHeight: 1.55 }}>{sug?.reasoning}</div>
            </div>
          </div>
          <div className="actions" style={{ justifyContent: 'flex-end' }}>
            <button type="button" className="btn" onClick={() => ctx.suggestAnother(appt.id)}>Try again</button>
            <button type="button" className="btn primary" disabled={!!ctx.busy['wl-' + appt.id]} onClick={() => ctx.addApptToWaitlist(appt)}>Add to waitlist</button>
          </div>
        </div>
      )}

      {st === 'rejected' && (
        <div className="qbody" style={{ flexDirection: 'row', flexWrap: 'wrap', alignItems: 'center', gap: 12 }}>
          <div style={{ flex: '1 1 280px' }}>
            <div style={{ fontWeight: 600 }}>Still needs a new time</div>
            <div className="muted" style={{ fontSize: 13, marginTop: 2 }}>
              You rejected {sug?.recommendedSlot ? `${fmtTime(sug.recommendedSlot.startTime)} with ${sug.recommendedSlot.providerName}` : 'the last suggestion'}. The patient keeps their place in this queue.
            </div>
          </div>
          <button type="button" className="btn" disabled={!!ctx.busy['wl-' + appt.id]} onClick={() => ctx.addApptToWaitlist(appt)}>Add to waitlist</button>
          <button type="button" className="btn primary" onClick={() => ctx.suggestAnother(appt.id)}>Suggest another time</button>
        </div>
      )}
    </article>
  );
}

function Rail({ ctx }: { ctx: Ctx }) {
  const { data } = ctx;
  return (
    <aside className="col-rail">
      <section aria-labelledby="rail-p" className="card">
        <div className="card-head">
          <h2 id="rail-p">Providers today</h2>
          <p className="small muted" style={{ marginTop: 2 }}>Marking a provider offline queues a suggestion for each of their confirmed patients.</p>
        </div>
        {data.providers.map((p) => {
          const S = pstatOf(p.status);
          const off = p.status === 'OFFLINE';
          const n = data.appointments.filter((a) => a.providerId === p.id && (a.status === 'CONFIRMED' || a.status === 'RESCHEDULED')).length;
          return (
            <div key={p.id} className="prow">
              <span className={'avatar' + (off ? ' off' : '')}>{initials(p.name)}</span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="ellipsis" style={{ fontWeight: 600, fontSize: 13 }}>{p.name}</div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: '#475467', flexWrap: 'wrap' }}>
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: S.fg, fontWeight: 600 }}><span className="dot" style={{ background: S.dot }} />{S.label}</span>
                  <span>· {specOf(p.specialty)} · {n} {n === 1 ? 'appt' : 'appts'}</span>
                </div>
              </div>
              <button type="button" className="btn sm" style={{ color: off ? '#067647' : '#B54708' }} disabled={!!ctx.busy['p-' + p.id]}
                aria-label={off ? `Mark ${p.name} available` : `Mark ${p.name} offline`}
                onClick={() => ctx.setProviderStatus(p.id, off ? 'AVAILABLE' : 'OFFLINE')}>
                {off ? 'Back online' : 'Mark offline'}
              </button>
            </div>
          );
        })}
      </section>

      <ActivityFeed ctx={ctx} />
    </aside>
  );
}

export function ActivityFeed({ ctx }: { ctx: Ctx }) {
  const items = [...ctx.data.activity].sort((a, b) => timeMs(b.at) - timeMs(a.at)).slice(0, 60);
  return (
    <section aria-labelledby="rail-l" className="card">
      <div className="card-head" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <h2 id="rail-l" style={{ flex: 1 }}>Agent activity</h2>
        <span className="small muted">Runs in the background</span>
      </div>
      <ol aria-live="polite" className="feed">
        {items.length === 0 && <li><span className="m muted">No activity yet.</span></li>}
        {items.map((l) => (
          <li key={l.id}>
            <span className="t mono">{fmtTime(l.at)}</span>
            <span className="d" style={{ background: ACTIVITY_DOT[String(l.kind).toUpperCase()] ?? '#98A2B3' }} />
            <span className="m">{l.message}</span>
          </li>
        ))}
      </ol>
    </section>
  );
}
