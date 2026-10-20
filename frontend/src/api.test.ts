import { describe, expect, it, vi } from 'vitest';
import { api, ApiError, suggestStream } from './api';
import { jsonResponse, streamOf, sug } from './test/fixtures';

function stubFetch(res: Response | (() => Response | Promise<Response>)) {
  const fn = vi.fn(async (_u: RequestInfo | URL, _i?: RequestInit) => (typeof res === 'function' ? res() : res));
  vi.stubGlobal('fetch', fn);
  return fn;
}
const call = (fn: ReturnType<typeof stubFetch>, i = 0) => {
  const [url, init] = fn.mock.calls[i];
  return { url: String(url), init: init ?? {}, body: init?.body ? JSON.parse(String(init.body)) : undefined };
};

describe('api endpoints', () => {
  type Case = [name: string, run: () => Promise<unknown>, method: string, url: string, body?: unknown];
  const cases: Case[] = [
    ['bookAppointment', () => api.bookAppointment({ patientName: 'A', type: 'URGENT', providerId: 'PRV-001', slotId: 'SLT-009', contact: 'a@b.c' }),
      'POST', '/api/appointments', { patientName: 'A', type: 'URGENT', providerId: 'PRV-001', slotId: 'SLT-009', contact: 'a@b.c' }],
    ['listAppointments()', () => api.listAppointments(), 'GET', '/api/appointments'],
    ['listAppointments(status)', () => api.listAppointments('RESCHEDULE_PENDING'), 'GET', '/api/appointments?status=RESCHEDULE_PENDING'],
    ['getAppointment', () => api.getAppointment('APT-001'), 'GET', '/api/appointments/APT-001'],
    ['path ids are URL-encoded', () => api.getAppointment('APT/1?x#y'), 'GET', '/api/appointments/APT%2F1%3Fx%23y'],
    ['portal path id is URL-encoded', () => api.portalReschedule('A B'), 'POST', '/api/portal/appointments/A%20B/reschedule'],
    ['completeAppointment', () => api.completeAppointment('APT-001'), 'PATCH', '/api/appointments/APT-001/complete'],
    ['suggest()', () => api.suggest('APT-001'), 'POST', '/api/appointments/APT-001/suggest'],
    ['suggest(strategy)', () => api.suggest('APT-001', 'RULE_BASED'), 'POST', '/api/appointments/APT-001/suggest?strategy=RULE_BASED'],
    ['listSuggestions()', () => api.listSuggestions(), 'GET', '/api/suggestions'],
    ['listSuggestions(status)', () => api.listSuggestions('PENDING'), 'GET', '/api/suggestions?status=PENDING'],
    ['resolveSuggestion default actor', () => api.resolveSuggestion('SUG-001', 'ACCEPTED'), 'PATCH', '/api/suggestions/SUG-001', { status: 'ACCEPTED', actor: 'STAFF' }],
    ['resolveSuggestion patient', () => api.resolveSuggestion('SUG-001', 'REJECTED', 'PATIENT'), 'PATCH', '/api/suggestions/SUG-001', { status: 'REJECTED', actor: 'PATIENT' }],
    ['listProviders', () => api.listProviders(), 'GET', '/api/providers'],
    ['setProviderStatus', () => api.setProviderStatus('PRV-001', 'OFFLINE'), 'PATCH', '/api/providers/PRV-001/status', { status: 'OFFLINE' }],
    ['listSlots()', () => api.listSlots(), 'GET', '/api/slots'],
    ['listSlots(provider,status)', () => api.listSlots('PRV-002', 'OPEN'), 'GET', '/api/slots?providerId=PRV-002&status=OPEN'],
    ['listSlots(undefined,status)', () => api.listSlots(undefined, 'OPEN'), 'GET', '/api/slots?status=OPEN'],
    ['setSlotStatus', () => api.setSlotStatus('SLT-003', 'CANCELLED'), 'PATCH', '/api/slots/SLT-003/status', { status: 'CANCELLED' }],
    ['schedule', () => api.schedule(), 'GET', '/api/schedule'],
    ['getStrategy', () => api.getStrategy(), 'GET', '/api/config/strategy'],
    ['setStrategy', () => api.setStrategy('ROOM_CONSTRAINT'), 'PUT', '/api/config/strategy', { strategy: 'ROOM_CONSTRAINT' }],
    ['rooms', () => api.rooms(), 'GET', '/api/rooms'],
    ['clinics', () => api.clinics(), 'GET', '/api/clinics'],
    ['getPreference encodes name', () => api.getPreference('Emily Watson/x'), 'GET', '/api/preferences/Emily%20Watson%2Fx'],
    ['setPreference', () => api.setPreference('Emily Watson', 'AFTERNOON'), 'PUT', '/api/preferences/Emily%20Watson', { preferredWindow: 'AFTERNOON' }],
    ['listWaitlist', () => api.listWaitlist(), 'GET', '/api/waitlist'],
    ['addWaitlist', () => api.addWaitlist({ patientName: 'N', type: 'ROUTINE', specialty: 'GENERAL', preferredWindow: 'ANY' }),
      'POST', '/api/waitlist', { patientName: 'N', type: 'ROUTINE', specialty: 'GENERAL', preferredWindow: 'ANY' }],
    ['acceptWaitlist', () => api.acceptWaitlist('WL-001'), 'POST', '/api/waitlist/WL-001/accept'],
    ['declineWaitlist', () => api.declineWaitlist('WL-001'), 'POST', '/api/waitlist/WL-001/decline'],
    ['notifications', () => api.notifications(), 'GET', '/api/notifications'],
    ['activity', () => api.activity(), 'GET', '/api/activity'],
    ['portalReschedule', () => api.portalReschedule('APT-002'), 'POST', '/api/portal/appointments/APT-002/reschedule'],
  ];

  it.each(cases)('%s', async (_n, run, method, url, body) => {
    const fn = stubFetch(jsonResponse({ ok: true }));
    await expect(run()).resolves.toEqual({ ok: true });
    expect(fn).toHaveBeenCalledTimes(1);
    const c = call(fn);
    expect(c.init.method).toBe(method);
    expect(c.url).toBe(url);
    expect(c.body).toEqual(body);
    const headers = c.init.headers as Record<string, string>;
    expect(headers.Accept).toBe('application/json');
    if (body !== undefined) expect(headers['Content-Type']).toBe('application/json');
    else expect(headers['Content-Type']).toBeUndefined();
  });

  it('returns null for an empty 2xx body', async () => {
    stubFetch(new Response('', { status: 200 }));
    await expect(api.acceptWaitlist('WL-1')).resolves.toBeNull();
  });

  it('returns raw text when a 2xx body is not JSON', async () => {
    stubFetch(new Response('pong', { status: 200 }));
    await expect(api.rooms()).resolves.toBe('pong');
  });

  describe('portal normalisation', () => {
    it('reads {appointment, suggestions}', async () => {
      const a = { id: 'APT-001' };
      stubFetch(jsonResponse({ appointment: a, suggestions: [sug()] }));
      const v = await api.portal('APT-001');
      expect(v.appointment).toEqual(a);
      expect(v.suggestions).toHaveLength(1);
    });
    it('accepts a bare appointment + pendingSuggestion shape', async () => {
      stubFetch(jsonResponse({ id: 'APT-001', patientName: 'X', pendingSuggestion: sug({ id: 'SUG-9' }), openSuggestions: [sug({ id: 'SUG-8' })] }));
      const v = await api.portal('APT-001');
      expect(v.appointment.id).toBe('APT-001');
      expect(v.suggestions.map((s) => s.id)).toEqual(['SUG-9', 'SUG-8']);
    });
    it('defaults to no suggestions', async () => {
      const fn = stubFetch(jsonResponse({ appointment: { id: 'APT-1' } }));
      expect((await api.portal('APT-1')).suggestions).toEqual([]);
      expect(call(fn).url).toBe('/api/portal/appointments/APT-1');
    });
  });
});

describe('ApiError parsing', () => {
  it('parses {error,message} bodies', async () => {
    stubFetch(jsonResponse({ error: 'INVALID_STATE', message: 'Suggestion is not pending' }, 409));
    const e = await api.resolveSuggestion('SUG-1', 'ACCEPTED').catch((x) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(e).toBeInstanceOf(Error);
    expect(e.status).toBe(409);
    expect(e.code).toBe('INVALID_STATE');
    expect(e.message).toBe('Suggestion is not pending');
    expect(e.isSlotUnavailable).toBe(false);
  });

  it('flags 409 SLOT_UNAVAILABLE', async () => {
    stubFetch(jsonResponse({ error: 'SLOT_UNAVAILABLE', message: 'Taken' }, 409));
    const e = await api.resolveSuggestion('SUG-1', 'ACCEPTED').catch((x) => x);
    expect(e.code).toBe('SLOT_UNAVAILABLE');
    expect(e.isSlotUnavailable).toBe(true);
  });

  it('flags a 409 whose message mentions the slot even without the code', () => {
    expect(new ApiError(409, '409', 'The slot was booked meanwhile').isSlotUnavailable).toBe(true);
    expect(new ApiError(400, 'VALIDATION', 'slot is required').isSlotUnavailable).toBe(false);
  });

  it('uses the text body for non-JSON errors', async () => {
    stubFetch(new Response('Bad Gateway from proxy', { status: 502, statusText: 'Bad Gateway' }));
    const e = await api.listProviders().catch((x) => x);
    expect(e).toMatchObject({ status: 502, code: '502', message: 'Bad Gateway from proxy' });
  });

  it('falls back to statusText for an empty error body', async () => {
    stubFetch(new Response('', { status: 404, statusText: 'Not Found' }));
    const e = await api.getAppointment('X').catch((x) => x);
    expect(e).toMatchObject({ status: 404, code: '404', message: 'Not Found' });
  });

  it('falls back to a generic message when nothing else is available', async () => {
    stubFetch(new Response('', { status: 500 }));
    const e = await api.getAppointment('X').catch((x) => x);
    expect(e.status).toBe(500);
    expect(e.message).toBe('Request failed');
  });

  it('maps network failures to status 0 / NETWORK', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
    const e = await api.listProviders().catch((x) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(e).toMatchObject({ status: 0, code: 'NETWORK' });
  });
});

// ---------- SSE ----------

const view = sug({ id: 'SUG-042', reasoning: 'We can offer you "2:00 PM" today.' });
const sse = (event: string, data: string, nl = '\n') => `event:${event}${nl}data:${data}${nl}${nl}`;
const sseResponse = (chunks: (string | Uint8Array)[]) =>
  new Response(streamOf(chunks), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });

/** Split a string into chunks of `n` characters — creates mid-line / mid-event boundaries. */
const chop = (s: string, n: number) => s.match(new RegExp(`[\\s\\S]{1,${n}}`, 'g')) ?? [];

function collect() {
  const status: string[] = [];
  const tokens: string[] = [];
  const suggestions: unknown[] = [];
  return {
    status, tokens, suggestions,
    h: { onStatus: (t: string) => status.push(t), onToken: (t: string) => tokens.push(t), onSuggestion: (s: unknown) => suggestions.push(s) },
  };
}

// What the Spring controller writes (AppointmentController.suggestStream): "event:x\ndata:y\n\n".
const tokenWords = ['We ', 'can ', 'offer ', 'you ', '"2:00 ', 'PM" ', ' today.'];
const fullStream =
  ':keep-alive comment\n\n'
  + sse('status', 'Collecting legal open slots for APT-001...')
  + sse('status', 'Suggestion validated (AI)')
  + tokenWords.map((t) => sse('token', t)).join('')
  + sse('suggestion', JSON.stringify(view));

describe('suggestStream', () => {
  it('POSTs to /api/appointments/{id}/suggest/stream asking for text/event-stream', async () => {
    const fn = stubFetch(sseResponse([fullStream]));
    const signal = new AbortController().signal;
    await suggestStream('APT-001', {}, signal);
    const c = call(fn);
    expect(c.url).toBe('/api/appointments/APT-001/suggest/stream');
    expect(c.init.method).toBe('POST');
    expect((c.init.headers as Record<string, string>).Accept).toBe('text/event-stream');
    expect(c.init.signal).toBe(signal);
  });

  it.each([
    ['one chunk', [fullStream]],
    ['1-char chunks', chop(fullStream, 1)],
    ['3-char chunks', chop(fullStream, 3)],
    ['7-char chunks', chop(fullStream, 7)],
    ['split between the two newlines of a boundary', fullStream.split(/(?<=\n)(?=\n)/)],
  ])('parses status/token/suggestion events (%s)', async (_n, chunks) => {
    stubFetch(sseResponse(chunks));
    const c = collect();
    const final = await suggestStream('APT-001', c.h);
    expect(c.status).toEqual(['Collecting legal open slots for APT-001...', 'Suggestion validated (AI)']);
    expect(c.tokens).toEqual(tokenWords);
    expect(c.tokens.join('')).toBe('We can offer you "2:00 PM"  today.');
    expect(c.suggestions).toEqual([view]);
    expect(final).toEqual(view);
  });

  it('handles CRLF line endings, including a CR/LF pair split across chunks', async () => {
    const crlf = fullStream.replace(/\n/g, '\r\n');
    for (const chunks of [[crlf], chop(crlf, 1), chop(crlf, 2), chop(crlf, 5)]) {
      stubFetch(sseResponse(chunks));
      const c = collect();
      await expect(suggestStream('APT-001', c.h)).resolves.toEqual(view);
      expect(c.tokens).toEqual(tokenWords);
      expect(c.status).toHaveLength(2);
    }
  });

  it('handles bare CR line endings', async () => {
    const cr = fullStream.replace(/\n/g, '\r');
    for (const chunks of [[cr], chop(cr, 1)]) {
      stubFetch(sseResponse(chunks));
      const c = collect();
      await expect(suggestStream('APT-001', c.h)).resolves.toEqual(view);
      expect(c.tokens).toEqual(tokenWords);
    }
  });

  it('decodes multi-byte UTF-8 split across chunks', async () => {
    const bytes = new TextEncoder().encode(sse('token', 'café — ok ') + sse('suggestion', JSON.stringify(view)));
    const cut = bytes.indexOf(0xc3) + 1; // middle of "é"
    stubFetch(sseResponse([bytes.slice(0, cut), bytes.slice(cut)]));
    const c = collect();
    await suggestStream('APT-001', c.h);
    expect(c.tokens).toEqual(['café — ok ']);
  });

  it('preserves leading and trailing spaces and quotes in token text', async () => {
    stubFetch(sseResponse([sse('token', '  indented'), sse('token', '"quoted" '), sse('token', '"last"'), sse('suggestion', JSON.stringify(view))]));
    const c = collect();
    await suggestStream('APT-001', c.h);
    expect(c.tokens).toEqual(['  indented', '"quoted" ', '"last"']);
  });

  it('unwraps {text}/{token} JSON object tokens', async () => {
    stubFetch(sseResponse([sse('token', '{"text":"Hello "}'), sse('token', '{"token":"world"}'), sse('status', '{"status":"Ranking"}'), sse('suggestion', JSON.stringify(view))]));
    const c = collect();
    await suggestStream('APT-001', c.h);
    expect(c.tokens).toEqual(['Hello ', 'world']);
    expect(c.status).toEqual(['Ranking']);
  });

  it('joins multi-line data with newlines', async () => {
    stubFetch(sseResponse(['event:token\ndata:line one\ndata:line two\n\n', sse('suggestion', JSON.stringify(view))]));
    const c = collect();
    await suggestStream('APT-001', c.h);
    expect(c.tokens).toEqual(['line one\nline two']);
  });

  it('ignores unnamed (message) events and events without data', async () => {
    stubFetch(sseResponse(['data:hello\n\n', 'event:token\n\n', sse('suggestion', JSON.stringify(view))]));
    const c = collect();
    await expect(suggestStream('APT-001', c.h)).resolves.toEqual(view);
    expect(c.tokens).toEqual([]);
  });

  it('rejects with the server message on an error event (JSON) and cancels the stream', async () => {
    let cancelled = false;
    const body = new ReadableStream<Uint8Array>({
      start(ctl) { ctl.enqueue(new TextEncoder().encode(sse('status', 'Collecting') + sse('error', '{"error":"INVALID_STATE","message":"Appointment is completed"}'))); },
      cancel() { cancelled = true; },
    });
    stubFetch(new Response(body, { status: 200 }));
    const c = collect();
    const e = await suggestStream('APT-001', c.h).catch((x) => x);
    expect(e).toBeInstanceOf(ApiError);
    expect(e.code).toBe('STREAM_ERROR');
    expect(e.message).toBe('Appointment is completed');
    expect(c.status).toEqual(['Collecting']);
    expect(cancelled).toBe(true);
  });

  it('rejects with plain-text error data', async () => {
    stubFetch(sseResponse([sse('error', 'boom')]));
    await expect(suggestStream('APT-001', {})).rejects.toMatchObject({ code: 'STREAM_ERROR', message: 'boom' });
  });

  it('rejects with STREAM_INCOMPLETE when the stream ends without a suggestion', async () => {
    stubFetch(sseResponse([sse('status', 'Collecting'), sse('token', 'We ')]));
    const c = collect();
    await expect(suggestStream('APT-001', c.h)).rejects.toMatchObject({ code: 'STREAM_INCOMPLETE' });
    expect(c.tokens).toEqual(['We ']);
  });

  it('rejects with STREAM_INCOMPLETE when the suggestion event is truncated', async () => {
    stubFetch(sseResponse(['event:suggestion\ndata:{"id":"SUG-1"']));
    await expect(suggestStream('APT-001', {})).rejects.toMatchObject({ code: 'STREAM_INCOMPLETE' });
  });

  it('rejects with STREAM_INCOMPLETE when the suggestion JSON is invalid', async () => {
    stubFetch(sseResponse([sse('suggestion', '{not json')]));
    await expect(suggestStream('APT-001', {})).rejects.toMatchObject({ code: 'STREAM_INCOMPLETE' });
  });

  it('turns a non-2xx response into an ApiError with the JSON body', async () => {
    stubFetch(jsonResponse({ error: 'NOT_FOUND', message: 'No appointment APT-404' }, 404));
    await expect(suggestStream('APT-404', {})).rejects.toMatchObject({ status: 404, code: 'NOT_FOUND', message: 'No appointment APT-404' });
  });

  it('turns a non-2xx non-JSON response into an ApiError', async () => {
    stubFetch(new Response('nope', { status: 503, statusText: 'Service Unavailable' }));
    await expect(suggestStream('APT-1', {})).rejects.toMatchObject({ status: 503, code: '503', message: 'Service Unavailable' });
  });

  it('rejects a 2xx response without a body', async () => {
    stubFetch(new Response(null, { status: 204 }));
    await expect(suggestStream('APT-1', {})).rejects.toBeInstanceOf(ApiError);
  });
});
