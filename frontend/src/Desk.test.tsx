import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Desk } from './Desk';
import {
  appt, at, deskData, deskRoutes, jsonResponse, provider, routeFetch, schedSlot, slot, streamOf, sug, waitlist,
} from './test/fixtures';
import type { DeskData } from './useDesk';

function state(): DeskData {
  const chen = provider();
  return deskData({
    providers: [chen, provider({ id: 'PRV-002', name: 'Dr. James Lee', specialty: 'PEDIATRIC', clinicId: 'CLN-002' })],
    appointments: [
      appt({ status: 'RESCHEDULE_PENDING' }),
      appt({ id: 'APT-002', patientName: 'Liam Ortiz', providerId: 'PRV-002', providerName: 'Dr. James Lee', slot: slot({ id: 'SLT-002', startTime: at('12:00'), providerId: 'PRV-002' }) }),
    ],
    suggestions: [sug()],
    waitlist: [waitlist({ status: 'OFFERED', offeredSlot: slot({ status: 'OPEN' }) }), waitlist({ id: 'WL-002', patientName: 'X', status: 'BOOKED' })],
    schedule: { providers: [{ provider: chen, slots: [schedSlot({ id: 'SLT-001', appointment: { id: 'APT-001', patientName: 'Emily Watson', type: 'ROUTINE', status: 'CONFIRMED' } })] }] },
  });
}

function setup(extra: Record<string, unknown> = {}, d = state()) {
  const fetch = routeFetch({ ...deskRoutes(d), ...extra });
  const user = userEvent.setup();
  render(<Desk />);
  return { fetch, user, d };
}
const calls = (fetch: ReturnType<typeof routeFetch>, method: string, path: string) =>
  fetch.mock.calls.filter(([u, i]) => String(u).split('?')[0] === '/api' + path && (i?.method ?? 'GET') === method);
const bodyOf = (fetch: ReturnType<typeof routeFetch>, method: string, path: string) =>
  JSON.parse(String(calls(fetch, method, path)[0][1]!.body));
const toast = (text: string | RegExp) => screen.findByText(text, { selector: '.toast span' });

describe('Desk shell', () => {
  it('shows a loading skeleton, then the queue and live status', async () => {
    setup();
    expect(screen.getByLabelText('Loading')).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByText('Connecting…')).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Re-schedule queue' })).toBeInTheDocument();
    expect(screen.getByText(/Live · synced \d+s ago/)).toBeInTheDocument();
    expect(screen.getByRole('article', { name: 'Emily Watson' })).toBeInTheDocument();
  });

  it('tabs switch views, mark the current one, and show counts', async () => {
    const { user } = setup();
    await screen.findByRole('heading', { name: 'Re-schedule queue' });
    const nav = screen.getByRole('navigation', { name: 'Sections' });
    const tab = (n: RegExp) => within(nav).getByRole('button', { name: n });
    expect(tab(/Re-schedule queue/)).toHaveAttribute('aria-current', 'page');
    expect(tab(/Re-schedule queue/)).toHaveTextContent('Re-schedule queue1');
    expect(tab(/Appointments/)).toHaveTextContent('Appointments2');
    expect(tab(/Providers/)).toHaveTextContent('Providers2');
    expect(tab(/Waitlist/)).toHaveTextContent('Waitlist1');
    expect(tab(/Waitlist/).querySelector('.count.hot')).not.toBeNull();

    for (const [t, h] of [[/Day view/, 'Day view'], [/Appointments/, 'Appointments'], [/Providers/, 'Providers'], [/Waitlist/, 'Waitlist'], [/Re-schedule/, 'Re-schedule queue']] as const) {
      await user.click(tab(t));
      expect(screen.getByRole('heading', { level: 1, name: h })).toBeInTheDocument();
      expect(tab(t)).toHaveAttribute('aria-current', 'page');
    }
    for (const b of screen.getAllByRole('button')) expect(b).toHaveAccessibleName();
  });

  it('strategy switch: aria-pressed, PUT /config/strategy and a toast', async () => {
    const { fetch, user } = setup({ 'PUT /config/strategy': { strategy: 'RULE_BASED', available: [] } });
    await screen.findByRole('heading', { name: 'Re-schedule queue' });
    const group = screen.getByRole('group', { name: 'Scheduling strategy' });
    expect(within(group).getByRole('button', { name: 'AI advisor' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(within(group).getByRole('button', { name: 'AI advisor' }));
    expect(calls(fetch, 'PUT', '/config/strategy')).toHaveLength(0);
    await user.click(within(group).getByRole('button', { name: 'Rule-based' }));
    expect(await toast('Strategy: Rule-based')).toBeInTheDocument();
    expect(bodyOf(fetch, 'PUT', '/config/strategy')).toEqual({ strategy: 'RULE_BASED' });
  });

  it('shows the error banner when polling fails, keeps data, and Retry recovers', async () => {
    let fail = false;
    const d = state();
    const { fetch, user } = setup({ 'GET /providers': () => (fail ? jsonResponse({ error: 'X', message: 'down' }, 503) : d.providers) }, d);
    await screen.findByRole('article', { name: 'Emily Watson' });

    fail = true;
    await user.click(screen.getByRole('button', { name: 'Mark Dr. Sarah Chen offline' })); // any action refreshes
    const banner = await screen.findByRole('alert');
    expect(banner).toHaveTextContent("Can't reach the SlotMind server.");
    expect(banner).toHaveTextContent('The list below was last updated at');
    expect(screen.getByText('Connection lost')).toBeInTheDocument();
    expect(screen.getByRole('article', { name: 'Emily Watson' })).toBeInTheDocument(); // last good data kept

    fail = false;
    const before = calls(fetch, 'GET', '/suggestions').length;
    await user.click(within(banner).getByRole('button', { name: 'Retry now' }));
    await waitFor(() => expect(screen.queryByText("Can't reach the SlotMind server.", { exact: false })).toBeNull());
    expect(calls(fetch, 'GET', '/suggestions').length).toBeGreaterThan(before);
  });

  it('the toast disappears after a few seconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    setup({ 'PUT /config/strategy': {} });
    await screen.findByRole('heading', { name: 'Re-schedule queue' });
    const u = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await u.click(screen.getByRole('button', { name: 'Room constraint' }));
    expect(await toast('Strategy: Room constraint')).toBeInTheDocument();
    await act(async () => { await vi.advanceTimersByTimeAsync(4000); });
    expect(screen.queryByText('Strategy: Room constraint')).toBeNull();
  });
});

describe('Desk actions', () => {
  it('accept: PATCH as STAFF, toast, then refresh', async () => {
    const { fetch, user } = setup({ 'PATCH /suggestions/SUG-001': sug({ status: 'ACCEPTED' }) });
    const c = await screen.findByRole('article', { name: 'Emily Watson' });
    const before = calls(fetch, 'GET', '/suggestions').length;
    await user.click(within(c).getByRole('button', { name: /accept/i }));
    expect(await toast('Emily Watson moved to the new time. Confirmation sent.')).toBeInTheDocument();
    expect(bodyOf(fetch, 'PATCH', '/suggestions/SUG-001')).toEqual({ status: 'ACCEPTED', actor: 'STAFF' });
    await waitFor(() => expect(calls(fetch, 'GET', '/suggestions').length).toBeGreaterThan(before));
  });

  it('accept 409 SLOT_UNAVAILABLE marks the card as conflicted', async () => {
    const { user } = setup({ 'PATCH /suggestions/SUG-001': () => jsonResponse({ error: 'SLOT_UNAVAILABLE', message: 'Slot SLT-010 is BOOKED' }, 409) });
    const c = await screen.findByRole('article', { name: 'Emily Watson' });
    await user.click(within(c).getByRole('button', { name: /accept/i }));
    expect(await within(c).findByRole('alert')).toHaveTextContent('Slot no longer free — get a new suggestion');
    expect(await toast('Slot no longer free — nothing was changed')).toBeInTheDocument();
    await waitFor(() => expect(within(c).getByRole('button', { name: /accept/i })).toBeDisabled());
  });

  it('a conflict flag is cleared once its suggestion is no longer pending', async () => {
    const d = state();
    const { user } = setup({
      'PATCH /suggestions/SUG-001': () => jsonResponse({ error: 'SLOT_UNAVAILABLE', message: 'taken' }, 409),
      'PUT /config/strategy': {},
    }, d);
    const c = await screen.findByRole('article', { name: 'Emily Watson' });
    await user.click(within(c).getByRole('button', { name: /accept/i }));
    expect(await within(c).findByRole('alert')).toBeInTheDocument();
    // Server replaced the stale suggestion with a fresh one; the next refresh must drop the conflict.
    d.suggestions = [sug({ id: 'SUG-002', createdAt: at('10:05') }), sug({ status: 'SUPERSEDED' })];
    await user.click(screen.getByRole('button', { name: 'Rule-based' })); // any action refreshes
    await waitFor(() => expect(within(c).queryByRole('alert')).toBeNull());
    expect(within(c).getByRole('button', { name: /accept/i })).toBeEnabled();
  });

  it('book: a non-slot error is shown as-is', async () => {
    const { user } = setup({
      'GET /slots': [slot({ id: 'SLT-050', startTime: at('15:00'), status: 'OPEN' })],
      'POST /appointments': () => jsonResponse({ error: 'VALIDATION', message: 'Duration too short for URGENT' }, 400),
    });
    const nav = await screen.findByRole('navigation', { name: 'Sections' });
    await user.click(within(nav).getByRole('button', { name: /Appointments/ }));
    await user.click(screen.getByRole('button', { name: /book appointment/i }));
    const form = screen.getByRole('heading', { name: 'Book an appointment' }).closest('form') as HTMLElement;
    await user.type(within(form).getByLabelText('Patient name'), 'Ava');
    await user.click(await within(form).findByRole('button', { name: /3:00 PM/ }));
    await user.click(within(form).getByRole('button', { name: 'Book appointment' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('Duration too short for URGENT');
  });

  it('accept 409 INVALID_STATE shows the server message', async () => {
    const { user } = setup({ 'PATCH /suggestions/SUG-001': () => jsonResponse({ error: 'INVALID_STATE', message: 'Suggestion is not pending' }, 409) });
    const c = await screen.findByRole('article', { name: 'Emily Watson' });
    await user.click(within(c).getByRole('button', { name: /accept/i }));
    expect(await within(c).findByRole('alert')).toHaveTextContent('Suggestion is not pending');
  });

  it('accept with a non-409 error only toasts', async () => {
    const { user } = setup({ 'PATCH /suggestions/SUG-001': () => jsonResponse({ error: 'X', message: 'Server exploded' }, 500) });
    const c = await screen.findByRole('article', { name: 'Emily Watson' });
    await user.click(within(c).getByRole('button', { name: /accept/i }));
    expect(await toast('Server exploded')).toBeInTheDocument();
    expect(within(c).queryByRole('alert')).toBeNull();
  });

  it('reject: PATCH REJECTED and toast', async () => {
    const { fetch, user } = setup({ 'PATCH /suggestions/SUG-001': sug({ status: 'REJECTED' }) });
    const c = await screen.findByRole('article', { name: 'Emily Watson' });
    await user.click(within(c).getByRole('button', { name: 'Reject' }));
    expect(await toast('Suggestion for Emily Watson rejected')).toBeInTheDocument();
    expect(bodyOf(fetch, 'PATCH', '/suggestions/SUG-001')).toEqual({ status: 'REJECTED', actor: 'STAFF' });
  });

  it('suggest another streams the reasoning into the card', async () => {
    const fresh = sug({ id: 'SUG-002', reasoning: 'Fresh option for you.' });
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    const enc = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      async start(ctl) {
        ctl.enqueue(enc.encode('event:status\ndata:Ranking candidates...\n\nevent:token\ndata:Fresh \n\nevent:token\ndata:option \n\n'));
        await gate;
        ctl.enqueue(enc.encode(`event:token\ndata:for you.\n\nevent:suggestion\ndata:${JSON.stringify(fresh)}\n\n`));
        ctl.close();
      },
    });
    const d = state();
    const { user } = setup({
      'POST /appointments/APT-001/suggest/stream': () => new Response(body, { status: 200 }),
    }, d);
    const c = await screen.findByRole('article', { name: 'Emily Watson' });
    await user.click(within(c).getByRole('button', { name: /suggest another/i }));
    await waitFor(() => expect(c).toHaveTextContent('Fresh option'));
    expect(c).toHaveTextContent('Streaming reasoning…');
    expect(within(c).getByRole('button', { name: /accept/i })).toBeDisabled();
    expect(c).toHaveTextContent('Provider offline');
    d.suggestions = [fresh, { ...d.suggestions[0], status: 'SUPERSEDED' }];
    release();
    expect(await toast('New suggestion ready')).toBeInTheDocument();
    await waitFor(() => expect(within(c).getByRole('button', { name: /accept/i })).toBeEnabled());
    expect(c).toHaveTextContent('Fresh option for you.');
    expect(c).toHaveTextContent('Provider offline');
  });

  it('suggest another falls back to the synchronous endpoint when the stream fails', async () => {
    const { fetch, user } = setup({
      'POST /appointments/APT-001/suggest/stream': () => jsonResponse({ error: 'X', message: 'no sse' }, 500),
      'POST /appointments/APT-001/suggest': sug({ id: 'SUG-003' }),
    });
    const c = await screen.findByRole('article', { name: 'Emily Watson' });
    await user.click(within(c).getByRole('button', { name: /suggest another/i }));
    expect(await toast('New suggestion ready')).toBeInTheDocument();
    expect(calls(fetch, 'POST', '/appointments/APT-001/suggest')).toHaveLength(1);
  });

  it('suggest another does not call the fallback when the stream already delivered a suggestion', async () => {
    const { fetch, user } = setup({
      'POST /appointments/APT-001/suggest/stream': () => new Response(streamOf([
        `event:suggestion\ndata:${JSON.stringify(sug({ id: 'SUG-004' }))}\n\nevent:error\ndata:late failure\n\n`,
      ]), { status: 200 }),
    });
    const c = await screen.findByRole('article', { name: 'Emily Watson' });
    await user.click(within(c).getByRole('button', { name: /suggest another/i }));
    await waitFor(() => expect(within(c).getByRole('button', { name: /suggest another/i })).toBeEnabled());
    expect(calls(fetch, 'POST', '/appointments/APT-001/suggest')).toHaveLength(0);
  });

  it('suggest another reports an error when both stream and fallback fail', async () => {
    const { user } = setup({
      'POST /appointments/APT-001/suggest/stream': () => jsonResponse({ error: 'X', message: 'no sse' }, 500),
      'POST /appointments/APT-001/suggest': () => jsonResponse({ error: 'INVALID_STATE', message: 'Appointment is completed' }, 409),
    });
    const c = await screen.findByRole('article', { name: 'Emily Watson' });
    await user.click(within(c).getByRole('button', { name: /suggest another/i }));
    expect(await toast('Appointment is completed')).toBeInTheDocument();
  });

  it('marking a provider offline from the rail toasts with the provider name', async () => {
    const { fetch, user } = setup({ 'PATCH /providers/PRV-002/status': provider({ id: 'PRV-002', name: 'Dr. James Lee', status: 'OFFLINE' }) });
    await screen.findByRole('article', { name: 'Emily Watson' });
    await user.click(screen.getByRole('button', { name: 'Mark Dr. James Lee offline' }));
    expect(await toast('Dr. James Lee is offline — AI advisor is drafting new times')).toBeInTheDocument();
    expect(bodyOf(fetch, 'PATCH', '/providers/PRV-002/status')).toEqual({ status: 'OFFLINE' });
  });

  it('provider status change to BUSY toasts the lower-case status', async () => {
    const { user } = setup({ 'PATCH /providers/PRV-001/status': {} });
    const nav = await screen.findByRole('navigation', { name: 'Sections' });
    await user.click(within(nav).getByRole('button', { name: /Providers/ }));
    await user.click(within(screen.getByRole('group', { name: 'Status of Dr. Sarah Chen' })).getByRole('button', { name: 'Busy' }));
    expect(await toast('Dr. Sarah Chen is busy')).toBeInTheDocument();
  });

  it('"Add to waitlist" from a no-option card posts the patient with the provider specialty and clinic', async () => {
    const d = state();
    d.suggestions = [sug({ recommendedSlot: null })];
    d.appointments[0] = appt({ status: 'RESCHEDULE_PENDING', providerId: 'PRV-002', contact: null });
    const { fetch, user } = setup({ 'POST /waitlist': waitlist() }, d);
    const c = await screen.findByRole('article', { name: 'Emily Watson' });
    await user.click(within(c).getByRole('button', { name: 'Add to waitlist' }));
    expect(await toast('Emily Watson added to the waitlist')).toBeInTheDocument();
    expect(bodyOf(fetch, 'POST', '/waitlist')).toEqual({
      patientName: 'Emily Watson', type: 'ROUTINE', specialty: 'PEDIATRIC', preferredWindow: 'ANY', clinicId: 'CLN-002',
    });
  });

  it('cancel slot from the day view', async () => {
    const { fetch, user } = setup({ 'PATCH /slots/SLT-001/status': slot({ status: 'CANCELLED' }) });
    const nav = await screen.findByRole('navigation', { name: 'Sections' });
    await user.click(within(nav).getByRole('button', { name: /Day view/ }));
    await user.click(screen.getByRole('button', { name: /^11:00 AM, Dr. Sarah Chen/ }));
    await user.click(screen.getByRole('button', { name: 'Cancel slot' }));
    expect(await toast('Slot cancelled — the affected patient is in the queue')).toBeInTheDocument();
    expect(bodyOf(fetch, 'PATCH', '/slots/SLT-001/status')).toEqual({ status: 'CANCELLED' });
  });

  it('complete an appointment, with an error toast on failure', async () => {
    const { user } = setup({ 'PATCH /appointments/APT-002/complete': () => jsonResponse({ error: 'INVALID_STATE', message: 'Cannot complete' }, 409) });
    const nav = await screen.findByRole('navigation', { name: 'Sections' });
    await user.click(within(nav).getByRole('button', { name: /Appointments/ }));
    await user.click(screen.getByRole('button', { name: 'Mark completed' }));
    expect(await toast('Cannot complete')).toBeInTheDocument();
  });

  it('book from the appointments form; a taken slot shows a friendly error', async () => {
    let taken = true;
    const { fetch, user } = setup({
      'GET /slots': [slot({ id: 'SLT-050', startTime: at('15:00'), status: 'OPEN' })],
      'POST /appointments': () => (taken
        ? jsonResponse({ error: 'SLOT_UNAVAILABLE', message: 'Slot SLT-050 is BOOKED' }, 409)
        : appt({ id: 'APT-009', patientName: 'Ava Stone' })),
    });
    const nav = await screen.findByRole('navigation', { name: 'Sections' });
    await user.click(within(nav).getByRole('button', { name: /Appointments/ }));
    await user.click(screen.getByRole('button', { name: /book appointment/i }));
    const form = screen.getByRole('heading', { name: 'Book an appointment' }).closest('form') as HTMLElement;
    await user.type(within(form).getByLabelText('Patient name'), 'Ava Stone');
    await user.click(await within(form).findByRole('button', { name: /3:00 PM/ }));
    await user.click(within(form).getByRole('button', { name: 'Book appointment' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('That slot was just taken. Pick another one.');

    taken = false;
    await user.click(within(form).getByRole('button', { name: /3:00 PM/ }));
    await user.click(within(form).getByRole('button', { name: 'Book appointment' }));
    expect(await toast('Booked Ava Stone')).toBeInTheDocument();
    expect(bodyOf(fetch, 'POST', '/appointments')).toEqual({ patientName: 'Ava Stone', type: 'ROUTINE', providerId: 'PRV-001', slotId: 'SLT-050' });
  });

  it('add to waitlist from the waitlist form; server errors stay in the form', async () => {
    let fail = true;
    const { user } = setup({
      'POST /waitlist': () => (fail ? jsonResponse({ error: 'VALIDATION', message: 'Name too long' }, 400) : waitlist()),
    });
    const nav = await screen.findByRole('navigation', { name: 'Sections' });
    await user.click(within(nav).getByRole('button', { name: /Waitlist/ }));
    await user.click(screen.getByRole('button', { name: /add to waitlist/i }));
    const form = screen.getByRole('heading', { name: 'Add to waitlist' }).closest('form') as HTMLElement;
    await user.type(within(form).getByLabelText('Patient name'), 'Zoe');
    await user.click(within(form).getByRole('button', { name: 'Add to waitlist' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('Name too long');
    fail = false;
    await user.click(within(form).getByRole('button', { name: 'Add to waitlist' }));
    expect(await toast('Zoe added to the waitlist')).toBeInTheDocument();
  });

  it('waitlist offer accept / decline', async () => {
    const { fetch, user } = setup({
      'POST /waitlist/WL-001/accept': () => jsonResponse({ error: 'SLOT_UNAVAILABLE', message: 'gone' }, 409),
      'POST /waitlist/WL-001/decline': {},
    });
    const nav = await screen.findByRole('navigation', { name: 'Sections' });
    await user.click(within(nav).getByRole('button', { name: /Waitlist/ }));
    await user.click(screen.getByRole('button', { name: 'Accept' }));
    expect(await toast('Slot no longer free — nothing was changed')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Decline' }));
    expect(await toast('Offer declined')).toBeInTheDocument();
    expect(calls(fetch, 'POST', '/waitlist/WL-001/decline')).toHaveLength(1);
  });

  it('waitlist offer accept success', async () => {
    const { user } = setup({ 'POST /waitlist/WL-001/accept': {} });
    const nav = await screen.findByRole('navigation', { name: 'Sections' });
    await user.click(within(nav).getByRole('button', { name: /Waitlist/ }));
    await user.click(screen.getByRole('button', { name: 'Accept' }));
    expect(await toast('Offer accepted — appointment booked')).toBeInTheDocument();
  });

  it('"Suggest new time" from the appointments tab jumps to the queue', async () => {
    const { user } = setup({ 'POST /appointments/APT-002/suggest/stream': () => new Promise<Response>(() => undefined) });
    const nav = await screen.findByRole('navigation', { name: 'Sections' });
    await user.click(within(nav).getByRole('button', { name: /Appointments/ }));
    await user.click(screen.getByRole('button', { name: 'Suggest new time' }));
    expect(screen.getByRole('heading', { level: 1, name: 'Re-schedule queue' })).toBeInTheDocument();
    const c = await screen.findByRole('article', { name: 'Liam Ortiz' });
    expect(c).toHaveTextContent('Manual');
  });
});
