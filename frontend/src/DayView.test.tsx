import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { DayView } from './DayView';
import { at, deskData, makeCtx, provider, schedSlot } from './test/fixtures';
import type { ScheduleView } from './types';

function schedule(): ScheduleView {
  return {
    providers: [
      {
        provider: provider(),
        slots: [
          schedSlot({ id: 'SLT-001', startTime: at('09:00'), appointment: { id: 'APT-001', patientName: 'Emily Watson', type: 'ROUTINE', status: 'CONFIRMED' } }),
          schedSlot({ id: 'SLT-002', startTime: at('09:30'), appointment: { id: 'APT-002', patientName: 'Liam Ortiz', type: 'URGENT', status: 'RESCHEDULE_PENDING' } }),
          schedSlot({ id: 'SLT-003', startTime: at('10:00'), status: 'OPEN' }),
          schedSlot({ id: 'SLT-004', startTime: at('10:30'), status: 'OPEN', heldBySuggestionId: 'SUG-001' }),
          schedSlot({ id: 'SLT-005', startTime: at('11:00'), status: 'CANCELLED' }),
          schedSlot({ id: 'SLT-006', startTime: at('11:30'), appointment: { id: 'APT-003', patientName: 'Mia Chen', type: 'FOLLOW_UP', status: 'COMPLETED' } }),
        ],
      },
      {
        provider: provider({ id: 'PRV-002', name: 'Dr. James Lee', status: 'OFFLINE', specialty: 'PEDIATRIC', clinicName: 'Uptown' }),
        slots: [schedSlot({ id: 'SLT-010', startTime: at('13:00'), status: 'OPEN', providerId: 'PRV-002', providerName: 'Dr. James Lee' })],
      },
    ],
  };
}

const setup = (s: ScheduleView = schedule()) => {
  const ctx = makeCtx(deskData({ schedule: s }));
  const user = userEvent.setup();
  render(<DayView ctx={ctx} />);
  return { ctx, user };
};

describe('DayView', () => {
  it('labels every slot by time, provider and state', () => {
    setup();
    for (const name of [
      '9:00 AM, Dr. Sarah Chen, Emily W.',
      '9:30 AM, Dr. Sarah Chen, Liam O.',
      '10:00 AM, Dr. Sarah Chen, Open',
      '10:30 AM, Dr. Sarah Chen, Held',
      '11:00 AM, Dr. Sarah Chen, Cancelled',
      '11:30 AM, Dr. Sarah Chen, Mia C.',
      '1:00 PM, Dr. James Lee, Unavailable',
    ]) expect(screen.getByRole('button', { name })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText('Pediatrics', { exact: false })).toBeInTheDocument();
    expect(screen.getByText('Uptown')).toBeInTheDocument();
    for (const b of screen.getAllByRole('button')) expect(b).toHaveAccessibleName();
  });

  it('renders the legend and hour axis covering the slots', () => {
    setup();
    const legend = screen.getByRole('list', { name: 'Legend' });
    expect(within(legend).getAllByRole('listitem').map((l) => l.textContent)).toContain('Held for a suggestion');
    expect(screen.getAllByText('9a').length).toBeGreaterThan(0);
    expect(screen.getAllByText('2p').length).toBeGreaterThan(0);
  });

  it('selecting a booked slot shows details and lets staff suggest a new time', async () => {
    const { ctx, user } = setup();
    const b = screen.getByRole('button', { name: /^9:00 AM/ });
    await user.click(b);
    expect(b).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Emily Watson · Routine · Confirmed')).toBeInTheDocument();
    expect(screen.getByText(/9:00 AM – 9:30 AM · Dr. Sarah Chen/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Patient view' })).toHaveAttribute('href', '#/portal/APT-001');
    await user.click(screen.getByRole('button', { name: 'Suggest new time' }));
    expect(ctx.suggestAnother).toHaveBeenCalledWith('APT-001');
    expect(ctx.goTab).toHaveBeenCalledWith('queue');
    await user.click(screen.getByRole('button', { name: 'Cancel slot' }));
    expect(ctx.cancelSlot).toHaveBeenCalledWith('SLT-001');
  });

  it('clicking the selected slot again (or Close) deselects it', async () => {
    const { user } = setup();
    const b = screen.getByRole('button', { name: /^10:00 AM/ });
    await user.click(b);
    expect(screen.getByText('No patient booked')).toBeInTheDocument();
    await user.click(b);
    expect(b).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByText(/Select a slot above/)).toBeInTheDocument();
    await user.click(b);
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.getByText(/Select a slot above/)).toBeInTheDocument();
  });

  it('no "Suggest new time" for a pending or completed appointment; no cancel for a cancelled slot', async () => {
    const { user } = setup();
    await user.click(screen.getByRole('button', { name: /^9:30 AM/ }));
    expect(screen.queryByRole('button', { name: 'Suggest new time' })).toBeNull();
    await user.click(screen.getByRole('button', { name: /^11:30 AM/ }));
    expect(screen.queryByRole('button', { name: 'Suggest new time' })).toBeNull();
    await user.click(screen.getByRole('button', { name: /^11:00 AM/ }));
    expect(screen.queryByRole('button', { name: 'Cancel slot' })).toBeNull();
    await user.click(screen.getByRole('button', { name: /^10:30 AM/ }));
    expect(screen.getByText('Held by a pending suggestion')).toBeInTheDocument();
  });

  it('disables Cancel slot while busy', async () => {
    const ctx = makeCtx(deskData({ schedule: schedule() }), { busy: { 's-SLT-003': true } });
    const user = userEvent.setup();
    render(<DayView ctx={ctx} />);
    await user.click(screen.getByRole('button', { name: /^10:00 AM/ }));
    expect(screen.getByRole('button', { name: 'Cancel slot' })).toBeDisabled();
  });

  it('shows an empty message without slots', () => {
    setup({ providers: [] });
    expect(screen.getByText('No slots scheduled today.')).toBeInTheDocument();
  });
});
