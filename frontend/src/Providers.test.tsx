import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { api, ApiError } from './api';
import { ProvidersView } from './Providers';
import { at, deskData, makeCtx, provider, schedSlot } from './test/fixtures';
import type { Ctx } from './ctx';

function data() {
  const chen = provider({ bookedToday: 7, maxDailyAppointments: 8 });
  const lee = provider({ id: 'PRV-002', name: 'Dr. James Lee', status: 'OFFLINE', specialty: 'PEDIATRIC', bookedToday: 0, maxDailyAppointments: 0, clinicName: null });
  return deskData({
    providers: [chen, lee],
    schedule: {
      providers: [{
        provider: chen,
        slots: [
          schedSlot({ id: 'S5', startTime: at('13:00'), status: 'OPEN' }),
          schedSlot({ id: 'S1', startTime: at('09:00'), appointment: { id: 'APT-1', patientName: 'Emily Watson', type: 'ROUTINE', status: 'CONFIRMED' } }),
          schedSlot({ id: 'S2', startTime: at('10:00'), status: 'OPEN', heldBySuggestionId: 'SUG-1' }),
          schedSlot({ id: 'S3', startTime: at('11:00'), status: 'CANCELLED' }),
          schedSlot({ id: 'S4', startTime: at('12:00'), appointment: { id: 'APT-2', patientName: 'Mia Chen', type: 'ROUTINE', status: 'COMPLETED' } }),
          schedSlot({ id: 'S0', startTime: '2000-01-01T08:00:00', status: 'OPEN' }),
        ],
      }],
    },
  });
}

function setup(p: Partial<Ctx> = {}) {
  const ctx = makeCtx(data(), p);
  const user = userEvent.setup();
  render(<ProvidersView ctx={ctx} />);
  return { ctx, user };
}

describe('ProvidersView', () => {
  it('shows one card per provider with a status toggle group', async () => {
    const { ctx, user } = setup();
    const chen = screen.getByRole('region', { name: 'Dr. Sarah Chen' });
    expect(chen).toHaveTextContent('General practice · PRV-001 · Downtown');
    const group = within(chen).getByRole('group', { name: 'Status of Dr. Sarah Chen' });
    expect(within(group).getByRole('button', { name: 'Available' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(group).getByRole('button', { name: 'Offline' })).toHaveAttribute('aria-pressed', 'false');
    await user.click(within(group).getByRole('button', { name: 'Available' }));
    expect(ctx.setProviderStatus).not.toHaveBeenCalled(); // already the current status
    await user.click(within(group).getByRole('button', { name: 'Busy' }));
    expect(ctx.setProviderStatus).toHaveBeenCalledWith('PRV-001', 'BUSY');

    const lee = screen.getByRole('region', { name: 'Dr. James Lee' });
    expect(within(lee).getByRole('button', { name: 'Offline' })).toHaveAttribute('aria-pressed', 'true');
    expect(lee).toHaveTextContent('Pediatrics');
  });

  it('shows load as a progressbar', () => {
    setup();
    const chen = screen.getByRole('region', { name: 'Dr. Sarah Chen' });
    const bar = within(chen).getByRole('progressbar', { name: 'Load today' });
    expect(bar).toHaveAttribute('aria-valuenow', '7');
    expect(bar).toHaveAttribute('aria-valuemax', '8');
    expect(chen).toHaveTextContent('88% · 7 of 8 appointments');
    expect(screen.getByRole('region', { name: 'Dr. James Lee' })).toHaveTextContent('0% · 0 of 0 appointments');
  });

  it('lists the next four upcoming slots with their state', () => {
    setup();
    const items = within(screen.getByRole('region', { name: 'Dr. Sarah Chen' })).getAllByRole('listitem');
    expect(items.map((li) => li.textContent)).toEqual([
      '9:00 AMEmily WatsonConfirmed',
      '10:00 AMOpen slotHeld',
      '11:00 AMCancelled slotCancelled',
      '12:00 PMMia ChenCompleted', // a completed visit's slot is still booked, never "Open"
    ]);
    expect(within(screen.getByRole('region', { name: 'Dr. James Lee' })).getByText('No upcoming slots.')).toBeInTheDocument();
  });

  it('disables the status toggle while busy', () => {
    setup({ busy: { 'p-PRV-001': true } });
    const group = screen.getByRole('group', { name: 'Status of Dr. Sarah Chen' });
    for (const b of within(group).getAllByRole('button')) expect(b).toBeDisabled();
  });
});

describe('PreferenceEditor', () => {
  const form = () => screen.getByRole('heading', { name: 'Patient time preference' }).closest('form') as HTMLElement;

  it('requires a name for lookup and save', async () => {
    const get = vi.spyOn(api, 'getPreference');
    const { user } = setup();
    await user.click(within(form()).getByRole('button', { name: 'Look up' }));
    expect(within(form()).getByRole('alert')).toHaveTextContent('Enter a patient name.');
    await user.click(within(form()).getByRole('button', { name: 'Save preference' }));
    expect(within(form()).getByRole('alert')).toHaveTextContent('Enter a patient name.');
    expect(get).not.toHaveBeenCalled();
  });

  it('looks up the current preference and selects it', async () => {
    const get = vi.spyOn(api, 'getPreference').mockResolvedValue({ patientName: 'Emily Watson', preferredWindow: 'AFTERNOON', source: 'INFERRED' });
    const { user } = setup();
    await user.type(within(form()).getByLabelText('Patient name'), ' Emily Watson ');
    await user.click(within(form()).getByRole('button', { name: 'Look up' }));
    expect(get).toHaveBeenCalledWith('Emily Watson');
    expect(await within(form()).findByText('Current: Afternoons (inferred)')).toBeInTheDocument();
    const group = within(form()).getByRole('group', { name: 'Preferred window' });
    expect(within(group).getByRole('button', { name: 'Afternoons' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(group).getByRole('button', { name: 'Mornings' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('shows the lookup error', async () => {
    vi.spyOn(api, 'getPreference').mockRejectedValue(new ApiError(404, 'NOT_FOUND', 'No preference for Zed'));
    const { user } = setup();
    await user.type(within(form()).getByLabelText('Patient name'), 'Zed');
    await user.click(within(form()).getByRole('button', { name: 'Look up' }));
    expect(await within(form()).findByRole('alert')).toHaveTextContent('No preference for Zed');
  });

  it('saves the chosen window and toasts', async () => {
    const set = vi.spyOn(api, 'setPreference').mockResolvedValue({});
    const { ctx, user } = setup();
    await user.type(within(form()).getByLabelText('Patient name'), 'Emily Watson');
    await user.click(within(form()).getByRole('button', { name: 'Any time' }));
    await user.click(within(form()).getByRole('button', { name: 'Save preference' }));
    expect(set).toHaveBeenCalledWith('Emily Watson', 'ANY');
    expect(await within(form()).findByText('Current: Any time (explicit)')).toBeInTheDocument();
    expect(ctx.toast).toHaveBeenCalledWith('Saved: Emily Watson prefers any time');
  });

  it('shows the save error', async () => {
    vi.spyOn(api, 'setPreference').mockRejectedValue(new ApiError(400, 'VALIDATION', 'Bad window'));
    const { ctx, user } = setup();
    await user.type(within(form()).getByLabelText('Patient name'), 'Emily');
    await user.click(within(form()).getByRole('button', { name: 'Save preference' }));
    expect(await within(form()).findByRole('alert')).toHaveTextContent('Bad window');
    expect(ctx.toast).not.toHaveBeenCalled();
  });
});
