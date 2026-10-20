package com.slotmind.service;

import com.slotmind.service.DomainEvents.SlotFreed;
import com.slotmind.service.DomainEvents.SuggestionAccepted;
import com.slotmind.service.DomainEvents.WaitlistBooked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Sprint 3 side effects that must never roll back a booking: waitlist auto-fill and notifications. */
@Component
public class AfterCommitListeners {
    private static final Logger log = LoggerFactory.getLogger(AfterCommitListeners.class);
    private final WaitlistService waitlist;
    private final NotificationService notifications;

    public AfterCommitListeners(WaitlistService waitlist, NotificationService notifications) {
        this.waitlist = waitlist; this.notifications = notifications;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSlotFreed(SlotFreed e) {
        try { waitlist.autoFill(e.slotId()); } catch (Exception ex) { log.error("Waitlist auto-fill failed for {}", e.slotId(), ex); }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAccepted(SuggestionAccepted e) {
        try { notifications.notifyBooking(e.appointmentId(), true); } catch (Exception ex) { log.error("Notify failed", ex); }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWaitlistBooked(WaitlistBooked e) {
        try { notifications.notifyBooking(e.appointmentId(), false); } catch (Exception ex) { log.error("Notify failed", ex); }
    }
}
