package com.slotmind.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Entities whose only behaviour is construction, a single mutator, or defensive copying. */
class SimpleEntitiesTest {

    @Test
    void clinic() {
        Clinic c = new Clinic("CLN-001", "Downtown");
        assertThat(c.getId()).isEqualTo("CLN-001");
        assertThat(c.getName()).isEqualTo("Downtown");
    }

    @Test
    void roomCopiesEquipmentDefensively() {
        Clinic c = new Clinic("CLN-001", "Downtown");
        Set<String> eq = new HashSet<>(Set.of("TRIAGE"));
        Room r = new Room("RM-001", "Exam 1", c, eq);
        eq.add("XRAY");
        assertThat(r.getId()).isEqualTo("RM-001");
        assertThat(r.getName()).isEqualTo("Exam 1");
        assertThat(r.getClinic()).isSameAs(c);
        assertThat(r.getEquipment()).containsExactly("TRIAGE");
        // immutable input is accepted too (copied into a mutable set for JPA)
        Room r2 = new Room("RM-002", "Exam 2", c, Set.of());
        r2.getEquipment().add("PEDIATRIC");
        assertThat(r2.getEquipment()).containsExactly("PEDIATRIC");
    }

    @Test
    void appointmentTypeRuleCopiesEquipmentDefensively() {
        Set<String> eq = new HashSet<>(Set.of("TRIAGE"));
        AppointmentTypeRule rule = new AppointmentTypeRule(AppointmentType.URGENT, 45, eq);
        eq.clear();
        assertThat(rule.getType()).isEqualTo(AppointmentType.URGENT);
        assertThat(rule.getDurationMinutes()).isEqualTo(45);
        assertThat(rule.getRequiredEquipment()).containsExactly("TRIAGE");
    }

    @Test
    void patientPreferenceUpdateBecomesExplicit() throws InterruptedException {
        PatientPreference p = new PatientPreference("Maria", PreferredWindow.MORNING, PreferenceSource.INFERRED);
        assertThat(p.getPatientName()).isEqualTo("Maria");
        assertThat(p.getPreferredWindow()).isEqualTo(PreferredWindow.MORNING);
        assertThat(p.getSource()).isEqualTo(PreferenceSource.INFERRED);
        LocalDateTime created = p.getUpdatedAt();
        assertThat(created).isNotNull();

        p.update(PreferredWindow.AFTERNOON);
        assertThat(p.getPreferredWindow()).isEqualTo(PreferredWindow.AFTERNOON);
        assertThat(p.getSource()).isEqualTo(PreferenceSource.EXPLICIT);
        assertThat(p.getUpdatedAt()).isAfterOrEqualTo(created);
    }

    @Test
    void notificationStartsSentAndCanFail() {
        Notification n = new Notification("APT-001", NotificationChannel.EMAIL, "a@b.c", "moved");
        assertThat(n.getId()).isNull();
        assertThat(n.getAppointmentId()).isEqualTo("APT-001");
        assertThat(n.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(n.getRecipient()).isEqualTo("a@b.c");
        assertThat(n.getMessage()).isEqualTo("moved");
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getCreatedAt()).isNotNull();
        n.markFailed();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void activityEventKeepsShortMessages() {
        ActivityEvent e = new ActivityEvent(ActivityKind.WARN, "hello");
        assertThat(e.getId()).isNull();
        assertThat(e.getKind()).isEqualTo(ActivityKind.WARN);
        assertThat(e.getMessage()).isEqualTo("hello");
        assertThat(e.getAt()).isNotNull();
        assertThat(new ActivityEvent(ActivityKind.INFO, null).getMessage()).isNull();
    }

    @Test
    void activityEventTruncatesToColumnLength() {
        String exactly = "x".repeat(2000);
        assertThat(new ActivityEvent(ActivityKind.INFO, exactly).getMessage()).isEqualTo(exactly);

        String tooLong = "y".repeat(2001);
        String stored = new ActivityEvent(ActivityKind.INFO, tooLong).getMessage();
        assertThat(stored).hasSize(2000).endsWith("...").startsWith("yyy");
    }

    @Test
    void exceptionCarriesMessage() {
        assertThat(new IllegalStateTransitionException("boom")).isInstanceOf(RuntimeException.class).hasMessage("boom");
    }
}
