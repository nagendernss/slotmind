package com.slotmind.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slotmind.repository.*;
import com.slotmind.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One shared Spring context for all service/web tests (same annotations => cached context, one H2 db, no
 * @DirtiesContext). A real random port is started too, for SSE and the concurrency race over HTTP.
 * Tests isolate themselves through {@link Fixture}'s unique ids.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({Fixture.class, SwitchableNotificationSender.class, DomainEventRecorder.class})
public abstract class IntegrationTestBase {
    @Autowired protected Fixture fx;
    @Autowired protected DomainEventRecorder recorder;
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected SwitchableNotificationSender sender;
    @LocalServerPort protected int port;

    @Autowired protected AppointmentRepository appointments;
    @Autowired protected SlotRepository slots;
    @Autowired protected ProviderRepository providers;
    @Autowired protected SuggestionRepository suggestions;
    @Autowired protected WaitlistRepository waitlist;
    @Autowired protected NotificationRepository notifications;
    @Autowired protected ActivityRepository activityRepo;

    @Autowired protected AppointmentService appointmentService;
    @Autowired protected ProviderService providerService;
    @Autowired protected SlotService slotService;
    @Autowired protected SuggestionService suggestionService;
    @Autowired protected RescheduleOrchestrator orchestrator;
    @Autowired protected WaitlistService waitlistService;
    @Autowired protected NotificationService notificationService;
    @Autowired protected PreferenceService preferenceService;
    @Autowired protected LoadService loadService;
    @Autowired protected QueryService queryService;
    @Autowired protected ActivityService activityService;

    protected String baseUrl() { return "http://localhost:" + port + "/api"; }

    protected String toJson(Object o) {
        try { return json.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
