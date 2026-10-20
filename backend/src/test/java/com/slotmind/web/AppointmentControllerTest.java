package com.slotmind.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.slotmind.domain.*;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.WebTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AppointmentControllerTest extends WebTestBase {

    private Map<String, Object> bookBody(String providerId, String slotId, String type) {
        return Map.of("patientName", "Web Patient", "type", type, "providerId", providerId, "slotId", slotId,
                "contact", "web@example.com");
    }

    @Test
    void postBooksAndReturns201WithAppointmentView() throws Exception {
        World w = fx.world();
        var r = postJson("/appointments", bookBody(w.b().getId(), w.b90().getId(), "ROUTINE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patientName").value("Web Patient"))
                .andExpect(jsonPath("$.type").value("ROUTINE"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.providerId").value(w.b().getId()))
                .andExpect(jsonPath("$.providerName").value(w.b().getName()))
                .andExpect(jsonPath("$.contact").value("web@example.com"))
                .andExpect(jsonPath("$.slot.id").value(w.b90().getId()))
                .andExpect(jsonPath("$.slot.status").value("BOOKED"))
                .andExpect(jsonPath("$.slot.durationMinutes").value(30))
                .andExpect(jsonPath("$.slot.clinicId").value(w.clinic().getId()));
        JsonNode body = body(r);
        assertThat(keys(body)).isEqualTo(APPOINTMENT_KEYS);
        assertSlotShape(body.get("slot"));
        assertThat(fx.slotStatus(w.b90().getId())).isEqualTo(SlotStatus.BOOKED);
    }

    @Test
    void postOnATakenSlotIs409SlotUnavailable() throws Exception {
        World w = fx.world();
        postJson("/appointments", bookBody(w.a().getId(), w.booked().getId(), "ROUTINE"))
                .andExpectAll(apiError(409, "SLOT_UNAVAILABLE"));
    }

    @Test
    void postValidationErrorsAre400AndUnknownIdsAre404() throws Exception {
        World w = fx.world();
        postJson("/appointments", Map.of("patientName", "X", "type", "ROUTINE")).andExpectAll(apiError(400, "VALIDATION"));
        postJson("/appointments", "{not json").andExpectAll(apiError(400, "VALIDATION"));
        postJson("/appointments", bookBody(w.b().getId(), w.b90().getId(), "SURGERY")).andExpectAll(apiError(400, "VALIDATION"));
        postJson("/appointments", bookBody(w.b().getId(), w.b90().getId(), "URGENT")) // 30 min < 45
                .andExpectAll(apiError(400, "VALIDATION"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("45")));
        postJson("/appointments", bookBody("PRV-NOPE", w.b90().getId(), "ROUTINE")).andExpectAll(apiError(404, "NOT_FOUND"));
        postJson("/appointments", bookBody(w.b().getId(), "SLT-NOPE", "ROUTINE")).andExpectAll(apiError(404, "NOT_FOUND"));
        assertThat(fx.slotStatus(w.b90().getId())).isEqualTo(SlotStatus.OPEN);
    }

    @Test
    void getListFiltersByStatus() throws Exception {
        World w = fx.world();
        getJson("/appointments?status=CONFIRMED").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(w.appt().getId())))
                .andExpect(jsonPath("$[*].status", not(hasItem("COMPLETED"))));
        getJson("/appointments?status=COMPLETED").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", not(hasItem(w.appt().getId()))));
        getJson("/appointments").andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(w.appt().getId())));
        getJson("/appointments?status=BOGUS").andExpectAll(apiError(400, "VALIDATION"));
    }

    @Test
    void getOne() throws Exception {
        World w = fx.world();
        JsonNode b = body(getJson("/appointments/" + w.appt().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(w.appt().getId()))
                .andExpect(jsonPath("$.slot.roomName").value(w.room().getName())));
        assertThat(keys(b)).isEqualTo(APPOINTMENT_KEYS);
        getJson("/appointments/APT-NOPE").andExpectAll(apiError(404, "NOT_FOUND"));
    }

    @Test
    void completeAndCompleteAgain() throws Exception {
        World w = fx.world();
        patchJson("/appointments/" + w.appt().getId() + "/complete", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        patchJson("/appointments/" + w.appt().getId() + "/complete", null).andExpectAll(apiError(409, "INVALID_STATE"));
        patchJson("/appointments/APT-NOPE/complete", null).andExpectAll(apiError(404, "NOT_FOUND"));
    }

    @Test
    void suggestIsSynchronousAndInitial() throws Exception {
        World w = fx.world();
        JsonNode b = body(postJson("/appointments/" + w.appt().getId() + "/suggest?strategy=RULE_BASED", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerReason").value("INITIAL"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.strategy").value("RULE_BASED"))
                .andExpect(jsonPath("$.fallbackUsed").value(false))
                .andExpect(jsonPath("$.appointmentId").value(w.appt().getId()))
                .andExpect(jsonPath("$.appointmentStatus").value("RESCHEDULE_PENDING"))
                .andExpect(jsonPath("$.recommendedSlot.id").value(w.a180().getId())));
        assertThat(keys(b)).isEqualTo(SUGGESTION_KEYS);
        assertThat(b.get("resolvedAt").isNull()).isTrue(); // present but null
        assertThat(b.get("alternateProviderId").isNull()).isTrue();
        assertSlotShape(b.get("originalSlot"));
        assertSlotShape(b.get("recommendedSlot"));
        assertThat(b.get("confidence").isNumber()).isTrue();

        // default strategy (mock AI) also works
        postJson("/appointments/" + w.appt().getId() + "/suggest", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("AI"));
    }

    @Test
    void suggestErrors() throws Exception {
        World w = fx.world();
        postJson("/appointments/" + w.appt().getId() + "/suggest?strategy=NOPE", null).andExpectAll(apiError(400, "VALIDATION"));
        assertThat(fx.apptStatus(w.appt().getId())).isEqualTo(AppointmentStatus.CONFIRMED);
        postJson("/appointments/APT-NOPE/suggest", null).andExpectAll(apiError(404, "NOT_FOUND"));
        patchJson("/appointments/" + w.appt().getId() + "/complete", null).andExpect(status().isOk());
        postJson("/appointments/" + w.appt().getId() + "/suggest", null).andExpectAll(apiError(409, "INVALID_STATE"));
    }
}
