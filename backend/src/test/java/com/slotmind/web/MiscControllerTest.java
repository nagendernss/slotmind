package com.slotmind.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.slotmind.domain.*;
import com.slotmind.scheduling.StrategyRegistry;
import com.slotmind.support.Fixture;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.WebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** /schedule, /rooms, /clinics, /activity, /notifications, /config/strategy, /preferences. */
class MiscControllerTest extends WebTestBase {

    @Autowired StrategyRegistry registry;

    @Test
    void schedule() throws Exception {
        World w = fx.world();
        RescheduleSuggestion held = fx.pendingSuggestion(w.appt().getId(), w.b90().getId(), TriggerReason.INITIAL);
        JsonNode body = body(getJson("/schedule").andExpect(status().isOk()));
        assertThat(keys(body)).containsExactly("providers");
        JsonNode a = null, b = null;
        for (JsonNode p : body.get("providers")) {
            String id = p.get("provider").get("id").asText();
            if (id.equals(w.a().getId())) a = p;
            if (id.equals(w.b().getId())) b = p;
        }
        assertThat(keys(a)).containsExactlyInAnyOrder("provider", "slots");
        assertThat(keys(a.get("provider"))).isEqualTo(PROVIDER_KEYS);
        JsonNode bookedSlot = a.get("slots").get(0);
        Set<String> scheduleSlotKeys = new java.util.HashSet<>(SLOT_KEYS);
        scheduleSlotKeys.addAll(Set.of("appointment", "heldBySuggestionId"));
        assertThat(keys(bookedSlot)).isEqualTo(scheduleSlotKeys);
        assertThat(keys(bookedSlot.get("appointment"))).containsExactlyInAnyOrder("id", "patientName", "type", "status");
        assertThat(bookedSlot.get("appointment").get("id").asText()).isEqualTo(w.appt().getId());
        assertThat(bookedSlot.get("heldBySuggestionId").isNull()).isTrue();
        assertThat(b.get("slots").get(0).get("heldBySuggestionId").asText()).isEqualTo(held.getId());
        assertThat(b.get("slots").get(0).get("appointment").isNull()).isTrue();
    }

    @Test
    void roomsAndClinics() throws Exception {
        Clinic c = fx.clinic();
        Room r = fx.room(c, "TRIAGE", "EXAM");
        getJson("/rooms").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + r.getId() + "')].clinicId").value(hasItem(c.getId())))
                .andExpect(jsonPath("$[?(@.id == '" + r.getId() + "')].equipment[*]").value(contains("EXAM", "TRIAGE")));
        getJson("/clinics").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + c.getId() + "')].name").value(hasItem(c.getName())))
                .andExpect(jsonPath("$[*].id", hasItem("CLN-001")));
    }

    @Test
    void activityFeed() throws Exception {
        String marker = "web-activity-" + Fixture.uid();
        activityService.log(ActivityKind.OK, marker);
        JsonNode list = body(getJson("/activity").andExpect(status().isOk()));
        JsonNode mine = null;
        for (JsonNode n : list) if (n.get("message").asText().equals(marker)) mine = n;
        assertThat(mine).isNotNull();
        assertThat(keys(mine)).containsExactlyInAnyOrder("id", "at", "kind", "message");
        assertThat(mine.get("kind").asText()).isEqualTo("OK");
        assertThat(list.size()).isLessThanOrEqualTo(100);
    }

    @Test
    void notificationsLog() throws Exception {
        World w = fx.world();
        notificationService.notifyBooking(w.appt().getId(), false);
        JsonNode list = body(getJson("/notifications").andExpect(status().isOk()));
        JsonNode mine = null;
        for (JsonNode n : list) if (n.get("appointmentId").asText().equals(w.appt().getId())) mine = n;
        assertThat(mine).isNotNull();
        assertThat(keys(mine)).containsExactlyInAnyOrder("id", "appointmentId", "channel", "recipient", "message", "status", "createdAt");
        assertThat(mine.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(mine.get("status").asText()).isEqualTo("SENT");
    }

    @Test
    void strategyGetPutAndUnknownIs400() throws Exception {
        String before = registry.activeKey();
        try {
            getJson("/config/strategy").andExpect(status().isOk())
                    .andExpect(jsonPath("$.strategy").value(before))
                    .andExpect(jsonPath("$.available").value(contains("AI", "RULE_BASED", "ROOM_CONSTRAINT")))
                    .andExpect(jsonPath("$.length()").value(2));
            putJson("/config/strategy", Map.of("strategy", "room_constraint")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.strategy").value("ROOM_CONSTRAINT"));
            getJson("/config/strategy").andExpect(jsonPath("$.strategy").value("ROOM_CONSTRAINT"));

            // the switch is live: a manual suggestion now uses the new strategy
            World w = fx.world();
            postJson("/appointments/" + w.appt().getId() + "/suggest", null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.strategy").value("ROOM_CONSTRAINT"));

            putJson("/config/strategy", Map.of("strategy", "MAGIC")).andExpectAll(apiError(400, "VALIDATION"));
            putJson("/config/strategy", Map.of()).andExpectAll(apiError(400, "VALIDATION"));
            putJson("/config/strategy", "nope").andExpectAll(apiError(400, "VALIDATION"));
            getJson("/config/strategy").andExpect(jsonPath("$.strategy").value("ROOM_CONSTRAINT"));
        } finally {
            registry.setActive(before);
        }
    }

    @Test
    void preferences() throws Exception {
        String name = "Pref Person " + Fixture.uid();
        String path = "/preferences/" + name;
        getJson(path).andExpect(status().isOk())
                .andExpect(jsonPath("$.patientName").value(name))
                .andExpect(jsonPath("$.preferredWindow").value("ANY"))
                .andExpect(jsonPath("$.source").value("DEFAULT"));
        putJson(path, Map.of("preferredWindow", "MORNING")).andExpect(status().isOk())
                .andExpect(jsonPath("$.preferredWindow").value("MORNING"))
                .andExpect(jsonPath("$.source").value("EXPLICIT"));
        getJson(path).andExpect(jsonPath("$.preferredWindow").value("MORNING")).andExpect(jsonPath("$.source").value("EXPLICIT"));
        putJson(path, Map.of()).andExpectAll(apiError(400, "VALIDATION"));
        putJson(path, Map.of("preferredWindow", "EVENING")).andExpectAll(apiError(400, "VALIDATION"));
        getJson("/preferences/Emily Watson").andExpect(status().isOk()); // seed: explicit afternoon
    }
}
