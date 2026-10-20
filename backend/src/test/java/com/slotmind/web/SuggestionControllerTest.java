package com.slotmind.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.slotmind.domain.*;
import com.slotmind.service.AppointmentService.BookRequest;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.WebTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SuggestionControllerTest extends WebTestBase {

    @Test
    void listByStatusWithContractShape() throws Exception {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.PROVIDER_OFFLINE);
        JsonNode list = body(getJson("/suggestions?status=PENDING").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(g.getId())))
                .andExpect(jsonPath("$[*].status", not(hasItem("ACCEPTED")))));
        JsonNode mine = null;
        for (JsonNode n : list) if (n.get("id").asText().equals(g.getId())) mine = n;
        assertThat(mine).isNotNull();
        assertThat(keys(mine)).isEqualTo(SUGGESTION_KEYS);
        assertThat(mine.get("triggerReason").asText()).isEqualTo("PROVIDER_OFFLINE");
        assertThat(mine.get("patientName").asText()).isEqualTo(w.appt().getPatientName());
        assertThat(mine.get("appointmentType").asText()).isEqualTo("ROUTINE");
        assertThat(mine.get("originalSlot").get("id").asText()).isEqualTo(w.booked().getId());
        assertSlotShape(mine.get("recommendedSlot"));

        getJson("/suggestions?status=ACCEPTED").andExpect(status().isOk()).andExpect(jsonPath("$[*].id", not(hasItem(g.getId()))));
        getJson("/suggestions").andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(g.getId())));
        getJson("/suggestions?status=NOPE").andExpectAll(apiError(400, "VALIDATION"));
    }

    @Test
    void acceptThenSecondAcceptIs409InvalidState() throws Exception {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        patchJson("/suggestions/" + g.getId(), Map.of("status", "ACCEPTED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.appointmentStatus").value("RESCHEDULED"))
                .andExpect(jsonPath("$.resolvedAt").isString())
                .andExpect(jsonPath("$.recommendedSlot.status").value("BOOKED"));
        assertThat(fx.slotStatus(w.booked().getId())).isEqualTo(SlotStatus.OPEN);
        patchJson("/suggestions/" + g.getId(), Map.of("status", "ACCEPTED")).andExpectAll(apiError(409, "INVALID_STATE"));
    }

    @Test
    void acceptOfATakenSlotIs409SlotUnavailable() throws Exception {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        appointmentService.book(new BookRequest("Other", AppointmentType.ROUTINE, w.b().getId(), w.b90().getId(), null));
        patchJson("/suggestions/" + g.getId(), Map.of("status", "ACCEPTED", "actor", "PATIENT"))
                .andExpectAll(apiError(409, "SLOT_UNAVAILABLE"));
        assertThat(fx.suggestionStatus(g.getId())).isEqualTo(SuggestionStatus.PENDING);
        assertThat(fx.apptSlotId(w.appt().getId())).isEqualTo(w.booked().getId());
    }

    @Test
    void reject() throws Exception {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        patchJson("/suggestions/" + g.getId(), Map.of("status", "REJECTED", "actor", "PATIENT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.appointmentStatus").value("RESCHEDULE_PENDING"));
        patchJson("/suggestions/" + g.getId(), Map.of("status", "REJECTED")).andExpectAll(apiError(409, "INVALID_STATE"));
    }

    @Test
    void patchValidation() throws Exception {
        World w = fx.world();
        RescheduleSuggestion g = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        patchJson("/suggestions/" + g.getId(), Map.of()).andExpectAll(apiError(400, "VALIDATION"));
        patchJson("/suggestions/" + g.getId(), Map.of("status", "PENDING")).andExpectAll(apiError(400, "VALIDATION"));
        patchJson("/suggestions/" + g.getId(), Map.of("status", "WHATEVER")).andExpectAll(apiError(400, "VALIDATION"));
        patchJson("/suggestions/" + g.getId(), "[1,2").andExpectAll(apiError(400, "VALIDATION"));
        patchJson("/suggestions/SUG-NOPE", Map.of("status", "ACCEPTED")).andExpectAll(apiError(404, "NOT_FOUND"));
        assertThat(fx.suggestionStatus(g.getId())).isEqualTo(SuggestionStatus.PENDING);
    }
}
