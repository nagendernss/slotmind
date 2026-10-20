import type {
  ActivityView, AppointmentStatus, AppointmentView, BookRequest, NotificationView, PortalView,
  PreferredWindow, ProviderStatus, ProviderView, ScheduleView, SlotStatus, SlotView, Strategy,
  StrategyConfig, SuggestionStatus, SuggestionView, WaitlistRequest, WaitlistView,
} from './types';

const BASE = '/api';
/** Path segments come from the URL hash (portal) or server data: always encode them. */
const enc = encodeURIComponent;

export class ApiError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
  get isSlotUnavailable() {
    return this.code === 'SLOT_UNAVAILABLE' || (this.status === 409 && /slot/i.test(this.message));
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(BASE + path, {
      method,
      headers: body !== undefined ? { 'Content-Type': 'application/json', Accept: 'application/json' } : { Accept: 'application/json' },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch (e) {
    throw new ApiError(0, 'NETWORK', "Can't reach the SlotMind server.");
  }
  const text = await res.text();
  let data: unknown = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = text; }
  }
  if (!res.ok) {
    const d = (data && typeof data === 'object' ? data : {}) as { error?: string; message?: string };
    throw new ApiError(res.status, d.error || String(res.status), d.message || (typeof data === 'string' && data) || res.statusText || 'Request failed');
  }
  return data as T;
}

const q = (params: Record<string, string | undefined>) => {
  const s = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v) s.set(k, v); });
  const str = s.toString();
  return str ? '?' + str : '';
};

export const api = {
  // Appointments
  bookAppointment: (b: BookRequest) => request<AppointmentView>('POST', '/appointments', b),
  listAppointments: (status?: AppointmentStatus) => request<AppointmentView[]>('GET', '/appointments' + q({ status })),
  getAppointment: (id: string) => request<AppointmentView>('GET', `/appointments/${enc(id)}`),
  completeAppointment: (id: string) => request<AppointmentView>('PATCH', `/appointments/${enc(id)}/complete`),
  suggest: (id: string, strategy?: Strategy) => request<SuggestionView>('POST', `/appointments/${enc(id)}/suggest` + q({ strategy })),

  // Suggestions
  listSuggestions: (status?: SuggestionStatus) => request<SuggestionView[]>('GET', '/suggestions' + q({ status })),
  resolveSuggestion: (id: string, status: 'ACCEPTED' | 'REJECTED', actor: 'STAFF' | 'PATIENT' = 'STAFF') =>
    request<SuggestionView>('PATCH', `/suggestions/${enc(id)}`, { status, actor }),

  // Providers & slots
  listProviders: () => request<ProviderView[]>('GET', '/providers'),
  setProviderStatus: (id: string, status: ProviderStatus) => request<ProviderView>('PATCH', `/providers/${enc(id)}/status`, { status }),
  listSlots: (providerId?: string, status?: SlotStatus) => request<SlotView[]>('GET', '/slots' + q({ providerId, status })),
  setSlotStatus: (id: string, status: SlotStatus) => request<SlotView>('PATCH', `/slots/${enc(id)}/status`, { status }),
  schedule: () => request<ScheduleView>('GET', '/schedule'),

  // Config
  getStrategy: () => request<StrategyConfig>('GET', '/config/strategy'),
  setStrategy: (strategy: Strategy) => request<StrategyConfig>('PUT', '/config/strategy', { strategy }),

  // Misc
  rooms: () => request<unknown[]>('GET', '/rooms'),
  clinics: () => request<{ id: string; name: string }[]>('GET', '/clinics'),
  getPreference: (name: string) => request<{ patientName: string; preferredWindow: PreferredWindow; source?: string }>('GET', `/preferences/${encodeURIComponent(name)}`),
  setPreference: (name: string, preferredWindow: PreferredWindow) => request<unknown>('PUT', `/preferences/${encodeURIComponent(name)}`, { preferredWindow }),

  // Waitlist
  listWaitlist: () => request<WaitlistView[]>('GET', '/waitlist'),
  addWaitlist: (b: WaitlistRequest) => request<WaitlistView>('POST', '/waitlist', b),
  acceptWaitlist: (id: string) => request<unknown>('POST', `/waitlist/${enc(id)}/accept`),
  declineWaitlist: (id: string) => request<unknown>('POST', `/waitlist/${enc(id)}/decline`),

  notifications: () => request<NotificationView[]>('GET', '/notifications'),
  activity: () => request<ActivityView[]>('GET', '/activity'),

  // Portal
  portal: async (id: string): Promise<PortalView> => {
    const raw = await request<Record<string, unknown>>('GET', `/portal/appointments/${enc(id)}`);
    return normalizePortal(raw);
  },
  portalReschedule: (id: string) => request<unknown>('POST', `/portal/appointments/${enc(id)}/reschedule`),
};

/** Accepts a few plausible shapes for the portal view (contract only says "appointment + open suggestions"). */
function normalizePortal(raw: Record<string, unknown>): PortalView {
  const appointment = (raw.appointment ?? raw) as AppointmentView;
  let suggestions: SuggestionView[] = [];
  const cand = raw.suggestions ?? raw.openSuggestions ?? raw.pendingSuggestions;
  if (Array.isArray(cand)) suggestions = cand as SuggestionView[];
  const single = raw.pendingSuggestion ?? raw.suggestion;
  if (single && typeof single === 'object') suggestions = [single as SuggestionView, ...suggestions];
  return { appointment, suggestions };
}

// ---------- SSE over POST (EventSource can't POST) ----------

export interface StreamHandlers {
  onStatus?: (text: string) => void;
  onToken?: (text: string) => void;
  onSuggestion?: (s: SuggestionView) => void;
}

/** Parses text/event-stream from a fetch body. Resolves with the final suggestion. */
export async function suggestStream(id: string, h: StreamHandlers, signal?: AbortSignal): Promise<SuggestionView> {
  const res = await fetch(`${BASE}/appointments/${enc(id)}/suggest/stream`, {
    method: 'POST',
    headers: { Accept: 'text/event-stream' },
    signal,
  });
  if (!res.ok || !res.body) {
    let code = String(res.status), message = res.statusText;
    try { const d = await res.json(); code = d.error || code; message = d.message || message; } catch { /* ignore */ }
    throw new ApiError(res.status, code, message || 'Stream failed');
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  let final: SuggestionView | null = null;

  const dispatch = (event: string, data: string) => {
    if (event === 'token') {
      h.onToken?.(unwrapToken(data));
    } else if (event === 'status') {
      h.onStatus?.(unwrapToken(data));
    } else if (event === 'suggestion') {
      try { final = JSON.parse(data) as SuggestionView; h.onSuggestion?.(final); } catch { /* ignore */ }
    } else if (event === 'error') {
      let msg = data;
      try { const d = JSON.parse(data); msg = d.message || data; } catch { /* plain */ }
      throw new ApiError(500, 'STREAM_ERROR', msg);
    }
  };

  const readEvents = async () => {
    for (;;) {
      const { value, done } = await reader.read();
      if (value) buf += decoder.decode(value, { stream: true });
      // Normalise CRLF and bare CR line endings. A trailing CR may be the first half of a CRLF split
      // across chunks, so it is only converted once more data (or the end of the stream) arrives.
      buf = buf.replace(/\r\n/g, '\n').replace(/\r(?!$)/g, '\n');
      if (done && buf.endsWith('\r')) buf = buf.slice(0, -1) + '\n';
      let idx: number;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        let event = 'message';
        const dataLines: string[] = [];
        for (const line of block.split('\n')) {
          if (line.startsWith(':')) continue;
          if (line.startsWith('event:')) event = line.slice(6).trim();
          else if (line.startsWith('data:')) dataLines.push(line.slice(5)); // Spring writes "data:" without a pad space, so keep leading spaces of tokens
        }
        if (dataLines.length) dispatch(event, dataLines.join('\n'));
      }
      if (done) return;
    }
  };

  try {
    await readEvents();
  } catch (e) {
    reader.cancel().catch(() => undefined); // don't leave the connection open after an error event
    throw e;
  }
  if (!final) throw new ApiError(500, 'STREAM_INCOMPLETE', 'Stream ended without a suggestion');
  return final;
}

/**
 * token/status data is raw text (Spring `data(String)`), optionally a {text}/{token} JSON object.
 * Bare JSON strings are NOT unwrapped: a reasoning word in quotes (`"urgent" `) is valid raw text, and
 * unwrapping it would drop the quotes and the trailing space.
 */
function unwrapToken(data: string): string {
  const t = data.trim();
  if (t.startsWith('{') && t.endsWith('}')) {
    try {
      const v = JSON.parse(t);
      if (v && typeof v === 'object') {
        const s = v.text ?? v.token ?? v.message ?? v.status;
        if (typeof s === 'string') return s;
      }
    } catch { /* raw */ }
  }
  return data;
}
