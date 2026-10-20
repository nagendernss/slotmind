package com.slotmind.service;

import com.slotmind.domain.*;
import com.slotmind.repository.AppointmentRepository;
import com.slotmind.repository.NotificationRepository;
import com.slotmind.scheduling.Texts;
import com.slotmind.view.Views.NotificationView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Outbox: write the Notification row, then send; a send failure is stored as FAILED and never touches the booking. */
@Service
@Transactional(readOnly = true)
public class NotificationService {
    private final NotificationRepository notifications;
    private final AppointmentRepository appointments;
    private final NotificationSender sender;
    private final ActivityService activity;

    public NotificationService(NotificationRepository notifications, AppointmentRepository appointments,
                               NotificationSender sender, ActivityService activity) {
        this.notifications = notifications; this.appointments = appointments; this.sender = sender; this.activity = activity;
    }

    public List<NotificationView> list() {
        return notifications.findAllByOrderByIdDesc().stream().map(NotificationView::of).toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyBooking(String appointmentId, boolean rescheduled) {
        Appointment a = appointments.findDetailed(appointmentId).orElse(null);
        if (a == null) return;
        String contact = a.getContact();
        NotificationChannel channel = channelFor(contact);
        Slot s = a.getSlot();
        String clinic = s.getProvider().getClinic() == null ? "" : " at our " + s.getProvider().getClinic().getName() + " clinic";
        String message = "Hi " + a.getPatientName() + ", your appointment " + (rescheduled ? "has been moved to " : "is confirmed for ")
                + Texts.when(s.getStartTime(), LocalDateTime.now()) + " with " + s.getProvider().getName() + clinic
                + ". Reply or call us if this time does not work for you.";
        Notification n = new Notification(appointmentId, channel, contact == null ? "front desk log" : contact, message);
        try {
            sender.send(channel, n.getRecipient(), message);
        } catch (Exception ex) {
            n.markFailed();
        }
        notifications.save(n);
        activity.log(n.getStatus() == NotificationStatus.SENT ? ActivityKind.OK : ActivityKind.ERROR,
                "Notification " + channel + " to " + a.getPatientName() + " (" + n.getRecipient() + "): " + n.getStatus());
    }

    static NotificationChannel channelFor(String contact) {
        if (contact == null || contact.isBlank()) return NotificationChannel.LOG;
        if (contact.contains("@")) return NotificationChannel.EMAIL;
        if (contact.chars().anyMatch(Character::isDigit)) return NotificationChannel.SMS;
        return NotificationChannel.LOG;
    }
}
