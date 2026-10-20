import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { WaitlistView } from './Waitlist';
import { at, deskData, makeCtx, slot, waitlist } from './test/fixtures';
import type { Ctx } from './ctx';
import type { DeskData } from './useDesk';

function data(): DeskData {
  return deskData({
    waitlist: [
      waitlist({ id: 'WL-001', patientName: 'Noah Park', status: 'WAITING', createdAt: at('08:00') }),
      waitlist({ id: 'WL-002', patientName: 'Olivia Kim', status: 'OFFERED', type: 'URGENT', preferredWindow: 'AFTERNOON', contact: null, createdAt: at('09:00'), offeredSlot: slot({ startTime: at('13:30'), status: 'OPEN', providerName: 'Dr. James Lee' }) }),
      waitlist({ id: 'WL-003', patientName: 'Pat Doe', status: 'BOOKED', createdAt: at('07:00'), offeredSlot: slot({ startTime: at('15:00') }) }),
      waitlist({ id: 'WL-004', patientName: 'Quinn Ray', status: 'DECLINED', createdAt: at('06:00') }),
      waitlist({ id: 'WL-005', patientName: 'Early Bird', status: 'WAITING', createdAt: at('06:30'), specialty: 'PEDIATRIC' }),
    ],
    notifications: [
      { id: 1, appointmentId: 'APT-1', channel: 'SMS', recipient: '+15550100', message: 'Moved to 2:00 PM', status: 'SENT', createdAt: at('10:00') },
      { id: 2, appointmentId: 'APT-2', channel: 'EMAIL', recipient: 'x@y.z', message: 'Moved to 3:00 PM', status: 'FAILED', createdAt: at('11:00') },
    ],
  });
}

function setup(p: Partial<Ctx> = {}, d = data()) {
  const ctx = makeCtx(d, p);
  const user = userEvent.setup();
  render(<WaitlistView ctx={ctx} />);
  return { ctx, user };
}
const rowOf = (name: string) => screen.getByText(name).closest('.trow') as HTMLElement;

describe('WaitlistView', () => {
  it('orders offers first, then waiting (longest wait first), booked, declined', () => {
    setup();
    const names = ['Noah Park', 'Olivia Kim', 'Pat Doe', 'Quinn Ray', 'Early Bird'];
    const order = screen.getAllByText(new RegExp(`^(${names.join('|')})$`)).map((e) => e.textContent);
    expect(order).toEqual(['Olivia Kim', 'Early Bird', 'Noah Park', 'Pat Doe', 'Quinn Ray']);
  });

  it('renders status, needs and preferences', () => {
    setup();
    const offered = rowOf('Olivia Kim');
    expect(offered).toHaveTextContent('Offer ready');
    expect(offered).toHaveTextContent('Urgent');
    expect(offered).toHaveTextContent('Afternoons');
    expect(offered).toHaveTextContent('1:30 PM · Dr. James Lee');
    expect(rowOf('Noah Park')).toHaveTextContent('No matching slot yet');
    expect(rowOf('Noah Park')).toHaveTextContent('noah@example.com');
    expect(rowOf('Pat Doe')).toHaveTextContent('Booked 3:00 PM · Dr. Sarah Chen');
    expect(rowOf('Quinn Ray')).toHaveTextContent('Declined');
    expect(rowOf('Early Bird')).toHaveTextContent('Pediatrics');
  });

  it('accept / decline an offer', async () => {
    const { ctx, user } = setup();
    const offered = rowOf('Olivia Kim');
    await user.click(within(offered).getByRole('button', { name: 'Accept' }));
    expect(ctx.waitlistAccept).toHaveBeenCalledWith('WL-002');
    await user.click(within(offered).getByRole('button', { name: 'Decline' }));
    expect(ctx.waitlistDecline).toHaveBeenCalledWith('WL-002');
    expect(within(rowOf('Noah Park')).queryByRole('button')).toBeNull();
  });

  it('disables offer buttons while busy', () => {
    setup({ busy: { 'w-WL-002': true } });
    const offered = rowOf('Olivia Kim');
    expect(within(offered).getByRole('button', { name: 'Accept' })).toBeDisabled();
    expect(within(offered).getByRole('button', { name: 'Decline' })).toBeDisabled();
  });

  it('lists notifications newest first with sent/failed status', () => {
    setup();
    const sec = screen.getByRole('region', { name: 'Notifications sent' });
    const rows = sec.querySelectorAll('.trow:not(.head)');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('11:00 AMEMAILx@y.zMoved to 3:00 PMFailed');
    expect(rows[1]).toHaveTextContent('10:00 AMSMS+15550100Moved to 2:00 PMSent');
  });

  it('shows empty messages', () => {
    setup({}, deskData());
    expect(screen.getByText('Nobody is on the waitlist.')).toBeInTheDocument();
    expect(screen.getByText(/No notifications yet/)).toBeInTheDocument();
  });
});

describe('WaitlistForm', () => {
  async function openForm(p: Partial<Ctx> = {}) {
    const r = setup(p);
    const toggle = screen.getByRole('button', { name: /add to waitlist/i });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await r.user.click(toggle);
    expect(screen.getByRole('button', { name: /close form/i })).toHaveAttribute('aria-expanded', 'true');
    return { ...r, form: screen.getByRole('heading', { name: 'Add to waitlist' }).closest('form') as HTMLElement };
  }

  it('requires a name', async () => {
    const { ctx, user, form } = await openForm();
    await user.click(within(form).getByRole('button', { name: 'Add to waitlist' }));
    const name = within(form).getByLabelText('Patient name');
    expect(name).toHaveAttribute('aria-invalid', 'true');
    expect(name).toHaveAccessibleDescription('Enter the patient’s name.');
    expect(ctx.addWaitlist).not.toHaveBeenCalled();
  });

  it('submits the chosen options and closes', async () => {
    const { ctx, user, form } = await openForm();
    await user.type(within(form).getByLabelText('Patient name'), ' Zoe Lane ');
    await user.type(within(form).getByLabelText('Contact (phone or email)'), '+1555');
    await user.click(within(within(form).getByRole('group', { name: 'Appointment type' })).getByRole('button', { name: 'Follow-up' }));
    const spec = within(form).getByRole('group', { name: 'Specialty' });
    await user.click(within(spec).getByRole('button', { name: 'Specialist' }));
    expect(within(spec).getByRole('button', { name: 'Specialist' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(spec).getByRole('button', { name: 'General practice' })).toHaveAttribute('aria-pressed', 'false');
    await user.click(within(within(form).getByRole('group', { name: 'Preferred window' })).getByRole('button', { name: 'Mornings' }));
    await user.click(within(form).getByRole('button', { name: 'Add to waitlist' }));
    expect(ctx.addWaitlist).toHaveBeenCalledWith({ patientName: 'Zoe Lane', contact: '+1555', type: 'FOLLOW_UP', specialty: 'SPECIALIST', preferredWindow: 'MORNING' });
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Add to waitlist' })).toBeNull());
  });

  it('shows a server error and stays open', async () => {
    const addWaitlist = vi.fn(async () => 'Clinic not found');
    const { user, form } = await openForm({ addWaitlist });
    await user.type(within(form).getByLabelText('Patient name'), 'Zoe');
    await user.click(within(form).getByRole('button', { name: 'Add to waitlist' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('Clinic not found');
    expect(addWaitlist).toHaveBeenCalledWith(expect.objectContaining({ contact: undefined, type: 'ROUTINE', specialty: 'GENERAL', preferredWindow: 'ANY' }));
  });

  it('Cancel closes the form', async () => {
    const { user, form } = await openForm();
    await user.click(within(form).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('heading', { name: 'Add to waitlist' })).toBeNull();
  });
});
