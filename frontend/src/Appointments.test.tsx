import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { AppointmentsView } from './Appointments';
import { appt, at, deskData, makeCtx, provider, slot } from './test/fixtures';
import type { Ctx } from './ctx';

const appts = () => [
  appt({ id: 'APT-002', patientName: 'Liam Ortiz', status: 'RESCHEDULE_PENDING', type: 'URGENT', slot: slot({ id: 'SLT-2', startTime: at('10:00') }) }),
  appt({ id: 'APT-001', patientName: 'Emily Watson', status: 'CONFIRMED', slot: slot({ id: 'SLT-1', startTime: at('09:00') }) }),
  appt({ id: 'APT-003', patientName: 'Mia Chen', status: 'COMPLETED', contact: null, slot: slot({ id: 'SLT-3', startTime: at('11:00') }) }),
  appt({ id: 'APT-004', patientName: 'Noah Park', status: 'RESCHEDULED', slot: slot({ id: 'SLT-4', startTime: at('08:00') }) }),
];

function setup(p: Partial<Ctx> = {}, data = deskData({ appointments: appts() })) {
  const ctx = makeCtx(data, p);
  const user = userEvent.setup();
  render(<AppointmentsView ctx={ctx} />);
  return { ctx, user };
}
const rowNames = () => screen.getAllByText(/^(Liam Ortiz|Emily Watson|Mia Chen|Noah Park)$/).map((e) => e.textContent);
const rowOf = (name: string) => screen.getByText(name).closest('.trow') as HTMLElement;

describe('AppointmentsView list', () => {
  it('lists all appointments sorted by start time', () => {
    setup();
    expect(rowNames()).toEqual(['Noah Park', 'Emily Watson', 'Liam Ortiz', 'Mia Chen']);
    expect(rowOf('Emily Watson')).toHaveTextContent('APT-001 · +15550100');
    expect(rowOf('Emily Watson')).toHaveTextContent('Confirmed');
    expect(rowOf('Emily Watson')).toHaveTextContent('9:00 AM');
    expect(rowOf('Liam Ortiz')).toHaveTextContent('Urgent');
  });

  it('status chips show counts and filter via GET /appointments?status=', async () => {
    const spy = vi.spyOn(api, 'listAppointments').mockResolvedValue([appts()[1]]);
    const { user } = setup();
    const group = screen.getByRole('group', { name: 'Filter by status' });
    const chip = (n: RegExp) => within(group).getByRole('button', { name: n });
    expect(chip(/^All · 4$/)).toHaveAttribute('aria-pressed', 'true');
    expect(chip(/^Confirmed · 1$/)).toBeInTheDocument();
    expect(chip(/^Reschedule pending · 1$/)).toBeInTheDocument();
    expect(chip(/^Rescheduled · 1$/)).toBeInTheDocument();
    expect(chip(/^Completed · 1$/)).toBeInTheDocument();
    await user.click(chip(/^Confirmed/));
    expect(chip(/^Confirmed/)).toHaveAttribute('aria-pressed', 'true');
    expect(chip(/^All/)).toHaveAttribute('aria-pressed', 'false');
    expect(spy).toHaveBeenCalledWith('CONFIRMED');
    await waitFor(() => expect(rowNames()).toEqual(['Emily Watson']));
  });

  it('falls back to filtering locally when the server filter fails', async () => {
    vi.spyOn(api, 'listAppointments').mockRejectedValue(new Error('down'));
    const { user } = setup();
    await user.click(screen.getByRole('button', { name: /^Completed · / }));
    await waitFor(() => expect(rowNames()).toEqual(['Mia Chen']));
  });

  it('shows an empty message when nothing matches', async () => {
    vi.spyOn(api, 'listAppointments').mockResolvedValue([]);
    const { user } = setup();
    await user.click(screen.getByRole('button', { name: /^Rescheduled · / }));
    expect(await screen.findByText('No appointments with this status.')).toBeInTheDocument();
  });

  it('row actions depend on status and call ctx', async () => {
    const { ctx, user } = setup();
    const confirmed = rowOf('Emily Watson');
    await user.click(within(confirmed).getByRole('button', { name: 'Suggest new time' }));
    expect(ctx.suggestAnother).toHaveBeenCalledWith('APT-001');
    expect(ctx.goTab).toHaveBeenCalledWith('queue');
    await user.click(within(confirmed).getByRole('button', { name: 'Mark completed' }));
    expect(ctx.complete).toHaveBeenCalledWith('APT-001');
    expect(within(confirmed).getByRole('link', { name: 'Patient view' })).toHaveAttribute('href', '#/portal/APT-001');

    const pending = rowOf('Liam Ortiz');
    expect(within(pending).queryByRole('button', { name: 'Suggest new time' })).toBeNull();
    await user.click(within(pending).getByRole('button', { name: 'Open in queue' }));
    expect(ctx.goTab).toHaveBeenLastCalledWith('queue');

    const done = rowOf('Mia Chen');
    expect(within(done).queryByRole('button')).toBeNull();
    expect(within(rowOf('Noah Park')).getByRole('button', { name: 'Mark completed' })).toBeEnabled();
  });

  it('disables Mark completed while busy', () => {
    setup({ busy: { 'c-APT-001': true } });
    expect(within(rowOf('Emily Watson')).getByRole('button', { name: 'Mark completed' })).toBeDisabled();
  });
});

describe('BookingForm', () => {
  const providers = [
    provider({ id: 'PRV-005', name: 'Dr. Off Line', status: 'OFFLINE' }),
    provider({ id: 'PRV-001', name: 'Dr. Sarah Chen' }),
    provider({ id: 'PRV-002', name: 'Dr. James Lee', status: 'BUSY' }),
  ];
  const openSlots = (pid: string) => [
    slot({ id: `${pid}-late`, startTime: at('15:00'), status: 'OPEN', durationMinutes: 45, roomName: 'Room 2' }),
    slot({ id: `${pid}-early`, startTime: at('14:00'), status: 'OPEN', roomName: null }),
    slot({ id: `${pid}-past`, startTime: '2000-01-01T09:00:00', status: 'OPEN' }),
    slot({ id: `${pid}-booked`, startTime: at('16:00'), status: 'BOOKED' }),
  ];

  async function openForm(p: Partial<Ctx> = {}) {
    const listSlots = vi.spyOn(api, 'listSlots').mockImplementation(async (pid) => openSlots(pid!));
    const r = setup(p, deskData({ appointments: appts(), providers }));
    const toggle = screen.getByRole('button', { name: /book appointment/i });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await r.user.click(toggle);
    expect(screen.getByRole('button', { name: /close form/i })).toHaveAttribute('aria-expanded', 'true');
    return { ...r, listSlots, form: screen.getByRole('heading', { name: 'Book an appointment' }).closest('form') as HTMLElement };
  }

  it('defaults to the first online provider and lists only future open slots, earliest first', async () => {
    const { listSlots, form } = await openForm();
    expect(listSlots).toHaveBeenCalledWith('PRV-001', 'OPEN');
    const provGroup = within(form).getByRole('group', { name: 'Provider' });
    expect(within(provGroup).getByRole('button', { name: 'Dr. Sarah Chen' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(provGroup).getByRole('button', { name: 'Dr. Off Line (offline)' })).toBeDisabled();
    const slotGroup = within(form).getByRole('group', { name: 'Open slot' });
    await waitFor(() => expect(within(slotGroup).getAllByRole('button').map((b) => b.textContent)).toEqual([
      '2:00 PM · 30m', '3:00 PM · 45m · Room 2',
    ]));
  });

  it('validates required fields', async () => {
    const { ctx, user, form } = await openForm();
    await user.click(within(form).getByRole('button', { name: 'Book appointment' }));
    const name = within(form).getByLabelText('Patient name');
    expect(name).toHaveAttribute('aria-invalid', 'true');
    expect(name).toHaveAccessibleDescription('Enter the patient’s name.');
    expect(within(form).getByText('Choose an open slot.')).toBeInTheDocument();
    expect(ctx.book).not.toHaveBeenCalled();

    await user.type(name, '   ');
    await user.click(within(form).getByRole('button', { name: 'Book appointment' }));
    expect(ctx.book).not.toHaveBeenCalled();
  });

  it('switching provider reloads slots and clears the chosen slot', async () => {
    const { user, form, listSlots } = await openForm();
    const slotGroup = within(form).getByRole('group', { name: 'Open slot' });
    await user.click(await within(slotGroup).findByRole('button', { name: /2:00 PM/ }));
    expect(within(slotGroup).getByRole('button', { name: /2:00 PM/ })).toHaveAttribute('aria-pressed', 'true');
    await user.click(within(form).getByRole('button', { name: 'Dr. James Lee' }));
    expect(listSlots).toHaveBeenLastCalledWith('PRV-002', 'OPEN');
    await waitFor(() => expect(within(slotGroup).getByRole('button', { name: /2:00 PM/ })).toHaveAttribute('aria-pressed', 'false'));
  });

  it('shows a message when the provider has no open slots', async () => {
    vi.spyOn(api, 'listSlots').mockResolvedValue([]);
    const r = setup({}, deskData({ appointments: appts(), providers }));
    await r.user.click(screen.getByRole('button', { name: /book appointment/i }));
    expect(await screen.findByText('No open slots left today for this provider.')).toBeInTheDocument();
  });

  it('submits trimmed values with the chosen type and closes on success', async () => {
    const { ctx, user, form } = await openForm();
    await user.type(within(form).getByLabelText('Patient name'), '  Ava Stone ');
    await user.type(within(form).getByLabelText('Contact (phone or email)'), ' ava@example.com ');
    const typeGroup = within(form).getByRole('group', { name: 'Appointment type' });
    await user.click(within(typeGroup).getByRole('button', { name: 'Urgent' }));
    expect(within(typeGroup).getByRole('button', { name: 'Urgent' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(typeGroup).getByRole('button', { name: 'Routine' })).toHaveAttribute('aria-pressed', 'false');
    await user.click(await within(form).findByRole('button', { name: /3:00 PM/ }));
    await user.click(within(form).getByRole('button', { name: 'Book appointment' }));
    expect(ctx.book).toHaveBeenCalledWith({ patientName: 'Ava Stone', contact: 'ava@example.com', type: 'URGENT', providerId: 'PRV-001', slotId: 'PRV-001-late' });
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Book an appointment' })).toBeNull());
  });

  it('keeps the form open with an alert when booking fails, and clears the slot', async () => {
    const book = vi.fn(async () => 'That slot was just taken. Pick another one.');
    const { user, form } = await openForm({ book });
    await user.type(within(form).getByLabelText('Patient name'), 'Ava');
    await user.click(await within(form).findByRole('button', { name: /2:00 PM/ }));
    await user.click(within(form).getByRole('button', { name: 'Book appointment' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('That slot was just taken');
    expect(within(form).getByRole('button', { name: /2:00 PM/ })).toHaveAttribute('aria-pressed', 'false');
    expect(book.mock.calls[0]).toEqual([expect.objectContaining({ contact: undefined })]);
  });

  it('Cancel closes the form', async () => {
    const { user, form } = await openForm();
    await user.click(within(form).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('heading', { name: 'Book an appointment' })).toBeNull();
  });
});
