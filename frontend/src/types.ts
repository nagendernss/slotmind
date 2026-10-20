// JSON views — mirrors decision.md §3 "JSON views".

export type Specialty = 'GENERAL' | 'SPECIALIST' | 'PEDIATRIC';
export type ProviderStatus = 'AVAILABLE' | 'BUSY' | 'OFFLINE';
export type SlotStatus = 'OPEN' | 'BOOKED' | 'CANCELLED';
export type AppointmentType = 'ROUTINE' | 'URGENT' | 'FOLLOW_UP';
export type AppointmentStatus = 'CONFIRMED' | 'RESCHEDULE_PENDING' | 'RESCHEDULED' | 'COMPLETED';
export type SuggestionStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'SUPERSEDED';
export type TriggerReason = 'INITIAL' | 'PROVIDER_OFFLINE' | 'SLOT_CANCELLED';
export type Strategy = 'AI' | 'RULE_BASED' | 'ROOM_CONSTRAINT' | string;
export type PreferredWindow = 'MORNING' | 'AFTERNOON' | 'ANY';
export type WaitlistStatus = 'WAITING' | 'OFFERED' | 'BOOKED' | 'DECLINED';
export type ActivityKind = 'INFO' | 'AGENT' | 'OK' | 'WARN' | 'ERROR';

export interface SlotView {
  id: string;
  startTime: string;
  durationMinutes: number;
  status: SlotStatus;
  providerId: string;
  providerName: string;
  roomId?: string | null;
  roomName?: string | null;
  clinicId?: string | null;
  clinicName?: string | null;
}

export interface ProviderView {
  id: string;
  name: string;
  specialty: Specialty;
  status: ProviderStatus;
  clinicId?: string | null;
  clinicName?: string | null;
  maxDailyAppointments: number;
  bookedToday: number;
}

export interface AppointmentView {
  id: string;
  patientName: string;
  contact?: string | null;
  type: AppointmentType;
  status: AppointmentStatus;
  providerId: string;
  providerName: string;
  slot: SlotView;
}

export interface SuggestionView {
  id: string;
  appointmentId: string;
  patientName: string;
  appointmentType: AppointmentType;
  appointmentStatus: AppointmentStatus;
  triggerReason: TriggerReason;
  status: SuggestionStatus;
  strategy: Strategy;
  fallbackUsed: boolean;
  crossClinic: boolean;
  confidence: number | null;
  reasoning: string;
  createdAt: string;
  resolvedAt?: string | null;
  originalSlot: SlotView | null;
  recommendedSlot: SlotView | null;
  alternateProviderId: string | null;
}

export interface ScheduleSlot extends SlotView {
  appointment: { id: string; patientName: string; type: AppointmentType; status: AppointmentStatus } | null;
  heldBySuggestionId: string | null;
}

export interface ScheduleView {
  providers: { provider: ProviderView; slots: ScheduleSlot[] }[];
}

export interface WaitlistView {
  id: string;
  patientName: string;
  contact?: string | null;
  type: AppointmentType;
  specialty: Specialty;
  preferredWindow: PreferredWindow;
  clinicId?: string | null;
  status: WaitlistStatus;
  createdAt: string;
  offeredSlot: SlotView | null;
  bookedAppointmentId?: string | null;
}

export interface NotificationView {
  id: string | number;
  appointmentId: string;
  channel: 'SMS' | 'EMAIL' | 'LOG' | string;
  recipient: string;
  message: string;
  status: 'SENT' | 'FAILED' | string;
  createdAt: string;
}

export interface ActivityView {
  id: string | number;
  at: string;
  kind: ActivityKind | string;
  message: string;
}

export interface StrategyConfig {
  strategy: Strategy;
  available: Strategy[];
}

export interface PortalView {
  appointment: AppointmentView;
  suggestions: SuggestionView[];
}

export interface BookRequest {
  patientName: string;
  type: AppointmentType;
  providerId: string;
  slotId: string;
  contact?: string;
}

export interface WaitlistRequest {
  patientName: string;
  contact?: string;
  type: AppointmentType;
  specialty: Specialty;
  preferredWindow: PreferredWindow;
  clinicId?: string;
}
