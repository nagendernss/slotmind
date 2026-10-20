package com.slotmind.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;

import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MockMvc helpers. MockMvc bypasses the servlet context path, so paths here are without the "/api" prefix. */
public abstract class WebTestBase extends IntegrationTestBase {

    protected ResultActions getJson(String path) throws Exception {
        return mvc.perform(get(path).accept(MediaType.APPLICATION_JSON));
    }

    protected ResultActions postJson(String path, Object body) throws Exception {
        var req = post(path).accept(MediaType.APPLICATION_JSON);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : toJson(body));
        return mvc.perform(req);
    }

    protected ResultActions patchJson(String path, Object body) throws Exception {
        var req = patch(path).accept(MediaType.APPLICATION_JSON);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : toJson(body));
        return mvc.perform(req);
    }

    protected ResultActions putJson(String path, Object body) throws Exception {
        return mvc.perform(put(path).accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : toJson(body)));
    }

    protected JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    /** decision.md §3 error contract: {error, message} and nothing else. */
    protected static ResultMatcher[] apiError(int status, String code) {
        return new ResultMatcher[]{
                status().is(status),
                content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON),
                jsonPath("$.error").value(code),
                jsonPath("$.message").isString(),
                jsonPath("$.length()").value(2)};
    }

    protected static Set<String> keys(JsonNode node) {
        Set<String> out = new TreeSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    protected static final Set<String> SLOT_KEYS = Set.of("id", "startTime", "durationMinutes", "status", "providerId",
            "providerName", "roomId", "roomName", "clinicId", "clinicName");
    protected static final Set<String> PROVIDER_KEYS = Set.of("id", "name", "specialty", "status", "clinicId", "clinicName",
            "maxDailyAppointments", "bookedToday");
    protected static final Set<String> APPOINTMENT_KEYS = Set.of("id", "patientName", "contact", "type", "status", "providerId",
            "providerName", "slot");
    protected static final Set<String> SUGGESTION_KEYS = Set.of("id", "appointmentId", "patientName", "appointmentType",
            "appointmentStatus", "triggerReason", "status", "strategy", "fallbackUsed", "crossClinic", "confidence", "reasoning",
            "createdAt", "resolvedAt", "originalSlot", "recommendedSlot", "alternateProviderId");

    protected static void assertSlotShape(JsonNode slot) {
        assertThat(keys(slot)).isEqualTo(SLOT_KEYS);
        assertThat(slot.get("startTime").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?");
    }
}
