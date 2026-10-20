package com.slotmind.support;

import com.slotmind.domain.NotificationChannel;
import com.slotmind.service.NotificationSender;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every send and can be told to fail, so outbox FAILED handling is testable without mocks. */
@TestComponent
@Primary
public class SwitchableNotificationSender implements NotificationSender {
    public record Sent(NotificationChannel channel, String recipient, String message) {}

    public final List<Sent> sent = new CopyOnWriteArrayList<>();
    /** Recipients for which send() throws. */
    public final List<String> failFor = new CopyOnWriteArrayList<>();

    @Override
    public void send(NotificationChannel channel, String recipient, String message) {
        if (failFor.contains(recipient)) throw new IllegalStateException("SMTP down for " + recipient);
        sent.add(new Sent(channel, recipient, message));
    }
}
