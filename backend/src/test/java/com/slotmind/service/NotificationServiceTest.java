package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.support.Fixture.World;
import com.slotmind.support.IntegrationTestBase;
import com.slotmind.view.Views.NotificationView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceTest extends IntegrationTestBase {

    private List<Notification> rowsFor(String apptId) {
        return notifications.findAllByOrderByIdDesc().stream().filter(n -> n.getAppointmentId().equals(apptId)).toList();
    }

    private Appointment apptWithContact(World w, String contact) {
        Slot s = fx.slot(w.b(), w.room(), 500, 30, SlotStatus.BOOKED);
        return fx.appointment(w.b(), s, AppointmentType.ROUTINE, contact, AppointmentStatus.CONFIRMED);
    }

    @Test
    void channelIsChosenFromTheContact() {
        assertThat(NotificationService.channelFor("a@b.com")).isEqualTo(NotificationChannel.EMAIL);
        assertThat(NotificationService.channelFor("+1-555-0142")).isEqualTo(NotificationChannel.SMS);
        assertThat(NotificationService.channelFor("call 0800")).isEqualTo(NotificationChannel.SMS);
        assertThat(NotificationService.channelFor(null)).isEqualTo(NotificationChannel.LOG);
        assertThat(NotificationService.channelFor("   ")).isEqualTo(NotificationChannel.LOG);
        assertThat(NotificationService.channelFor("ask at desk")).isEqualTo(NotificationChannel.LOG);
    }

    @Test
    void emailBookingConfirmationWritesOutboxRowAndSends() {
        World w = fx.world();
        notificationService.notifyBooking(w.appt().getId(), false);
        List<Notification> rows = rowsFor(w.appt().getId());
        assertThat(rows).hasSize(1);
        Notification n = rows.get(0);
        assertThat(n.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(n.getRecipient()).isEqualTo("patient@example.com");
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getCreatedAt()).isNotNull();
        assertThat(n.getMessage()).startsWith("Hi " + w.appt().getPatientName() + ", your appointment is confirmed for")
                .contains(w.a().getName()).contains("at our " + w.clinic().getName() + " clinic")
                .doesNotContain("SLT-").doesNotContain("PRV-");
        assertThat(sender.sent).anyMatch(s -> s.recipient().equals("patient@example.com") && s.message().equals(n.getMessage()));
    }

    @Test
    void smsAndLogChannels() {
        World w = fx.world();
        Appointment sms = apptWithContact(w, "+1 555 0199");
        Appointment none = apptWithContact(w, null);
        notificationService.notifyBooking(sms.getId(), true);
        notificationService.notifyBooking(none.getId(), true);
        assertThat(rowsFor(sms.getId())).singleElement().satisfies(n -> {
            assertThat(n.getChannel()).isEqualTo(NotificationChannel.SMS);
            assertThat(n.getMessage()).contains("has been moved to");
        });
        assertThat(rowsFor(none.getId())).singleElement().satisfies(n -> {
            assertThat(n.getChannel()).isEqualTo(NotificationChannel.LOG);
            assertThat(n.getRecipient()).isEqualTo("front desk log");
            assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        });
    }

    @Test
    void sendFailureIsStoredAsFailedAndNeverThrows() {
        World w = fx.world();
        String contact = "broken-" + w.appt().getId() + "@example.com";
        Appointment a = apptWithContact(w, contact);
        sender.failFor.add(contact);
        try {
            notificationService.notifyBooking(a.getId(), true);
        } finally {
            sender.failFor.remove(contact);
        }
        assertThat(rowsFor(a.getId())).singleElement().extracting(Notification::getStatus).isEqualTo(NotificationStatus.FAILED);
        assertThat(activityRepo.findTop100ByOrderByIdDesc()).anyMatch(x -> x.getKind() == ActivityKind.ERROR
                && x.getMessage().contains(contact) && x.getMessage().contains("FAILED"));
        // the booking itself is untouched
        assertThat(fx.apptStatus(a.getId())).isEqualTo(AppointmentStatus.CONFIRMED);
    }

    @Test
    void unknownAppointmentIsIgnored() {
        long before = notifications.count();
        notificationService.notifyBooking("APT-NOPE", true);
        assertThat(notifications.count()).isEqualTo(before);
    }

    @Test
    void listIsNewestFirst() {
        World w = fx.world();
        notificationService.notifyBooking(w.appt().getId(), false);
        notificationService.notifyBooking(w.appt().getId(), true);
        List<NotificationView> list = notificationService.list();
        assertThat(list).extracting(NotificationView::id).isSortedAccordingTo((a, b) -> Long.compare(b, a));
        List<NotificationView> mine = list.stream().filter(n -> n.appointmentId().equals(w.appt().getId())).toList();
        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).message()).contains("has been moved");
        assertThat(mine.get(1).message()).contains("is confirmed");
    }
}
