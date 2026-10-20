package com.slotmind.service;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.RescheduleSuggestion;
import com.slotmind.domain.Slot;
import com.slotmind.domain.SuggestionStatus;
import com.slotmind.repository.*;
import com.slotmind.view.Views.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/** Read-only composite views: schedule (day view / load view), rooms, clinics, patient portal. */
@Service
@Transactional(readOnly = true)
public class QueryService {
    private final ProviderRepository providers;
    private final SlotRepository slots;
    private final AppointmentRepository appointments;
    private final SuggestionRepository suggestions;
    private final RoomRepository rooms;
    private final ClinicRepository clinics;
    private final LoadService loadService;

    public QueryService(ProviderRepository providers, SlotRepository slots, AppointmentRepository appointments,
                        SuggestionRepository suggestions, RoomRepository rooms, ClinicRepository clinics, LoadService loadService) {
        this.providers = providers; this.slots = slots; this.appointments = appointments; this.suggestions = suggestions;
        this.rooms = rooms; this.clinics = clinics; this.loadService = loadService;
    }

    public ScheduleView schedule() {
        Map<String, Integer> load = loadService.bookedToday();
        java.time.LocalDateTime dayStart = java.time.LocalDate.now().atStartOfDay();
        List<Slot> all = slots.search(null, null).stream().filter(s -> !s.getStartTime().isBefore(dayStart)).toList();
        Map<String, Appointment> bySlot = all.isEmpty() ? Map.of()
                : appointments.findBySlotIdIn(all.stream().map(Slot::getId).toList()).stream()
                .collect(Collectors.toMap(a -> a.getSlot().getId(), a -> a, (x, y) -> x));
        Map<String, String> held = new HashMap<>();
        for (RescheduleSuggestion g : suggestions.findByStatus(SuggestionStatus.PENDING)) {
            if (g.getRecommendedSlot() != null) held.putIfAbsent(g.getRecommendedSlot().getId(), g.getId());
        }
        Map<String, List<ScheduleSlot>> perProvider = new LinkedHashMap<>();
        for (Slot s : all) {
            Appointment a = bySlot.get(s.getId());
            ScheduleAppointment sa = a == null ? null : new ScheduleAppointment(a.getId(), a.getPatientName(), a.getType(), a.getStatus());
            perProvider.computeIfAbsent(s.getProvider().getId(), k -> new ArrayList<>())
                    .add(ScheduleSlot.of(SlotView.of(s), sa, held.get(s.getId())));
        }
        return new ScheduleView(providers.findAllWithClinic().stream()
                .map(p -> new ScheduleProvider(ProviderView.of(p, load), perProvider.getOrDefault(p.getId(), List.of())))
                .toList());
    }

    public List<RoomView> rooms() { return rooms.findAllWithClinic().stream().map(RoomView::of).toList(); }

    public List<ClinicView> clinics() { return clinics.findAll().stream().map(ClinicView::of).toList(); }

    public PortalView portal(String appointmentId) {
        Appointment a = appointments.findDetailed(appointmentId).orElseThrow(() -> new NotFoundException("Appointment", appointmentId));
        List<SuggestionView> pending = suggestions.findDetailedByAppointmentId(appointmentId).stream()
                .filter(g -> g.getStatus() == SuggestionStatus.PENDING).map(SuggestionView::of).toList();
        return new PortalView(AppointmentView.of(a), pending);
    }
}
