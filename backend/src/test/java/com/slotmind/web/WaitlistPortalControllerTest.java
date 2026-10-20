package com.slotmind.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.slotmind.domain.*;
import com.slotmind.support.Fixture;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.WebTestBase;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WaitlistPortalControllerTest extends WebTestBase {

    private Map<String, Object> entry(String clinicId) {
        Map<String, Object> m = new HashMap<>();
        m.put("patientName", "Web Waiter");
        m.put("contact", "+1 555 0123");
        m.put("type", "ROUTINE");
        m.put("specialty", "GENERAL");
        m.put("preferredWindow", "ANY");
        m.put("clinicId", clinicId);
        return m;
    }

    @Test
    void addListAcceptAndErrors() throws Exception {
        Clinic c = fx.clinic();
        Provider p = fx.provider(c, Specialty.GENERAL);
        JsonNode created = body(postJson("/waitlist", entry(c.getId())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.clinicId").value(c.getId())));
        assertThat(keys(created)).containsExactlyInAnyOrder("id", "patientName", "contact", "type", "specialty", "preferredWindow",
                "clinicId", "status", "createdAt", "offeredSlot", "bookedAppointmentId");
        String id = created.get("id").asText();
        getJson("/waitlist").andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(id)));

        postJson("/waitlist/" + id + "/accept", null).andExpectAll(apiError(409, "INVALID_STATE"));
        postJson("/waitlist/" + id + "/decline", null).andExpectAll(apiError(409, "INVALID_STATE"));
        postJson("/waitlist/WL-NOPE/accept", null).andExpectAll(apiError(404, "NOT_FOUND"));
        postJson("/waitlist/WL-NOPE/decline", null).andExpectAll(apiError(404, "NOT_FOUND"));

        Slot s = fx.slotAt(p, null, LocalDate.now().plusDays(1).atTime(10, 0), 30, SlotStatus.OPEN);
        waitlistService.autoFill(s.getId());
        getJson("/waitlist").andExpect(jsonPath("$[?(@.id == '" + id + "')].offeredSlot.id").value(hasItem(s.getId())));
        postJson("/waitlist/" + id + "/accept", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.bookedAppointmentId").isString())
                .andExpect(jsonPath("$.offeredSlot.status").value("BOOKED"));
    }

    @Test
    void declineMovesTheOfferOn() throws Exception {
        Clinic c = fx.clinic();
        Provider p = fx.provider(c, Specialty.GENERAL);
        String first = body(postJson("/waitlist", entry(c.getId()))).get("id").asText();
        String second = body(postJson("/waitlist", entry(c.getId()))).get("id").asText();
        // make sure 'first' has waited longest
        tx.executeWithoutResult(t -> {
            var e = waitlist.findById(first).orElseThrow();
            org.springframework.test.util.ReflectionTestUtils.setField(e, "createdAt", LocalDateTime.now().minusDays(2));
        });
        Slot s = fx.slotAt(p, null, LocalDate.now().plusDays(1).atTime(10, 0), 30, SlotStatus.OPEN);
        waitlistService.autoFill(s.getId());
        postJson("/waitlist/" + first + "/decline", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
        Fixture.await("second gets the offer", () -> waitlist.findById(second).orElseThrow().getStatus() == WaitlistStatus.OFFERED);
    }

    @Test
    void addValidation() throws Exception {
        Map<String, Object> bad = entry(null);
        bad.remove("specialty");
        postJson("/waitlist", bad).andExpectAll(apiError(400, "VALIDATION"));
        Map<String, Object> badEnum = entry(null);
        badEnum.put("type", "SOMETIME");
        postJson("/waitlist", badEnum).andExpectAll(apiError(400, "VALIDATION"));
        postJson("/waitlist", entry("CLN-NOPE")).andExpectAll(apiError(404, "NOT_FOUND"));
    }

    @Test
    void portalViewShowsAppointmentAndPendingSuggestions() throws Exception {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.SLOT_CANCELLED);
        JsonNode body = body(getJson("/portal/appointments/" + w.appt().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.appointment.id").value(w.appt().getId()))
                .andExpect(jsonPath("$.suggestions.length()").value(1))
                .andExpect(jsonPath("$.suggestions[0].id").value(g.getId())));
        assertThat(keys(body)).containsExactlyInAnyOrder("appointment", "suggestions");
        assertThat(keys(body.get("appointment"))).isEqualTo(APPOINTMENT_KEYS);
        assertThat(keys(body.get("suggestions").get(0))).isEqualTo(SUGGESTION_KEYS);
        getJson("/portal/appointments/APT-NOPE").andExpectAll(apiError(404, "NOT_FOUND"));
    }

    @Test
    void portalRescheduleRequestsANewTimeAndPatientCanAccept() throws Exception {
        World w = fx.world();
        JsonNode s = body(postJson("/portal/appointments/" + w.appt().getId() + "/reschedule", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerReason").value("INITIAL"))
                .andExpect(jsonPath("$.status").value("PENDING")));
        getJson("/portal/appointments/" + w.appt().getId()).andExpect(jsonPath("$.suggestions[0].id").value(s.get("id").asText()))
                .andExpect(jsonPath("$.appointment.status").value("RESCHEDULE_PENDING"));
        patchJson("/suggestions/" + s.get("id").asText(), Map.of("status", "ACCEPTED", "actor", "PATIENT")).andExpect(status().isOk());
        getJson("/portal/appointments/" + w.appt().getId()).andExpect(jsonPath("$.suggestions.length()").value(0))
                .andExpect(jsonPath("$.appointment.status").value("RESCHEDULED"))
                .andExpect(jsonPath("$.appointment.slot.id").value(s.get("recommendedSlot").get("id").asText()));
        assertThat(suggestions.findById(s.get("id").asText()).orElseThrow().getResolvedBy()).isEqualTo("PATIENT");
    }

    @Test
    void portalRescheduleKeepsTheOriginalTriggerAndRejectsCompleted() throws Exception {
        World w = fx.world();
        orchestrator.process(w.appt().getId(), TriggerReason.PROVIDER_OFFLINE);
        postJson("/portal/appointments/" + w.appt().getId() + "/reschedule", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerReason").value("PROVIDER_OFFLINE"));

        World done = fx.world();
        appointmentService.complete(done.appt().getId());
        postJson("/portal/appointments/" + done.appt().getId() + "/reschedule", null).andExpectAll(apiError(409, "INVALID_STATE"));
        postJson("/portal/appointments/APT-NOPE/reschedule", null).andExpectAll(apiError(404, "NOT_FOUND"));
    }
}
