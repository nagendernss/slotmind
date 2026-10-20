package com.slotmind.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.slotmind.domain.*;
import com.slotmind.support.Fixture;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.WebTestBase;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProviderSlotControllerTest extends WebTestBase {

    @Test
    void listProvidersWithLoad() throws Exception {
        Clinic c = fx.clinic();
        Provider p = fx.provider(c, Specialty.SPECIALIST, ProviderStatus.BUSY, 6);
        fx.slotAt(p, null, LocalDate.now().atStartOfDay().plusSeconds(5), 30, SlotStatus.BOOKED);
        JsonNode list = body(getJson("/providers").andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(p.getId()))));
        JsonNode mine = null;
        for (JsonNode n : list) if (n.get("id").asText().equals(p.getId())) mine = n;
        assertThat(keys(mine)).isEqualTo(PROVIDER_KEYS);
        assertThat(mine.get("bookedToday").asInt()).isEqualTo(1);
        assertThat(mine.get("maxDailyAppointments").asInt()).isEqualTo(6);
        assertThat(mine.get("specialty").asText()).isEqualTo("SPECIALIST");
        assertThat(mine.get("status").asText()).isEqualTo("BUSY");
        assertThat(mine.get("clinicName").asText()).isEqualTo(c.getName());
    }

    @Test
    void offlineReturnsImmediatelyAndTheLoopProposesAfterwards() throws Exception {
        World w = fx.world();
        patchJson("/providers/" + w.a().getId() + "/status", Map.of("status", "OFFLINE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.id").value(w.a().getId()));
        Fixture.await("suggestion from the agentic loop",
                () -> suggestions.findByAppointmentIdAndStatus(w.appt().getId(), SuggestionStatus.PENDING).size() == 1);
        getJson("/suggestions?status=PENDING").andExpect(jsonPath("$[?(@.appointmentId == '" + w.appt().getId() + "')].triggerReason")
                .value(hasItem("PROVIDER_OFFLINE")));
    }

    @Test
    void providerStatusErrors() throws Exception {
        Provider p = fx.provider(fx.clinic(), Specialty.GENERAL);
        patchJson("/providers/" + p.getId() + "/status", Map.of()).andExpectAll(apiError(400, "VALIDATION"));
        patchJson("/providers/" + p.getId() + "/status", Map.of("status", "ON_VACATION")).andExpectAll(apiError(400, "VALIDATION"));
        patchJson("/providers/" + p.getId() + "/status", null).andExpectAll(apiError(400, "VALIDATION"));
        patchJson("/providers/PRV-NOPE/status", Map.of("status", "BUSY")).andExpectAll(apiError(404, "NOT_FOUND"));
        patchJson("/providers/" + p.getId() + "/status", Map.of("status", "BUSY")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BUSY"));
    }

    @Test
    void listSlotsWithFilters() throws Exception {
        World w = fx.world();
        JsonNode list = body(getJson("/slots?providerId=" + w.b().getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(w.b90().getId()))
                .andExpect(jsonPath("$[1].id").value(w.b240().getId())));
        assertSlotShape(list.get(0));
        getJson("/slots?providerId=" + w.a().getId() + "&status=BOOKED").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(w.booked().getId()));
        getJson("/slots?status=OPEN&providerId=").andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(w.b90().getId())))
                .andExpect(jsonPath("$[*].id", not(hasItem(w.booked().getId()))));
        getJson("/slots?status=FREE").andExpectAll(apiError(400, "VALIDATION"));
    }

    @Test
    void slotStatusTransitions() throws Exception {
        World w = fx.world();
        patchJson("/slots/" + w.b240().getId() + "/status", Map.of("status", "CANCELLED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        patchJson("/slots/" + w.b240().getId() + "/status", Map.of("status", "CANCELLED")).andExpectAll(apiError(409, "INVALID_STATE"));
        patchJson("/slots/" + w.booked().getId() + "/status", Map.of("status", "OPEN")).andExpectAll(apiError(409, "INVALID_STATE"));
        patchJson("/slots/" + w.b90().getId() + "/status", Map.of("status", "BOOKED")).andExpectAll(apiError(400, "VALIDATION"));
        patchJson("/slots/" + w.b90().getId() + "/status", Map.of()).andExpectAll(apiError(400, "VALIDATION"));
        patchJson("/slots/SLT-NOPE/status", Map.of("status", "CANCELLED")).andExpectAll(apiError(404, "NOT_FOUND"));
        patchJson("/slots/" + w.b90().getId() + "/status", Map.of("status", "OPEN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void cancellingABookedSlotReturnsImmediatelyAndTheLoopProposes() throws Exception {
        World w = fx.world();
        patchJson("/slots/" + w.booked().getId() + "/status", Map.of("status", "CANCELLED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        Fixture.await("slot-cancelled suggestion",
                () -> suggestions.findByAppointmentIdAndStatus(w.appt().getId(), SuggestionStatus.PENDING).size() == 1);
        assertThat(suggestions.findByAppointmentIdAndStatus(w.appt().getId(), SuggestionStatus.PENDING).get(0).getTriggerReason())
                .isEqualTo(TriggerReason.SLOT_CANCELLED);
    }
}
