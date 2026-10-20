import type { DeskData } from './useDesk';
import type { AppointmentView, ProviderStatus, Strategy, SuggestionView, WaitlistRequest, BookRequest } from './types';

export type Tab = 'queue' | 'day' | 'appts' | 'providers' | 'waitlist';

export interface StreamState {
  status: string;
  text: string;
  suggestion?: SuggestionView;
}

export interface Ctx {
  data: DeskData;
  streams: Record<string, StreamState>;
  conflicts: Record<string, string>;
  busy: Record<string, boolean>;
  goTab: (t: Tab) => void;
  toast: (text: string, kind?: 'ok' | 'error') => void;
  refresh: () => Promise<void>;
  accept: (s: SuggestionView) => void;
  reject: (s: SuggestionView) => void;
  suggestAnother: (apptId: string) => void;
  addApptToWaitlist: (a: AppointmentView) => void;
  setProviderStatus: (id: string, status: ProviderStatus) => void;
  cancelSlot: (slotId: string) => void;
  setStrategy: (s: Strategy) => void;
  complete: (apptId: string) => void;
  book: (b: BookRequest) => Promise<string | null>;
  addWaitlist: (b: WaitlistRequest) => Promise<string | null>;
  waitlistAccept: (id: string) => void;
  waitlistDecline: (id: string) => void;
}
