import { act, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import App from './App';
import { appt, deskData, deskRoutes, routeFetch } from './test/fixtures';

function go(hash: string) {
  act(() => {
    window.location.hash = hash;
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  });
}

describe('App hash routing', () => {
  it('renders the front desk by default', async () => {
    routeFetch(deskRoutes(deskData()));
    render(<App />);
    expect(await screen.findByRole('heading', { name: 'Re-schedule queue' })).toBeInTheDocument();
  });

  it('renders the patient portal for #/portal/{id} (decoded) and switches on hashchange', async () => {
    const fetch = routeFetch({
      ...deskRoutes(deskData()),
      'GET /portal/appointments/APT%2F7': { appointment: appt({ id: 'APT/7', patientName: 'Zed Q' }), suggestions: [] },
    });
    render(<App />);
    await screen.findByRole('heading', { name: 'Re-schedule queue' });

    go('#/portal/APT%2F7?x=1');
    expect(await screen.findByRole('heading', { name: 'Hi Zed' })).toBeInTheDocument();
    expect(fetch.mock.calls.some(([u]) => String(u) === '/api/portal/appointments/APT%2F7')).toBe(true);

    go('#/');
    expect(await screen.findByRole('heading', { name: 'Re-schedule queue' })).toBeInTheDocument();
  });

  it('starts on the portal when loaded with a portal hash', async () => {
    window.location.hash = '#/portal/APT-001';
    routeFetch({ 'GET /portal/appointments/APT-001': { appointment: appt(), suggestions: [] } });
    render(<App />);
    expect(await screen.findByRole('heading', { name: 'Hi Emily' })).toBeInTheDocument();
  });
});
