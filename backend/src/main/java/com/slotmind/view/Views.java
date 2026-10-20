package com.slotmind.view;

import com.slotmind.domain.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** JSON view records (decision.md §3 "JSON views"). Built inside service transactions; controllers never see entities. */
public final class Views {
    private Views() {}

    public record SlotView(String id, LocalDateTime startTime, int durationMinutes, SlotStatus status,
                           String providerId, String providerName, String roomId, String roomName,
                           String clinicId, String clinicName) {
        public static SlotView of(Slot s) {
            if (s == null) return null;
            Provider p = s.getProvider();
            Room r = s.getRoom();
            Clinic c = r != null && r.getClinic() != null ? r.getClinic() : p.getClinic();
            return new SlotView(s.getId(), s.getStartTime(), s.getDurationMinutes(), s.getStatus(),
                    p.getId(), p.getName(), r == null ? null : r.getId(), r == null ? null : r.getName(),
                    c == null ? null : c.getId(), c == null ? null : c.getName());
        }
    }

    public record ProviderView(String id, String name, Specialty specialty, ProviderStatus status, String clinicId,
                               String clinicName, int maxDailyAppointments, int bookedToday) {
        public static ProviderView of(Provider p, Map<String, Integer> load) {
            Clinic c = p.getClinic();
            return new ProviderView(p.getId(), p.getName(), p.getSpecialty(), p.getStatus(),
                    c == null ? null : c.getId(), c == null ? null : c.getName(), p.getMaxDailyAppointments(),
                    load.getOrDefault(p.getId(), 0));
        }
    }

    public record AppointmentView(String id, String patientName, String contact, AppointmentType type,
                                  AppointmentStatus status, String providerId, String providerName, SlotView slot) {
        public static AppointmentView of(Appointment a) {
            return new AppointmentView(a.getId(), a.getPatientName(), a.getContact(), a.getType(), a.getStatus(),
                    a.getProvider().getId(), a.getProvider().getName(), SlotView.of(a.getSlot()));
        }
    }

    public record SuggestionView(String id, String appointmentId, String patientName, AppointmentType appointmentType,
                                 AppointmentStatus appointmentStatus, TriggerReason triggerReason, SuggestionStatus status,
                                 String strategy, boolean fallbackUsed, boolean crossClinic, double confidence,
                                 String reasoning, LocalDateTime createdAt, LocalDateTime resolvedAt,
                                 SlotView originalSlot, SlotView recommendedSlot, String alternateProviderId) {
        public static SuggestionView of(RescheduleSuggestion g) {
            Appointment a = g.getAppointment();
            return new SuggestionView(g.getId(), a.getId(), a.getPatientName(), a.getType(), a.getStatus(),
                    g.getTriggerReason(), g.getStatus(), g.getStrategy(), g.isFallbackUsed(), g.isCrossClinic(),
                    g.getConfidence(), g.getReasoning(), g.getCreatedAt(), g.getResolvedAt(),
                    SlotView.of(g.getOriginalSlot()), SlotView.of(g.getRecommendedSlot()), g.getAlternateProviderId());
        }
    }

    public record ScheduleAppointment(String id, String patientName, AppointmentType type, AppointmentStatus status) {}

    public record ScheduleSlot(String id, LocalDateTime startTime, int durationMinutes, SlotStatus status,
                               String providerId, String providerName, String roomId, String roomName,
                               String clinicId, String clinicName, ScheduleAppointment appointment,
                               String heldBySuggestionId) {
        public static ScheduleSlot of(SlotView v, ScheduleAppointment a, String held) {
            return new ScheduleSlot(v.id(), v.startTime(), v.durationMinutes(), v.status(), v.providerId(),
                    v.providerName(), v.roomId(), v.roomName(), v.clinicId(), v.clinicName(), a, held);
        }
    }

    public record ScheduleProvider(ProviderView provider, List<ScheduleSlot> slots) {}

    public record ScheduleView(List<ScheduleProvider> providers) {}

    public record WaitlistView(String id, String patientName, String contact, AppointmentType type, Specialty specialty,
                               PreferredWindow preferredWindow, String clinicId, WaitlistStatus status,
                               LocalDateTime createdAt, SlotView offeredSlot, String bookedAppointmentId) {
        public static WaitlistView of(WaitlistEntry w) {
            return new WaitlistView(w.getId(), w.getPatientName(), w.getContact(), w.getType(), w.getSpecialty(),
                    w.getPreferredWindow(), w.getClinic() == null ? null : w.getClinic().getId(), w.getStatus(),
                    w.getCreatedAt(), SlotView.of(w.getOfferedSlot()), w.getBookedAppointmentId());
        }
    }

    public record NotificationView(Long id, String appointmentId, NotificationChannel channel, String recipient,
                                   String message, NotificationStatus status, LocalDateTime createdAt) {
        public static NotificationView of(Notification n) {
            return new NotificationView(n.getId(), n.getAppointmentId(), n.getChannel(), n.getRecipient(), n.getMessage(),
                    n.getStatus(), n.getCreatedAt());
        }
    }

    public record ActivityView(Long id, LocalDateTime at, ActivityKind kind, String message) {
        public static ActivityView of(ActivityEvent e) {
            return new ActivityView(e.getId(), e.getAt(), e.getKind(), e.getMessage());
        }
    }

    public record StrategyConfig(String strategy, List<String> available) {}

    public record RoomView(String id, String name, String clinicId, String clinicName, List<String> equipment) {
        public static RoomView of(Room r) {
            return new RoomView(r.getId(), r.getName(), r.getClinic() == null ? null : r.getClinic().getId(),
                    r.getClinic() == null ? null : r.getClinic().getName(), r.getEquipment().stream().sorted().toList());
        }
    }

    public record ClinicView(String id, String name) {
        public static ClinicView of(Clinic c) { return new ClinicView(c.getId(), c.getName()); }
    }

    public record PreferenceView(String patientName, PreferredWindow preferredWindow, String source) {}

    public record PortalView(AppointmentView appointment, List<SuggestionView> suggestions) {}
}
