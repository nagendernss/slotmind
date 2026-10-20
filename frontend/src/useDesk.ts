import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api';
import type {
  ActivityView, AppointmentView, NotificationView, ProviderView, ScheduleView, StrategyConfig,
  SuggestionView, WaitlistView,
} from './types';

export interface DeskData {
  suggestions: SuggestionView[];
  appointments: AppointmentView[];
  providers: ProviderView[];
  schedule: ScheduleView;
  activity: ActivityView[];
  waitlist: WaitlistView[];
  notifications: NotificationView[];
  strategy: StrategyConfig;
}

const POLL_MS = 5000;

/** Polls every endpoint the front desk needs. Keeps last good data on failure. */
export function useDesk() {
  const [data, setData] = useState<DeskData | null>(null);
  const [lastOk, setLastOk] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState(Date.now());
  const inflight = useRef<Promise<void> | null>(null);
  const seq = useRef(0);

  // Polls may share an in-flight request; after a mutation (fresh=true) we must wait for it and fetch again,
  // otherwise the UI shows data read before the change. Responses older than the newest applied one are dropped.
  const refresh = useCallback(async (fresh = true): Promise<void> => {
    const pending = inflight.current;
    if (pending) {
      if (!fresh) return pending;
      await pending.catch(() => undefined);
      if (inflight.current !== null && inflight.current !== pending) return refresh(true);
    }
    const mine = ++seq.current;
    const p = (async () => {
      try {
        const [suggestions, appointments, providers, schedule, activity, waitlist, notifications, strategy] = await Promise.all([
          api.listSuggestions(),
          api.listAppointments(),
          api.listProviders(),
          api.schedule(),
          api.activity(),
          api.listWaitlist(),
          api.notifications(),
          api.getStrategy(),
        ]);
        if (mine !== seq.current) return;
        setData({
          suggestions: suggestions ?? [],
          appointments: appointments ?? [],
          providers: providers ?? [],
          schedule: schedule ?? { providers: [] },
          activity: activity ?? [],
          waitlist: waitlist ?? [],
          notifications: notifications ?? [],
          strategy: strategy ?? { strategy: 'AI', available: ['AI', 'RULE_BASED', 'ROOM_CONSTRAINT'] },
        });
        setLastOk(Date.now());
        setError(null);
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Connection lost');
      } finally {
        inflight.current = null;
      }
    })();
    inflight.current = p;
    return p;
  }, []);

  useEffect(() => {
    refresh();
    const id = window.setInterval(() => { refresh(false); }, POLL_MS);
    const tick = window.setInterval(() => setNow(Date.now()), 1000);
    return () => { window.clearInterval(id); window.clearInterval(tick); };
  }, [refresh]);

  const agoSec = lastOk ? Math.max(0, Math.round((now - lastOk) / 1000)) : 0;
  return { data, error, lastOk, agoSec, refresh, setData };
}
