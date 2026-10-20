import { describe, expect, it, vi } from 'vitest';
import {
  ACTIVITY_DOT, ASTAT, PREF_LABEL, PSTAT, SPEC, STRATEGY_LABEL, TRIG, TYPE,
  addMinutes, ago, astatOf, fmtDateTime, fmtTime, initials, minutesOf, parseLocal, pstatOf, shortName,
  specOf, timeMs, trigOf, typeOf,
} from './format';

const parts = (d: Date | null) => d && [d.getFullYear(), d.getMonth() + 1, d.getDate(), d.getHours(), d.getMinutes(), d.getSeconds(), d.getMilliseconds()];

describe('parseLocal', () => {
  it('parses a local ISO datetime without shifting the timezone', () => {
    expect(parts(parseLocal('2026-10-20T11:05:09'))).toEqual([2026, 10, 20, 11, 5, 9, 0]);
  });

  it('accepts a space separator and missing seconds', () => {
    expect(parts(parseLocal('2026-10-20 07:30'))).toEqual([2026, 10, 20, 7, 30, 0, 0]);
  });

  it('keeps milliseconds from a fractional part and ignores sub-ms digits', () => {
    expect(parts(parseLocal('2026-10-20T11:00:00.123456'))).toEqual([2026, 10, 20, 11, 0, 0, 123]);
    expect(parts(parseLocal('2026-10-20T11:00:00.5'))).toEqual([2026, 10, 20, 11, 0, 0, 500]);
    expect(parts(parseLocal('2026-10-20T11:00:00.123456789'))).toEqual([2026, 10, 20, 11, 0, 0, 123]);
  });

  it('orders timestamps within the same second', () => {
    expect(timeMs('2026-10-20T11:00:00.900001')).toBeGreaterThan(timeMs('2026-10-20T11:00:00.100'));
  });

  it('honours an explicit timezone (Z or offset)', () => {
    expect(parseLocal('2026-10-20T11:00:00Z')!.getTime()).toBe(Date.UTC(2026, 9, 20, 11, 0, 0));
    expect(parseLocal('2026-10-20T11:00:00.250+02:00')!.getTime()).toBe(Date.UTC(2026, 9, 20, 9, 0, 0, 250));
    expect(parseLocal('2026-10-20T11:00:00-0130')!.getTime()).toBe(Date.UTC(2026, 9, 20, 12, 30, 0));
  });

  it('parses Jackson timestamp arrays', () => {
    expect(parts(parseLocal([2026, 10, 20, 11, 5]))).toEqual([2026, 10, 20, 11, 5, 0, 0]);
    expect(parts(parseLocal([2026, 10, 20]))).toEqual([2026, 10, 20, 0, 0, 0, 0]);
    expect(parts(parseLocal([2026, 10, 20, 11, 5, 7, 123456789]))).toEqual([2026, 10, 20, 11, 5, 7, 123]);
  });

  it('returns null for empty or invalid input', () => {
    expect(parseLocal(undefined)).toBeNull();
    expect(parseLocal(null)).toBeNull();
    expect(parseLocal('')).toBeNull();
    expect(parseLocal('not a date')).toBeNull();
  });
});

describe('time formatting', () => {
  it.each([
    ['2026-10-20T00:05:00', '12:05 AM'],
    ['2026-10-20T09:00:00', '9:00 AM'],
    ['2026-10-20T12:00:00', '12:00 PM'],
    ['2026-10-20T17:45:30.999', '5:45 PM'],
  ])('fmtTime(%s) = %s', (iso, out) => {
    expect(fmtTime(iso)).toBe(out);
  });

  it('fmtTime shows a dash for missing/invalid input', () => {
    expect(fmtTime(undefined)).toBe('—');
    expect(fmtTime('nope')).toBe('—');
  });

  it('fmtDateTime formats a Date', () => {
    expect(fmtDateTime(new Date(2026, 0, 1, 23, 7))).toBe('11:07 PM');
  });

  it('minutesOf returns minutes since midnight', () => {
    expect(minutesOf('2026-10-20T13:30:00')).toBe(810);
    expect(minutesOf(null)).toBe(0);
  });

  it('addMinutes adds and formats', () => {
    expect(addMinutes('2026-10-20T11:45:00', 30)).toBe('12:15 PM');
    expect(addMinutes('bad', 30)).toBe('—');
  });

  it('timeMs returns epoch ms or 0', () => {
    expect(timeMs('2026-10-20T11:00:00')).toBe(new Date(2026, 9, 20, 11).getTime());
    expect(timeMs(undefined)).toBe(0);
    expect(timeMs('garbage')).toBe(0);
  });
});

describe('names', () => {
  it.each([
    ['Dr. Sarah Chen', 'SC'],
    ['dr James Lee', 'JL'],
    ['Emily', 'E'],
    ['  maria   del  rio ', 'MD'],
    ['', ''],
  ])('initials(%j) = %j', (n, out) => expect(initials(n)).toBe(out));

  it('shortName abbreviates the second word', () => {
    expect(shortName('Emily Watson')).toBe('Emily W.');
    expect(shortName('Cher')).toBe('Cher');
  });
});

describe('ago', () => {
  it('describes elapsed time relative to now', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 9, 20, 12, 0, 0));
    expect(ago('2026-10-20T11:59:50')).toBe('just now');
    expect(ago('2026-10-20T11:55:00')).toBe('5 min');
    expect(ago('2026-10-20T10:25:00')).toBe('1 h 35 min');
    expect(ago('2026-10-20T12:10:00')).toBe('just now'); // future clamps to 0
    expect(ago(null)).toBe('');
    expect(ago('bad')).toBe('');
  });
});

describe('lookups', () => {
  it('typeOf / trigOf / pstatOf / astatOf return the table entry', () => {
    expect(typeOf('URGENT')).toBe(TYPE.URGENT);
    expect(trigOf('SLOT_CANCELLED')).toBe(TRIG.SLOT_CANCELLED);
    expect(pstatOf('OFFLINE')).toBe(PSTAT.OFFLINE);
    expect(astatOf('RESCHEDULE_PENDING')).toBe(ASTAT.RESCHEDULE_PENDING);
  });

  it('labels match the UI wording', () => {
    expect(TRIG.PROVIDER_OFFLINE.label).toBe('Provider offline');
    expect(TRIG.SLOT_CANCELLED.label).toBe('Slot cancelled');
    expect(TRIG.INITIAL.label).toBe('Manual');
    expect(TYPE.FOLLOW_UP.label).toBe('Follow-up');
    expect(ASTAT.RESCHEDULE_PENDING.label).toBe('Reschedule pending');
    expect(SPEC.PEDIATRIC).toBe('Pediatrics');
    expect(STRATEGY_LABEL.AI).toBe('AI advisor');
    expect(PREF_LABEL.ANY).toBe('Any time');
    expect(Object.keys(ACTIVITY_DOT).sort()).toEqual(['AGENT', 'ERROR', 'INFO', 'OK', 'WARN']);
  });

  it('every appointment type has distinct colours', () => {
    const bgs = Object.values(TYPE).map((t) => t.bg);
    expect(new Set(bgs).size).toBe(bgs.length);
  });

  it('fall back for unknown or missing keys', () => {
    expect(typeOf('NEW_KIND')).toMatchObject({ label: 'NEW_KIND' });
    expect(typeOf(undefined).label).toBe('—');
    expect(trigOf('WHATEVER')).toBe(TRIG.INITIAL);
    expect(trigOf(null)).toBe(TRIG.INITIAL);
    expect(pstatOf('ON_LEAVE').label).toBe('ON_LEAVE');
    expect(pstatOf(null).label).toBe('—');
    expect(astatOf('CANCELLED').label).toBe('CANCELLED');
    expect(astatOf(undefined).label).toBe('—');
    expect(specOf('GENERAL')).toBe('General practice');
    expect(specOf('DENTAL')).toBe('DENTAL');
    expect(specOf(null)).toBe('');
  });
});
