import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, ApiError, suggestStream } from './api';
import { useDesk } from './useDesk';
import type { Ctx, StreamState, Tab } from './ctx';
import type { AppointmentView, ProviderStatus, Strategy, SuggestionView } from './types';
import { STRATEGY_LABEL, fmtDateTime } from './format';
import { Clock, Logo, Sparkle, Warn } from './icons';
import { QueueView } from './Queue';
import { DayView } from './DayView';
import { AppointmentsView } from './Appointments';
import { ProvidersView } from './Providers';
import { WaitlistView } from './Waitlist';

const errMsg = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong');

export function Desk() {
  const { data, error, lastOk, agoSec, refresh } = useDesk();
  const [tab, setTab] = useState<Tab>('queue');
  const [streams, setStreams] = useState<Record<string, StreamState>>({});
  const [conflicts, setConflicts] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const [toastState, setToast] = useState<{ text: string; kind: 'ok' | 'error' } | null>(null);
  const toastTimer = useRef<number | undefined>(undefined);

  const toast = useCallback((text: string, kind: 'ok' | 'error' = 'ok') => {
    setToast({ text, kind });
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(null), 3800);
  }, []);

  const withBusy = useCallback(async (key: string, fn: () => Promise<void>) => {
    setBusy((b) => ({ ...b, [key]: true }));
    try { await fn(); } finally {
      setBusy((b) => { const n = { ...b }; delete n[key]; return n; });
    }
  }, []);

  const accept = useCallback((s: SuggestionView) => withBusy(s.id, async () => {
    try {
      await api.resolveSuggestion(s.id, 'ACCEPTED', 'STAFF');
      toast(`${s.patientName} moved to the new time. Confirmation sent.`);
    } catch (e) {
      if (e instanceof ApiError && (e.status === 409)) {
        const msg = e.isSlotUnavailable
          ? 'Slot no longer free — get a new suggestion'
          : (e.message || 'This suggestion can no longer be accepted.');
        setConflicts((c) => ({ ...c, [s.id]: msg }));
        toast(e.isSlotUnavailable ? 'Slot no longer free — nothing was changed' : msg, 'error');
      } else toast(errMsg(e), 'error');
    }
    await refresh();
  }), [withBusy, toast, refresh]);

  const reject = useCallback((s: SuggestionView) => withBusy(s.id, async () => {
    try {
      await api.resolveSuggestion(s.id, 'REJECTED', 'STAFF');
      toast(`Suggestion for ${s.patientName} rejected`);
    } catch (e) { toast(errMsg(e), 'error'); }
    await refresh();
  }), [withBusy, toast, refresh]);

  const streamingRef = useRef<Record<string, boolean>>({});
  const suggestAnother = useCallback(async (apptId: string) => {
    if (streamingRef.current[apptId]) return;
    streamingRef.current[apptId] = true;
    setStreams((s) => ({ ...s, [apptId]: { status: 'Starting…', text: '' } }));
    let gotToken = false;
    let gotSuggestion = false;
    try {
      await suggestStream(apptId, {
        onStatus: (t) => setStreams((s) => (s[apptId] ? { ...s, [apptId]: { ...s[apptId], status: t } } : s)),
        onToken: (t) => {
          gotToken = true;
          setStreams((s) => (s[apptId] ? { ...s, [apptId]: { ...s[apptId], text: s[apptId].text + t } } : s));
        },
        onSuggestion: (sv) => { gotSuggestion = true; setStreams((s) => (s[apptId] ? { ...s, [apptId]: { ...s[apptId], suggestion: sv, text: gotToken ? s[apptId].text : sv.reasoning } } : s)); },
      });
      toast('New suggestion ready');
    } catch (e) {
      // Fall back to the synchronous endpoint (unless the stream already produced a suggestion).
      if (gotSuggestion) { /* already saved server-side */ } else try {
        await api.suggest(apptId);
        toast('New suggestion ready');
      } catch (e2) {
        toast(errMsg(e2 instanceof ApiError ? e2 : e), 'error');
      }
    }
    setConflicts({});
    await refresh();
    delete streamingRef.current[apptId];
    setStreams((s) => { const n = { ...s }; delete n[apptId]; return n; });
  }, [toast, refresh]);

  const addApptToWaitlist = useCallback((a: AppointmentView) => withBusy('wl-' + a.id, async () => {
    try {
      const prov = data?.providers.find((p) => p.id === a.providerId);
      await api.addWaitlist({
        patientName: a.patientName,
        contact: a.contact ?? undefined,
        type: a.type,
        specialty: prov?.specialty ?? 'GENERAL',
        preferredWindow: 'ANY',
        clinicId: prov?.clinicId ?? a.slot?.clinicId ?? undefined,
      });
      toast(`${a.patientName} added to the waitlist`);
    } catch (e) { toast(errMsg(e), 'error'); }
    await refresh();
  }), [withBusy, toast, refresh, data]);

  const setProviderStatus = useCallback((id: string, status: ProviderStatus) => withBusy('p-' + id, async () => {
    try {
      const p = await api.setProviderStatus(id, status);
      const name = p?.name ?? data?.providers.find((x) => x.id === id)?.name ?? id;
      toast(status === 'OFFLINE' ? `${name} is offline — AI advisor is drafting new times` : `${name} is ${status.toLowerCase()}`);
    } catch (e) { toast(errMsg(e), 'error'); }
    await refresh();
  }), [withBusy, toast, refresh, data]);

  const cancelSlot = useCallback((slotId: string) => withBusy('s-' + slotId, async () => {
    try {
      await api.setSlotStatus(slotId, 'CANCELLED');
      toast('Slot cancelled — the affected patient is in the queue');
    } catch (e) { toast(errMsg(e), 'error'); }
    await refresh();
  }), [withBusy, toast, refresh]);

  const setStrategy = useCallback((s: Strategy) => withBusy('strategy', async () => {
    try {
      await api.setStrategy(s);
      toast(`Strategy: ${STRATEGY_LABEL[s] ?? s}`);
    } catch (e) { toast(errMsg(e), 'error'); }
    await refresh();
  }), [withBusy, toast, refresh]);

  const complete = useCallback((id: string) => withBusy('c-' + id, async () => {
    try { await api.completeAppointment(id); toast('Marked completed'); } catch (e) { toast(errMsg(e), 'error'); }
    await refresh();
  }), [withBusy, toast, refresh]);

  const book: Ctx['book'] = useCallback(async (b) => {
    try {
      const a = await api.bookAppointment(b);
      toast(`Booked ${a?.patientName ?? b.patientName}`);
      await refresh();
      return null;
    } catch (e) {
      if (e instanceof ApiError && e.isSlotUnavailable) return 'That slot was just taken. Pick another one.';
      return errMsg(e);
    }
  }, [toast, refresh]);

  const addWaitlist: Ctx['addWaitlist'] = useCallback(async (b) => {
    try {
      await api.addWaitlist(b);
      toast(`${b.patientName} added to the waitlist`);
      await refresh();
      return null;
    } catch (e) { return errMsg(e); }
  }, [toast, refresh]);

  const waitlistAccept = useCallback((id: string) => withBusy('w-' + id, async () => {
    try { await api.acceptWaitlist(id); toast('Offer accepted — appointment booked'); } catch (e) {
      toast(e instanceof ApiError && e.isSlotUnavailable ? 'Slot no longer free — nothing was changed' : errMsg(e), 'error');
    }
    await refresh();
  }), [withBusy, toast, refresh]);

  const waitlistDecline = useCallback((id: string) => withBusy('w-' + id, async () => {
    try { await api.declineWaitlist(id); toast('Offer declined'); } catch (e) { toast(errMsg(e), 'error'); }
    await refresh();
  }), [withBusy, toast, refresh]);

  const goTab = useCallback((t: Tab) => { setTab(t); window.scrollTo({ top: 0 }); }, []);

  // Clear conflict flags whose suggestion is no longer pending.
  useEffect(() => {
    if (!data) return;
    setConflicts((c) => {
      const keys = Object.keys(c).filter((id) => data.suggestions.some((s) => s.id === id && s.status === 'PENDING'));
      if (keys.length === Object.keys(c).length) return c;
      const n: Record<string, string> = {};
      keys.forEach((k) => { n[k] = c[k]; });
      return n;
    });
  }, [data]);

  const ctx: Ctx | null = useMemo(() => (data ? {
    data, streams, conflicts, busy, goTab, toast, refresh, accept, reject, suggestAnother, addApptToWaitlist,
    setProviderStatus, cancelSlot, setStrategy, complete, book, addWaitlist, waitlistAccept, waitlistDecline,
  } : null), [data, streams, conflicts, busy, goTab, toast, refresh, accept, reject, suggestAnother, addApptToWaitlist,
    setProviderStatus, cancelSlot, setStrategy, complete, book, addWaitlist, waitlistAccept, waitlistDecline]);

  const pendingCount = data ? data.appointments.filter((a) => a.status === 'RESCHEDULE_PENDING').length : 0;
  const offeredCount = data ? data.waitlist.filter((w) => w.status === 'OFFERED').length : 0;
  const tabs: { id: Tab; label: string; count?: number; hot?: boolean }[] = [
    { id: 'queue', label: 'Re-schedule queue', count: pendingCount, hot: pendingCount > 0 },
    { id: 'day', label: 'Day view' },
    { id: 'appts', label: 'Appointments', count: data?.appointments.length },
    { id: 'providers', label: 'Providers', count: data?.providers.length },
    { id: 'waitlist', label: 'Waitlist', count: data?.waitlist.filter((w) => w.status === 'WAITING' || w.status === 'OFFERED').length, hot: offeredCount > 0 },
  ];

  const strategy = data?.strategy.strategy ?? 'AI';
  const available = data?.strategy.available?.length ? data.strategy.available : ['AI', 'RULE_BASED', 'ROOM_CONSTRAINT'];
  const today = new Date();
  const dateLabel = today.toLocaleDateString('en-US', { weekday: 'long', month: 'short', day: 'numeric' });

  return (
    <div>
      <header className="header">
        <div className="header-inner">
          <a className="brand" href="#/">
            <div className="brand-mark"><Logo /></div>
            <div style={{ display: 'flex', flexDirection: 'column' }}>
              <span style={{ fontWeight: 700, fontSize: 16, letterSpacing: '-0.01em' }}>SlotMind</span>
              <span className="small muted">CareBridge · Front desk</span>
            </div>
          </a>
          <div className="vsep" />
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#344054', fontSize: 13 }}>
            <Clock />
            <span>{dateLabel} · <span className="mono">{fmtDateTime(today)}</span></span>
          </div>
          <div style={{ marginLeft: 'auto', display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 12 }}>
            {error ? (
              <div className="pill err" role="status"><span className="d" style={{ background: '#F04438' }} /><span>Connection lost</span></div>
            ) : lastOk ? (
              <div className="pill ok" role="status"><span className="d pulse" style={{ background: '#12B76A' }} /><span>Live · synced {agoSec}s ago</span></div>
            ) : (
              <div className="pill ok"><span className="d pulse" style={{ background: '#98A2B3' }} /><span>Connecting…</span></div>
            )}
            <div role="group" aria-label="Scheduling strategy" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="small muted">Strategy</span>
              <div className="seg">
                {available.map((k) => (
                  <button key={k} type="button" aria-pressed={strategy === k} disabled={!data || !!busy.strategy}
                    onClick={() => strategy !== k && setStrategy(k)}
                    style={strategy === k && k === 'AI' ? { color: '#1D4ED8' } : undefined}>
                    {k === 'AI' && <Sparkle />}
                    {STRATEGY_LABEL[k] ?? k}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </div>
        <nav aria-label="Sections" className="tabs">
          {tabs.map((t) => (
            <button key={t.id} type="button" aria-current={tab === t.id ? 'page' : undefined} onClick={() => goTab(t.id)}>
              <span>{t.label}</span>
              {t.count !== undefined && <span className={'count' + (t.hot ? ' hot' : '')}>{t.count}</span>}
            </button>
          ))}
        </nav>
      </header>

      {error && (
        <div className="banner" role="alert">
          <div className="banner-inner">
            <Warn size={18} color="#D92D20" />
            <span style={{ flex: '1 1 320px' }}>
              <strong>Can't reach the SlotMind server.</strong> Retrying every 5 seconds.
              {lastOk ? ` The list below was last updated at ${fmtDateTime(new Date(lastOk))}, so check with the patient before you accept.` : ''}
            </span>
            <button type="button" className="btn" onClick={() => refresh()}>Retry now</button>
          </div>
        </div>
      )}

      <main>
        {!ctx ? (
          <div aria-busy="true" aria-label="Loading" className="cols">
            <div className="col-main">
              <div className="sk" style={{ height: 28, width: 260 }} />
              <div className="sk" style={{ height: 16, maxWidth: 420 }} />
              <div className="sk" style={{ height: 210 }} />
              <div className="sk" style={{ height: 210 }} />
            </div>
            <div className="col-rail">
              <div className="sk" style={{ height: 300 }} />
              <div className="sk" style={{ height: 220 }} />
            </div>
          </div>
        ) : tab === 'queue' ? <QueueView ctx={ctx} />
          : tab === 'day' ? <DayView ctx={ctx} />
            : tab === 'appts' ? <AppointmentsView ctx={ctx} />
              : tab === 'providers' ? <ProvidersView ctx={ctx} />
                : <WaitlistView ctx={ctx} />}
      </main>

      {toastState && (
        <div role="status" className="toast fadein" style={{ background: toastState.kind === 'error' ? '#912018' : '#101828' }}>
          <span className="d" style={{ background: toastState.kind === 'error' ? '#FDA29B' : '#47CD89' }} />
          <span>{toastState.text}</span>
        </div>
      )}
    </div>
  );
}
