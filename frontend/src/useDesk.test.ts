import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from './api';
import { useDesk } from './useDesk';
import { deferred, deskData, sug, type Deferred } from './test/fixtures';
import type { SuggestionView } from './types';

const base = deskData();

/** Every call to api.listSuggestions gets its own controllable promise; other endpoints resolve at once. */
let calls: Deferred<SuggestionView[]>[] = [];
function stubApi() {
  calls = [];
  vi.spyOn(api, 'listSuggestions').mockImplementation(() => {
    const d = deferred<SuggestionView[]>();
    calls.push(d);
    return d.promise;
  });
  vi.spyOn(api, 'listAppointments').mockResolvedValue(base.appointments);
  vi.spyOn(api, 'listProviders').mockResolvedValue(base.providers);
  vi.spyOn(api, 'schedule').mockResolvedValue(base.schedule);
  vi.spyOn(api, 'activity').mockResolvedValue([]);
  vi.spyOn(api, 'listWaitlist').mockResolvedValue([]);
  vi.spyOn(api, 'notifications').mockResolvedValue([]);
  vi.spyOn(api, 'getStrategy').mockResolvedValue(base.strategy);
}

const flush = () => act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); });
const tick = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });
const ids = (r: { current: ReturnType<typeof useDesk> }) => r.current.data?.suggestions.map((s) => s.id);

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date(2026, 9, 20, 10, 0, 0));
  stubApi();
});
afterEach(() => vi.useRealTimers());

describe('useDesk', () => {
  it('loads every endpoint on mount and fills defaults for null responses', async () => {
    vi.spyOn(api, 'schedule').mockResolvedValue(null as never);
    vi.spyOn(api, 'getStrategy').mockResolvedValue(null as never);
    vi.spyOn(api, 'listWaitlist').mockResolvedValue(null as never);
    const { result } = renderHook(() => useDesk());
    expect(result.current.data).toBeNull();
    expect(calls).toHaveLength(1);
    await act(async () => { calls[0].resolve([sug()]); });
    await flush();
    expect(result.current.data).toMatchObject({
      suggestions: [expect.objectContaining({ id: 'SUG-001' })],
      providers: base.providers,
      schedule: { providers: [] },
      waitlist: [],
      strategy: { strategy: 'AI', available: ['AI', 'RULE_BASED', 'ROOM_CONSTRAINT'] },
    });
    expect(result.current.error).toBeNull();
    expect(result.current.lastOk).toBe(Date.now());
    for (const fn of [api.listAppointments, api.listProviders, api.schedule, api.activity, api.listWaitlist, api.notifications, api.getStrategy]) {
      expect(fn).toHaveBeenCalledTimes(1);
    }
  });

  it('polls every 5 seconds and reports seconds since the last sync', async () => {
    const { result } = renderHook(() => useDesk());
    await act(async () => { calls[0].resolve([]); });
    await flush();
    await tick(4000);
    expect(calls).toHaveLength(1);
    expect(result.current.agoSec).toBe(4);
    await tick(1000);
    expect(calls).toHaveLength(2);
    await act(async () => { calls[1].resolve([sug({ id: 'SUG-002' })]); });
    await flush();
    expect(ids(result)).toEqual(['SUG-002']);
    await tick(5000);
    expect(calls).toHaveLength(3);
  });

  it('keeps the last good data on failure, sets the error, and clears it on recovery', async () => {
    const { result } = renderHook(() => useDesk());
    await act(async () => { calls[0].resolve([sug()]); });
    await flush();
    const okAt = result.current.lastOk;

    await tick(5000);
    await act(async () => { calls[1].reject(new ApiError(0, 'NETWORK', "Can't reach the SlotMind server.")); });
    await flush();
    expect(result.current.error).toBe("Can't reach the SlotMind server.");
    expect(ids(result)).toEqual(['SUG-001']);
    expect(result.current.lastOk).toBe(okAt);

    await tick(5000);
    await act(async () => { calls[2].resolve([sug({ id: 'SUG-003' })]); });
    await flush();
    expect(result.current.error).toBeNull();
    expect(ids(result)).toEqual(['SUG-003']);
  });

  it('uses a generic message for non-Error failures', async () => {
    const { result } = renderHook(() => useDesk());
    await act(async () => { calls[0].reject('boom'); });
    await flush();
    expect(result.current.error).toBe('Connection lost');
    expect(result.current.data).toBeNull();
  });

  it('background polls share an in-flight request', async () => {
    const { result } = renderHook(() => useDesk());
    await act(async () => { calls[0].resolve([]); });
    await flush();
    await tick(5000);
    expect(calls).toHaveLength(2);
    let p!: Promise<void>;
    act(() => { p = result.current.refresh(false); });
    expect(calls).toHaveLength(2); // no new request
    await act(async () => { calls[1].resolve([sug()]); await p; });
    expect(ids(result)).toEqual(['SUG-001']);
  });

  it('refresh() after a mutation does not reuse a poll that started before it, and drops that poll\'s response', async () => {
    const { result } = renderHook(() => useDesk());
    await act(async () => { calls[0].resolve([sug({ id: 'SUG-001', status: 'PENDING' })]); });
    await flush();

    // A background poll starts BEFORE the user clicks Reject...
    await tick(5000);
    expect(calls).toHaveLength(2);
    // ...the mutation completes and the action asks for a fresh refresh.
    let done = false;
    let p!: Promise<void>;
    act(() => { p = result.current.refresh().then(() => { done = true; }); });

    // The old poll answers with pre-mutation data: it must not finish the refresh, and must not be shown.
    await act(async () => { calls[1].resolve([sug({ id: 'SUG-001', status: 'PENDING', reasoning: 'stale' })]); });
    await flush();
    expect(done).toBe(false);
    expect(calls).toHaveLength(3); // a new request was started after the old poll settled
    expect(result.current.data?.suggestions[0].reasoning).not.toBe('stale');

    await act(async () => { calls[2].resolve([sug({ id: 'SUG-001', status: 'REJECTED' })]); await p; });
    expect(done).toBe(true);
    expect(result.current.data?.suggestions[0].status).toBe('REJECTED');
  });

  it('a failing pre-mutation poll does not raise the error banner once a fresh refresh is pending', async () => {
    const { result } = renderHook(() => useDesk());
    await act(async () => { calls[0].resolve([sug()]); });
    await flush();
    await tick(5000);
    let p!: Promise<void>;
    act(() => { p = result.current.refresh(); });
    await act(async () => { calls[1].reject(new Error('stale failure')); });
    await flush();
    expect(result.current.error).toBeNull();
    await act(async () => { calls[2].resolve([sug({ id: 'SUG-9' })]); await p; });
    expect(result.current.error).toBeNull();
    expect(ids(result)).toEqual(['SUG-9']);
  });

  it('concurrent fresh refreshes are serialised and the newest response wins', async () => {
    const { result } = renderHook(() => useDesk());
    await act(async () => { calls[0].resolve([]); });
    await flush();
    await tick(5000); // poll #1 in flight
    let a!: Promise<void>, b!: Promise<void>;
    act(() => { a = result.current.refresh(); b = result.current.refresh(); });
    await act(async () => { calls[1].resolve([sug({ id: 'OLD' })]); });
    await flush();
    // Exactly one new request at a time.
    expect(calls).toHaveLength(3);
    await act(async () => { calls[2].resolve([sug({ id: 'MID' })]); });
    await flush();
    expect(calls).toHaveLength(4);
    await act(async () => { calls[3].resolve([sug({ id: 'NEW' })]); await a; await b; });
    expect(ids(result)).toEqual(['NEW']);
  });

  it('a poll arriving during a fresh refresh joins it instead of starting another request', async () => {
    const { result } = renderHook(() => useDesk());
    await act(async () => { calls[0].resolve([]); });
    await flush();
    let p!: Promise<void>;
    act(() => { p = result.current.refresh(); });
    expect(calls).toHaveLength(2);
    await tick(5000); // interval fires while the fresh request is in flight
    expect(calls).toHaveLength(2);
    await act(async () => { calls[1].resolve([sug({ id: 'FRESH' })]); await p; });
    expect(ids(result)).toEqual(['FRESH']);
  });

  it('stops polling on unmount', async () => {
    const { unmount } = renderHook(() => useDesk());
    await act(async () => { calls[0].resolve([]); });
    unmount();
    await tick(20000);
    expect(calls).toHaveLength(1);
  });
});
