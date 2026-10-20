import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Portal } from './Portal';
import { appt, at, jsonResponse, routeFetch, slot, sug } from './test/fixtures';

const pendingView = () => ({
  appointment: appt({ status: 'RESCHEDULE_PENDING', slot: slot({ startTime: at('11:00'), durationMinutes: 30 }) }),
  suggestions: [sug()],
});

function setup(routes: Record<string, unknown>) {
  const fetch = routeFetch(routes);
  const user = userEvent.setup();
  render(<Portal id="APT-001" />);
  return { fetch, user };
}
const bodyOf = (fetch: ReturnType<typeof routeFetch>, method: string, path: string) => {
  const c = fetch.mock.calls.find(([u, i]) => String(u) === '/api' + path && i?.method === method);
  return c?.[1]?.body ? JSON.parse(String(c[1].body)) : c ? null : undefined;
};

describe('Portal', () => {
  it('shows the appointment and the proposed new time', async () => {
    setup({ 'GET /portal/appointments/APT-001': pendingView() });
    expect(await screen.findByRole('heading', { name: 'Hi Emily' })).toBeInTheDocument();
    expect(screen.getByText('Reschedule pending')).toBeInTheDocument();
    expect(screen.getByText('Tuesday, January 15')).toBeInTheDocument();
    expect(screen.getByText('11:00 AM – 11:30 AM')).toHaveStyle({ textDecoration: 'line-through' });
    expect(screen.getByText('Dr. Sarah Chen · Downtown · Room 1')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'We have a new time for you' })).toBeInTheDocument();
    expect(screen.getByText('2:00 PM')).toBeInTheDocument();
    expect(screen.getByText(sug().reasoning)).toBeInTheDocument();
    for (const b of screen.getAllByRole('button')) expect(b).toHaveAccessibleName();
    expect(screen.getByRole('link', { name: 'Front desk' })).toHaveAttribute('href', '#/');
  });

  it('accepting sends PATCH /suggestions/{id} as PATIENT and confirms', async () => {
    const { fetch, user } = setup({
      'GET /portal/appointments/APT-001': pendingView(),
      'PATCH /suggestions/SUG-001': sug({ status: 'ACCEPTED' }),
    });
    await user.click(await screen.findByRole('button', { name: 'Accept this time' }));
    expect(await screen.findByText(/your appointment has been moved/)).toBeInTheDocument();
    expect(bodyOf(fetch, 'PATCH', '/suggestions/SUG-001')).toEqual({ status: 'ACCEPTED', actor: 'PATIENT' });
    // reloads after the action
    expect(fetch.mock.calls.filter(([u]) => String(u) === '/api/portal/appointments/APT-001').length).toBeGreaterThanOrEqual(2);
  });

  it('a slot conflict on accept shows a friendly message', async () => {
    const { user } = setup({
      'GET /portal/appointments/APT-001': pendingView(),
      'PATCH /suggestions/SUG-001': () => jsonResponse({ error: 'SLOT_UNAVAILABLE', message: 'Slot SLT-010 is BOOKED' }, 409),
    });
    await user.click(await screen.findByRole('button', { name: 'Accept this time' }));
    expect(await screen.findByText('Sorry, that time was just taken. We’ll find you another one.')).toBeInTheDocument();
  });

  it('other errors show the server message', async () => {
    const { user } = setup({
      'GET /portal/appointments/APT-001': pendingView(),
      'PATCH /suggestions/SUG-001': () => jsonResponse({ error: 'INVALID_STATE', message: 'Suggestion is not pending' }, 409),
    });
    await user.click(await screen.findByRole('button', { name: 'Not suitable' }));
    expect(await screen.findByText('Suggestion is not pending')).toBeInTheDocument();
  });

  it('"Not suitable" rejects as PATIENT', async () => {
    const { fetch, user } = setup({
      'GET /portal/appointments/APT-001': pendingView(),
      'PATCH /suggestions/SUG-001': sug({ status: 'REJECTED' }),
    });
    await user.click(await screen.findByRole('button', { name: 'Not suitable' }));
    expect(await screen.findByText(/front desk will contact you/)).toBeInTheDocument();
    expect(bodyOf(fetch, 'PATCH', '/suggestions/SUG-001')).toEqual({ status: 'REJECTED', actor: 'PATIENT' });
  });

  it('a confirmed appointment can request a different time', async () => {
    const { fetch, user } = setup({
      'GET /portal/appointments/APT-001': { appointment: appt(), suggestions: [] },
      'POST /portal/appointments/APT-001/reschedule': {},
    });
    expect(await screen.findByText('Confirmed')).toBeInTheDocument();
    expect(screen.getByText('11:00 AM – 11:30 AM')).not.toHaveStyle({ textDecoration: 'line-through' });
    await user.click(screen.getByRole('button', { name: 'Request a different time' }));
    expect(await screen.findByText(/Request sent/)).toBeInTheDocument();
    expect(bodyOf(fetch, 'POST', '/portal/appointments/APT-001/reschedule')).toBeNull();
  });

  it('pending without a suggestion yet shows a busy message', async () => {
    setup({ 'GET /portal/appointments/APT-001': { appointment: appt({ status: 'RESCHEDULE_PENDING' }), suggestions: [sug({ status: 'REJECTED' })] } });
    expect(await screen.findByText('We’re finding a new time for you…')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request a different time' })).toBeNull();
  });

  it('a pending suggestion without a slot shows its reasoning only', async () => {
    setup({ 'GET /portal/appointments/APT-001': { appointment: appt({ status: 'RESCHEDULE_PENDING' }), suggestions: [sug({ recommendedSlot: null, reasoning: 'No slot today, we will call you.' })] } });
    expect(await screen.findByText('No slot today, we will call you.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Accept this time' })).toBeNull();
  });

  it('completed appointment offers no actions', async () => {
    setup({ 'GET /portal/appointments/APT-001': { appointment: appt({ status: 'COMPLETED' }), suggestions: [] } });
    expect(await screen.findByText('Completed')).toBeInTheDocument();
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('404 shows not found', async () => {
    setup({});
    expect(await screen.findByRole('alert')).toHaveTextContent('We couldn’t find this appointment.');
  });

  it('network failure shows a retry hint', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('offline'); }));
    render(<Portal id="APT-001" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('We can’t reach the clinic right now.');
  });

  it('polls every 5 seconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { fetch } = setup({ 'GET /portal/appointments/APT-001': pendingView() });
    await screen.findByRole('heading', { name: 'Hi Emily' });
    const n = fetch.mock.calls.length;
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetch.mock.calls.length).toBe(n + 1);
  });
});
