import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ActivityFeed, QueueView, latestSuggestionByAppt } from './Queue';
import type { Ctx } from './ctx';
import { appt, at, deskData, makeCtx, provider, slot, sug } from './test/fixtures';

const pendingAppt = (p = {}) => appt({ status: 'RESCHEDULE_PENDING', ...p });

function renderQueue(ctx: Ctx) {
  const user = userEvent.setup();
  const r = render(<QueueView ctx={ctx} />);
  return { user, ...r };
}
const card = (name: string) => screen.getByRole('article', { name });
const btn = (scope: HTMLElement, name: RegExp | string) => within(scope).getByRole('button', { name });

function expectAllButtonsNamed() {
  for (const b of screen.getAllByRole('button')) expect(b).toHaveAccessibleName();
}

describe('latestSuggestionByAppt', () => {
  it('ignores SUPERSEDED and picks the newest per appointment', () => {
    const r = latestSuggestionByAppt([
      sug({ id: 'SUG-003', appointmentId: 'APT-001', status: 'SUPERSEDED', createdAt: at('12:00') }),
      sug({ id: 'SUG-002', appointmentId: 'APT-001', status: 'PENDING', createdAt: at('11:00') }),
      sug({ id: 'SUG-001', appointmentId: 'APT-001', status: 'REJECTED', createdAt: at('10:00') }),
      sug({ id: 'SUG-004', appointmentId: 'APT-002', status: 'ACCEPTED', createdAt: at('09:00') }),
    ]);
    expect(r['APT-001'].id).toBe('SUG-002');
    expect(r['APT-002'].id).toBe('SUG-004');
    expect(Object.keys(r).sort()).toEqual(['APT-001', 'APT-002']);
  });

  it('returns nothing for an appointment with only superseded suggestions', () => {
    expect(latestSuggestionByAppt([sug({ status: 'SUPERSEDED' })])).toEqual({});
  });

  // GET /suggestions is ordered "createdAt desc, id desc" (newest first), and createdAt carries
  // microseconds. Reject + "Suggest another" easily lands in the same second.
  it.each([
    ['newest first (server order)', true],
    ['oldest first', false],
  ])('resolves two suggestions created in the same second to the newer one (%s)', (_n, newestFirst) => {
    const older = sug({ id: 'SUG-007', status: 'REJECTED', createdAt: '2030-01-15T10:00:00.104512' });
    const newer = sug({ id: 'SUG-008', status: 'PENDING', createdAt: '2030-01-15T10:00:00.871003' });
    const list = newestFirst ? [newer, older] : [older, newer];
    expect(latestSuggestionByAppt(list)['APT-001'].id).toBe('SUG-008');
  });

  it('separates timestamps that differ only below the millisecond', () => {
    const older = sug({ id: 'SUG-020', createdAt: '2030-01-15T10:00:00.123400' });
    const newer = sug({ id: 'SUG-019', createdAt: '2030-01-15T10:00:00.123401' });
    expect(latestSuggestionByAppt([newer, older])['APT-001'].id).toBe('SUG-019');
    expect(latestSuggestionByAppt([older, newer])['APT-001'].id).toBe('SUG-019');
  });

  it('falls back to the id sequence (numerically) for identical timestamps', () => {
    const a = sug({ id: 'SUG-999', createdAt: at('10:00') });
    const b = sug({ id: 'SUG-1000', createdAt: at('10:00') });
    expect(latestSuggestionByAppt([b, a])['APT-001'].id).toBe('SUG-1000');
    expect(latestSuggestionByAppt([a, b])['APT-001'].id).toBe('SUG-1000');
  });
});

describe('QueueView card states', () => {
  it('generating: pending appointment without a suggestion shows a busy skeleton', () => {
    renderQueue(makeCtx(deskData({ appointments: [pendingAppt()] })));
    const c = card('Emily Watson');
    expect(c.querySelector('[aria-busy="true"]')).not.toBeNull();
    expect(c).toHaveTextContent('AI advisor is checking open slots');
    expect(within(c).queryByRole('button', { name: /accept/i })).toBeNull();
  });

  it('generating copy for a non-AI strategy', () => {
    renderQueue(makeCtx(deskData({ appointments: [pendingAppt()], strategy: { strategy: 'RULE_BASED', available: [] } })));
    expect(card('Emily Watson')).toHaveTextContent('Finding the nearest matching open slot');
  });

  it('generating also when the latest suggestion was already ACCEPTED (appointment disrupted again)', () => {
    renderQueue(makeCtx(deskData({ appointments: [pendingAppt()], suggestions: [sug({ status: 'ACCEPTED' })] })));
    expect(card('Emily Watson').querySelector('[aria-busy="true"]')).not.toBeNull();
  });

  it('ready: shows was/suggested/confidence/reasoning and wires the buttons', async () => {
    const s = sug();
    const ctx = makeCtx(deskData({ appointments: [pendingAppt()], suggestions: [s] }));
    const { user } = renderQueue(ctx);
    const c = card('Emily Watson');
    expect(c).toHaveTextContent('11:00 AM');
    expect(c).toHaveTextContent('2:00 PM');
    expect(c).toHaveTextContent('Dr. James Lee');
    expect(c).toHaveTextContent('Different doctor');
    expect(c).toHaveTextContent('88%');
    expect(c).toHaveTextContent('High');
    expect(c).toHaveTextContent('Read to patient');
    expect(c).toHaveTextContent(s.reasoning);
    expect(c).toHaveTextContent('AI advisor');
    expect(c).toHaveTextContent('Provider offline');
    expect(within(c).getByRole('link', { name: 'Patient view' })).toHaveAttribute('href', '#/portal/APT-001');

    await user.click(btn(c, /accept/i));
    expect(ctx.accept).toHaveBeenCalledWith(s);
    await user.click(btn(c, 'Reject'));
    expect(ctx.reject).toHaveBeenCalledWith(s);
    await user.click(btn(c, /suggest another/i));
    expect(ctx.suggestAnother).toHaveBeenCalledWith('APT-001');
    expectAllButtonsNamed();
  });

  it('ready: rule-based fallback is labelled and shown as template text with a rule score', () => {
    renderQueue(makeCtx(deskData({
      appointments: [pendingAppt()],
      suggestions: [sug({ fallbackUsed: true, strategy: 'RULE_BASED', confidence: 0.7, recommendedSlot: slot({ id: 'SLT-011', startTime: at('15:00'), status: 'OPEN' }) })],
    })));
    const c = card('Emily Watson');
    expect(c).toHaveTextContent('Rule-based fallback · AI unavailable');
    expect(c).toHaveTextContent('Template text · rephrase for the patient');
    expect(c).toHaveTextContent('Rule score');
    expect(c).toHaveTextContent('Medium');
    expect(c).not.toHaveTextContent('Different doctor'); // same provider
  });

  it('ready: cross-clinic suggestion shows the clinic', () => {
    renderQueue(makeCtx(deskData({
      appointments: [pendingAppt()],
      suggestions: [sug({ crossClinic: true, confidence: 0.4, recommendedSlot: slot({ id: 'SLT-12', status: 'OPEN', clinicName: 'Uptown', providerId: 'PRV-003', providerName: 'Dr. Ana Ruiz' }) })],
    })));
    const c = card('Emily Watson');
    expect(c).toHaveTextContent('Other clinic');
    expect(c).toHaveTextContent('Uptown');
    expect(c).toHaveTextContent('Low');
  });

  it('streaming: shows streamed text, status, and disables actions', () => {
    const ctx = makeCtx(deskData({ appointments: [pendingAppt()], suggestions: [sug({ status: 'REJECTED' })] }), {
      streams: { 'APT-001': { status: 'Ranking candidates...', text: 'We can offer ' } },
    });
    renderQueue(ctx);
    const c = card('Emily Watson');
    expect(c).toHaveTextContent('We can offer');
    expect(c).toHaveTextContent('Streaming reasoning…');
    expect(c).not.toHaveTextContent('Still needs a new time');
    expect(btn(c, 'Reject')).toBeDisabled();
    expect(btn(c, /suggest another/i)).toBeDisabled();
    expect(btn(c, /accept/i)).toBeDisabled();
  });

  it('streaming before any token shows the stream status', () => {
    renderQueue(makeCtx(deskData({ appointments: [pendingAppt()] }), { streams: { 'APT-001': { status: 'Collecting legal open slots', text: '' } } }));
    expect(card('Emily Watson')).toHaveTextContent('Collecting legal open slots');
  });

  it('streaming with the final suggestion shows its slot', () => {
    const fresh = sug({ id: 'SUG-002', recommendedSlot: slot({ id: 'SLT-20', startTime: at('16:30'), status: 'OPEN' }) });
    renderQueue(makeCtx(deskData({ appointments: [pendingAppt()] }), { streams: { 'APT-001': { status: '', text: 'done', suggestion: fresh } } }));
    const c = card('Emily Watson');
    expect(c).toHaveTextContent('4:30 PM');
    expect(c).not.toHaveTextContent('Streaming reasoning…');
  });

  it('no option: explains and offers waitlist / try again', async () => {
    const ctx = makeCtx(deskData({
      appointments: [pendingAppt()],
      suggestions: [sug({ recommendedSlot: null, confidence: null, reasoning: 'Nothing fits today; we can add you to the waitlist.' })],
    }));
    const { user } = renderQueue(ctx);
    const c = card('Emily Watson');
    expect(c).toHaveTextContent('No open slot fits today');
    expect(c).toHaveTextContent('Nothing fits today');
    await user.click(btn(c, 'Add to waitlist'));
    expect(ctx.addApptToWaitlist).toHaveBeenCalledWith(expect.objectContaining({ id: 'APT-001' }));
    await user.click(btn(c, 'Try again'));
    expect(ctx.suggestAnother).toHaveBeenCalledWith('APT-001');
  });

  it('no option: waitlist button disabled while busy', () => {
    renderQueue(makeCtx(deskData({ appointments: [pendingAppt()], suggestions: [sug({ recommendedSlot: null })] }), { busy: { 'wl-APT-001': true } }));
    expect(btn(card('Emily Watson'), 'Add to waitlist')).toBeDisabled();
  });

  it('rejected: shows what was rejected and offers the next step', async () => {
    const ctx = makeCtx(deskData({ appointments: [pendingAppt()], suggestions: [sug({ status: 'REJECTED' })] }));
    const { user } = renderQueue(ctx);
    const c = card('Emily Watson');
    expect(c).toHaveTextContent('Still needs a new time');
    expect(c).toHaveTextContent('You rejected 2:00 PM with Dr. James Lee');
    await user.click(btn(c, 'Suggest another time'));
    expect(ctx.suggestAnother).toHaveBeenCalledWith('APT-001');
    await user.click(btn(c, 'Add to waitlist'));
    expect(ctx.addApptToWaitlist).toHaveBeenCalled();
  });

  it('conflict: shows an alert and blocks Accept but allows Suggest another', () => {
    const s = sug();
    renderQueue(makeCtx(deskData({ appointments: [pendingAppt()], suggestions: [s] }), {
      conflicts: { [s.id]: 'Slot no longer free — get a new suggestion' },
    }));
    const c = card('Emily Watson');
    expect(within(c).getByRole('alert')).toHaveTextContent('Slot no longer free — get a new suggestion');
    expect(btn(c, /accept/i)).toBeDisabled();
    expect(btn(c, 'Reject')).toBeEnabled();
    expect(btn(c, /suggest another/i)).toBeEnabled();
  });

  it('busy: disables all actions of that suggestion', () => {
    const s = sug();
    renderQueue(makeCtx(deskData({ appointments: [pendingAppt()], suggestions: [s] }), { busy: { [s.id]: true } }));
    const c = card('Emily Watson');
    for (const n of ['Reject', /suggest another/i, /accept/i]) expect(btn(c, n)).toBeDisabled();
  });

  it('after reject + suggest another in the same second, the card shows the new PENDING suggestion', () => {
    // Server order: newest first.
    const rejected = sug({ id: 'SUG-001', status: 'REJECTED', createdAt: '2030-01-15T10:00:00.100000' });
    const fresh = sug({ id: 'SUG-002', status: 'PENDING', createdAt: '2030-01-15T10:00:00.900000', recommendedSlot: slot({ id: 'SLT-30', startTime: at('15:30'), status: 'OPEN' }) });
    renderQueue(makeCtx(deskData({ appointments: [pendingAppt()], suggestions: [fresh, rejected] })));
    const c = card('Emily Watson');
    expect(c).not.toHaveTextContent('Still needs a new time');
    expect(c).toHaveTextContent('3:30 PM');
    expect(btn(c, /accept/i)).toBeEnabled();
  });
});

describe('QueueView trigger badge and filters', () => {
  const data = () => deskData({
    appointments: [
      pendingAppt({ id: 'APT-001', patientName: 'Emily Watson', type: 'ROUTINE' }),
      pendingAppt({ id: 'APT-002', patientName: 'Liam Ortiz', type: 'URGENT' }),
      pendingAppt({ id: 'APT-003', patientName: 'Mia Chen', type: 'FOLLOW_UP' }),
      appt({ id: 'APT-004', patientName: 'Confirmed Person' }),
    ],
    suggestions: [
      sug({ id: 'SUG-001', appointmentId: 'APT-001', triggerReason: 'PROVIDER_OFFLINE' }),
      sug({ id: 'SUG-002', appointmentId: 'APT-002', patientName: 'Liam Ortiz', triggerReason: 'PROVIDER_OFFLINE' }),
      sug({ id: 'SUG-003', appointmentId: 'APT-003', patientName: 'Mia Chen', triggerReason: 'SLOT_CANCELLED' }),
    ],
  });

  it('chips show counts, toggle aria-pressed, and filter cards', async () => {
    const { user } = renderQueue(makeCtx(data()));
    const group = screen.getByRole('group', { name: 'Filter by trigger' });
    const chip = (n: RegExp) => within(group).getByRole('button', { name: n });
    expect(chip(/^All · 3$/)).toHaveAttribute('aria-pressed', 'true');
    expect(chip(/^Provider offline · 2$/)).toHaveAttribute('aria-pressed', 'false');
    expect(chip(/^Slot cancelled · 1$/)).toBeInTheDocument();
    expect(chip(/^Manual · 0$/)).toBeInTheDocument();
    expect(screen.getAllByRole('article')).toHaveLength(3);

    await user.click(chip(/^Slot cancelled/));
    expect(chip(/^Slot cancelled/)).toHaveAttribute('aria-pressed', 'true');
    expect(chip(/^All/)).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getAllByRole('article').map((a) => a.getAttribute('aria-label'))).toEqual(['Mia Chen']);

    await user.click(chip(/^Manual/));
    expect(screen.queryAllByRole('article')).toHaveLength(0);
    expect(screen.getByText('Nothing matches this filter')).toBeInTheDocument();
  });

  it('sorts urgent first, then follow-up, then routine', () => {
    renderQueue(makeCtx(data()));
    expect(screen.getAllByRole('article').map((a) => a.getAttribute('aria-label'))).toEqual(['Liam Ortiz', 'Mia Chen', 'Emily Watson']);
  });

  it('keeps the original trigger badge and filter while "Suggest another" streams and after it returns', async () => {
    const d = data();
    d.suggestions[0] = sug({ id: 'SUG-001', appointmentId: 'APT-001', triggerReason: 'PROVIDER_OFFLINE', status: 'REJECTED' });
    const ctx = makeCtx(d, { streams: { 'APT-001': { status: 'Ranking', text: 'We can' } } });
    const { user, rerender } = renderQueue(ctx);
    await user.click(screen.getByRole('button', { name: /^Provider offline/ }));
    let c = card('Emily Watson');
    expect(c).toHaveTextContent('Provider offline');
    expect(c).not.toHaveTextContent('Manual');

    // Stream finished: server keeps the trigger (decision 4) for the new suggestion.
    const next = sug({ id: 'SUG-009', appointmentId: 'APT-001', triggerReason: 'PROVIDER_OFFLINE', createdAt: at('10:00', '.999') });
    rerender(<QueueView ctx={{ ...ctx, streams: { 'APT-001': { status: '', text: 'x', suggestion: next } } }} />);
    c = card('Emily Watson');
    expect(c).toHaveTextContent('Provider offline');

    // After the refresh the stream is gone, and the new suggestion is in the list.
    rerender(<QueueView ctx={{ ...ctx, streams: {}, data: { ...d, suggestions: [next, ...d.suggestions] } }} />);
    c = card('Emily Watson');
    expect(c).toHaveTextContent('Provider offline');
    expect(within(c).getByRole('button', { name: /accept/i })).toBeEnabled();
    expect(screen.getByRole('button', { name: /^Provider offline · 2$/ })).toHaveAttribute('aria-pressed', 'true');
  });

  it('a stream on a confirmed appointment (manual request) shows a Manual card', () => {
    renderQueue(makeCtx(data(), { streams: { 'APT-004': { status: 'Starting…', text: '' } } }));
    const c = card('Confirmed Person');
    expect(c).toHaveTextContent('Manual');
    expect(screen.getByRole('button', { name: /^Manual · 1$/ })).toBeInTheDocument();
  });
});

describe('QueueView empty state, history and rail', () => {
  it('empty queue offers the demo action for Dr. Sarah Chen', async () => {
    const ctx = makeCtx(deskData());
    const { user } = renderQueue(ctx);
    expect(screen.getByText('No patients need a new time')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Try it: mark Dr. Sarah Chen offline' }));
    expect(ctx.setProviderStatus).toHaveBeenCalledWith('PRV-001', 'OFFLINE');
  });

  it('no demo action when the demo provider is already offline', () => {
    renderQueue(makeCtx(deskData({ providers: [provider({ status: 'OFFLINE' })] })));
    expect(screen.queryByRole('button', { name: /try it/i })).toBeNull();
  });

  it('lists resolved suggestions newest first (max 8)', () => {
    const resolved = Array.from({ length: 10 }, (_, i) => sug({
      id: `SUG-1${i}`, appointmentId: `APT-1${i}`, patientName: `Patient ${i}`, status: i % 2 ? 'ACCEPTED' : 'REJECTED',
      resolvedAt: at(`1${i}:00`),
    }));
    renderQueue(makeCtx(deskData({ suggestions: resolved })));
    const h = screen.getByRole('heading', { name: 'Resolved today' });
    const rows = h.parentElement!.querySelectorAll('.hist-row');
    expect(rows).toHaveLength(8);
    expect(rows[0]).toHaveTextContent('Patient 9');
    expect(rows[0]).toHaveTextContent('Accepted');
    expect(rows[0]).toHaveTextContent('Moved to 2:00 PM with Dr. James Lee');
    expect(rows[1]).toHaveTextContent('Rejected');
    expect(rows[1]).toHaveTextContent('Declined 2:00 PM with Dr. James Lee');
  });

  it.each([
    ['AI', 'AI advisor picks times'],
    ['ROOM_CONSTRAINT', 'Room constraint: same provider first'],
    ['RULE_BASED', 'Rule-based: the same provider'],
  ])('strategy note for %s', (strategy, text) => {
    renderQueue(makeCtx(deskData({ strategy: { strategy, available: [] } })));
    expect(screen.getByText(new RegExp(text))).toBeInTheDocument();
  });

  it('rail toggles provider status with named buttons', async () => {
    const ctx = makeCtx(deskData({
      providers: [provider(), provider({ id: 'PRV-002', name: 'Dr. James Lee', status: 'OFFLINE' })],
      appointments: [appt(), appt({ id: 'APT-002', status: 'RESCHEDULED' }), appt({ id: 'APT-003', status: 'COMPLETED' })],
    }));
    const { user } = renderQueue(ctx);
    await user.click(screen.getByRole('button', { name: 'Mark Dr. Sarah Chen offline' }));
    expect(ctx.setProviderStatus).toHaveBeenCalledWith('PRV-001', 'OFFLINE');
    await user.click(screen.getByRole('button', { name: 'Mark Dr. James Lee available' }));
    expect(ctx.setProviderStatus).toHaveBeenCalledWith('PRV-002', 'AVAILABLE');
    const rail = screen.getByRole('heading', { name: 'Providers today' }).closest('section')!;
    expect(rail).toHaveTextContent('2 appts');
    expect(rail).toHaveTextContent('0 appts');
    expectAllButtonsNamed();
  });

  it('rail button disabled while that provider is busy', () => {
    renderQueue(makeCtx(deskData(), { busy: { 'p-PRV-001': true } }));
    expect(screen.getByRole('button', { name: 'Mark Dr. Sarah Chen offline' })).toBeDisabled();
  });
});

describe('ActivityFeed', () => {
  it('shows an empty message', () => {
    render(<ActivityFeed ctx={makeCtx(deskData())} />);
    expect(screen.getByText('No activity yet.')).toBeInTheDocument();
  });

  it('lists events newest first with times', () => {
    render(<ActivityFeed ctx={makeCtx(deskData({
      activity: [
        { id: 1, at: at('09:00'), kind: 'INFO', message: 'first' },
        { id: 2, at: at('09:30'), kind: 'agent', message: 'second' },
        { id: 3, at: at('09:15'), kind: 'UNKNOWN', message: 'middle' },
      ],
    }))} />);
    const items = within(screen.getByRole('list')).getAllByRole('listitem');
    expect(items.map((li) => li.textContent)).toEqual(['9:30 AMsecond', '9:15 AMmiddle', '9:00 AMfirst']);
  });
});
