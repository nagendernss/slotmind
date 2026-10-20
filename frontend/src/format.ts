import type { AppointmentStatus, AppointmentType, ProviderStatus, Specialty, TriggerReason } from './types';

export const ACCENT = '#1D4ED8';

export const TYPE: Record<AppointmentType, { label: string; fg: string; bg: string }> = {
  ROUTINE: { label: 'Routine', fg: '#1D4ED8', bg: '#EAF0FD' },
  FOLLOW_UP: { label: 'Follow-up', fg: '#0B6E5A', bg: '#E3F4EF' },
  URGENT: { label: 'Urgent', fg: '#B42318', bg: '#FDECEB' },
};

export const TRIG: Record<TriggerReason, { label: string; fg: string; bg: string }> = {
  PROVIDER_OFFLINE: { label: 'Provider offline', fg: '#B54708', bg: '#FEF0E1' },
  SLOT_CANCELLED: { label: 'Slot cancelled', fg: '#6941C6', bg: '#F2EEFC' },
  INITIAL: { label: 'Manual', fg: '#344054', bg: '#EEF0F3' },
};

export const PSTAT: Record<ProviderStatus, { label: string; fg: string; dot: string }> = {
  AVAILABLE: { label: 'Available', fg: '#067647', dot: '#12B76A' },
  BUSY: { label: 'Busy', fg: '#1D4ED8', dot: '#2E6BF0' },
  OFFLINE: { label: 'Offline', fg: '#B54708', dot: '#F79009' },
};

export const ASTAT: Record<AppointmentStatus, { label: string; fg: string; bg: string }> = {
  CONFIRMED: { label: 'Confirmed', fg: '#067647', bg: '#E5F5EC' },
  RESCHEDULE_PENDING: { label: 'Reschedule pending', fg: '#B54708', bg: '#FEF0E1' },
  RESCHEDULED: { label: 'Rescheduled', fg: '#1D4ED8', bg: '#EAF0FD' },
  COMPLETED: { label: 'Completed', fg: '#475467', bg: '#F2F4F7' },
};

export const SPEC: Record<Specialty, string> = {
  GENERAL: 'General practice',
  SPECIALIST: 'Specialist',
  PEDIATRIC: 'Pediatrics',
};

export const STRATEGY_LABEL: Record<string, string> = {
  AI: 'AI advisor',
  RULE_BASED: 'Rule-based',
  ROOM_CONSTRAINT: 'Room constraint',
};

export const ACTIVITY_DOT: Record<string, string> = {
  INFO: '#2E6BF0', OK: '#12B76A', WARN: '#F79009', ERROR: '#F04438', AGENT: '#7A5AF8',
};

export const PREF_LABEL: Record<string, string> = { MORNING: 'Mornings', AFTERNOON: 'Afternoons', ANY: 'Any time' };

const lookup = <T,>(m: Record<string, T>, k: string | null | undefined, fallback: T): T => (k && m[k]) || fallback;
export const typeOf = (k?: string | null) => lookup(TYPE as Record<string, { label: string; fg: string; bg: string }>, k, { label: k || '—', fg: '#344054', bg: '#EEF0F3' });
export const trigOf = (k?: string | null) => lookup(TRIG as Record<string, { label: string; fg: string; bg: string }>, k, TRIG.INITIAL);
export const pstatOf = (k?: string | null) => lookup(PSTAT as Record<string, { label: string; fg: string; dot: string }>, k, { label: k || '—', fg: '#475467', dot: '#98A2B3' });
export const astatOf = (k?: string | null) => lookup(ASTAT as Record<string, { label: string; fg: string; bg: string }>, k, { label: k || '—', fg: '#475467', bg: '#F2F4F7' });
export const specOf = (k?: string | null) => (k && (SPEC as Record<string, string>)[k]) || k || '';

/** Parse an ISO local datetime ("2026-10-20T11:00:00") without timezone shifting. */
export function parseLocal(iso?: string | number[] | null): Date | null {
  if (!iso) return null;
  if (Array.isArray(iso)) { // Jackson timestamp-array fallback [y,m,d,h,min,s]
    const [y, mo, d, h = 0, mi = 0, se = 0] = iso;
    return new Date(y, mo - 1, d, h, mi, se);
  }
  const m = /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/.exec(iso);
  if (m && !/[zZ]|[+-]\d{2}:?\d{2}$/.test(iso)) {
    return new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
  }
  const d = new Date(iso);
  return isNaN(d.getTime()) ? null : d;
}

export function fmtTime(iso?: string | null): string {
  const d = parseLocal(iso);
  if (!d) return '—';
  return fmtDateTime(d);
}

export function fmtDateTime(d: Date): string {
  let h = d.getHours();
  const m = d.getMinutes();
  const ap = h >= 12 ? 'PM' : 'AM';
  h = h % 12 || 12;
  return `${h}:${String(m).padStart(2, '0')} ${ap}`;
}

export function minutesOf(iso?: string | null): number {
  const d = parseLocal(iso);
  return d ? d.getHours() * 60 + d.getMinutes() : 0;
}

export function addMinutes(iso: string, mins: number): string {
  const d = parseLocal(iso);
  if (!d) return '—';
  return fmtDateTime(new Date(d.getTime() + mins * 60000));
}

export function timeMs(iso?: string | null): number {
  const d = parseLocal(iso);
  return d ? d.getTime() : 0;
}

export function initials(name: string): string {
  const parts = name.replace(/^Dr\.?\s+/i, '').split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] || '') + (parts[1]?.[0] || '')).toUpperCase();
}

export function ago(iso?: string | null): string {
  const t = timeMs(iso);
  if (!t) return '';
  const mins = Math.max(0, Math.round((Date.now() - t) / 60000));
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min`;
  const h = Math.floor(mins / 60);
  return `${h} h ${mins % 60} min`;
}

export function shortName(n: string): string {
  const p = n.split(' ');
  return p.length > 1 ? `${p[0]} ${p[1].charAt(0)}.` : n;
}
