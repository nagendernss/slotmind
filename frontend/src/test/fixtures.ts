// Fixtures built from the decision.md §3 JSON views, plus a fake Ctx and a URL-routed fake fetch.
import { vi } from 'vitest';
import type { Ctx } from '../ctx';
import type { DeskData } from '../useDesk';
import type {
  AppointmentView, ProviderView, ScheduleSlot, ScheduleView, SlotView, SuggestionView, WaitlistView,
} from '../types';

/** A day far enough in the future that "upcoming / future" filters keep every fixture slot. */
export const DAY = '2030-01-15';
export const at = (hhmm: string, frac = '') => `${DAY}T${hhmm}:00${frac}`;

export function slot(p: Partial<SlotView> = {}): SlotView {
  return {
    id: 'SLT-001', startTime: at('11:00'), durationMinutes: 30, status: 'BOOKED',
    providerId: 'PRV-001', providerName: 'Dr. Sarah Chen', roomId: 'RM-001', roomName: 'Room 1',
    clinicId: 'CLN-001', clinicName: 'Downtown', ...p,
  };
}

export function provider(p: Partial<ProviderView> = {}): ProviderView {
  return {
    id: 'PRV-001', name: 'Dr. Sarah Chen', specialty: 'GENERAL', status: 'AVAILABLE',
    clinicId: 'CLN-001', clinicName: 'Downtown', maxDailyAppointments: 8, bookedToday: 2, ...p,
  };
}

export function appt(p: Partial<AppointmentView> = {}): AppointmentView {
  return {
    id: 'APT-001', patientName: 'Emily Watson', contact: '+15550100', type: 'ROUTINE', status: 'CONFIRMED',
    providerId: 'PRV-001', providerName: 'Dr. Sarah Chen', slot: slot(), ...p,
  };
}

export function sug(p: Partial<SuggestionView> = {}): SuggestionView {
  return {
    id: 'SUG-001', appointmentId: 'APT-001', patientName: 'Emily Watson', appointmentType: 'ROUTINE',
    appointmentStatus: 'RESCHEDULE_PENDING', triggerReason: 'PROVIDER_OFFLINE', status: 'PENDING',
    strategy: 'AI', fallbackUsed: false, crossClinic: false, confidence: 0.88,
    reasoning: 'We can offer you 2:00 PM with Dr. Lee. Would that work?',
    createdAt: at('10:00', '.123456'), resolvedAt: null,
    originalSlot: slot(),
    recommendedSlot: slot({ id: 'SLT-010', startTime: at('14:00'), status: 'OPEN', providerId: 'PRV-002', providerName: 'Dr. James Lee' }),
    alternateProviderId: 'PRV-002', ...p,
  };
}

export function schedSlot(p: Partial<ScheduleSlot> = {}): ScheduleSlot {
  return { ...slot(), appointment: null, heldBySuggestionId: null, ...p };
}

export function waitlist(p: Partial<WaitlistView> = {}): WaitlistView {
  return {
    id: 'WL-001', patientName: 'Noah Park', contact: 'noah@example.com', type: 'ROUTINE', specialty: 'GENERAL',
    preferredWindow: 'MORNING', clinicId: 'CLN-001', status: 'WAITING', createdAt: at('08:00'), offeredSlot: null, ...p,
  };
}

export function deskData(p: Partial<DeskData> = {}): DeskData {
  const schedule: ScheduleView = { providers: [{ provider: provider(), slots: [schedSlot()] }] };
  return {
    suggestions: [], appointments: [], providers: [provider()], schedule, activity: [], waitlist: [],
    notifications: [], strategy: { strategy: 'AI', available: ['AI', 'RULE_BASED', 'ROOM_CONSTRAINT'] }, ...p,
  };
}

export function makeCtx(data: DeskData = deskData(), p: Partial<Ctx> = {}): Ctx {
  return {
    data, streams: {}, conflicts: {}, busy: {},
    goTab: vi.fn(), toast: vi.fn(), refresh: vi.fn(async () => undefined),
    accept: vi.fn(), reject: vi.fn(), suggestAnother: vi.fn(), addApptToWaitlist: vi.fn(),
    setProviderStatus: vi.fn(), cancelSlot: vi.fn(), setStrategy: vi.fn(), complete: vi.fn(),
    book: vi.fn(async () => null), addWaitlist: vi.fn(async () => null),
    waitlistAccept: vi.fn(), waitlistDecline: vi.fn(), ...p,
  };
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(body === undefined ? '' : JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

type Handler = (url: string, init: RequestInit) => unknown | Promise<unknown>;

/**
 * Stubs global fetch. Routes are "METHOD /path" (path without query string, after /api).
 * A handler may return a Response (used as-is) or any value (sent as 200 JSON).
 */
export function routeFetch(routes: Record<string, Handler | unknown>) {
  const fn = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input);
    const method = (init.method ?? 'GET').toUpperCase();
    const path = url.replace(/^\/api/, '').split('?')[0];
    const key = `${method} ${path}`;
    if (!(key in routes)) return jsonResponse({ error: 'NOT_FOUND', message: `no route ${key}` }, 404);
    const r = routes[key];
    const v = typeof r === 'function' ? await (r as Handler)(url, init) : r;
    return v instanceof Response ? v : jsonResponse(v);
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

/** Desk-wide default routes (every endpoint useDesk polls). */
export function deskRoutes(d: DeskData = deskData()): Record<string, unknown> {
  return {
    'GET /suggestions': () => d.suggestions,
    'GET /appointments': () => d.appointments,
    'GET /providers': () => d.providers,
    'GET /schedule': () => d.schedule,
    'GET /activity': () => d.activity,
    'GET /waitlist': () => d.waitlist,
    'GET /notifications': () => d.notifications,
    'GET /config/strategy': () => d.strategy,
  };
}

export interface Deferred<T> { promise: Promise<T>; resolve: (v: T) => void; reject: (e: unknown) => void }
export function deferred<T>(): Deferred<T> {
  let resolve!: (v: T) => void, reject!: (e: unknown) => void;
  const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

/** A ReadableStream body that yields the given string chunks (encoded as UTF-8). */
export function streamOf(chunks: (string | Uint8Array)[]): ReadableStream<Uint8Array> {
  const enc = new TextEncoder();
  let i = 0;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (i < chunks.length) {
        const c = chunks[i++];
        controller.enqueue(typeof c === 'string' ? enc.encode(c) : c);
      } else controller.close();
    },
  });
}
