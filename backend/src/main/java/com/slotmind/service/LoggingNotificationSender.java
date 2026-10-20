package com.slotmind.service;

import com.slotmind.domain.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LoggingNotificationSender implements NotificationSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(NotificationChannel channel, String recipient, String message) {
        log.info("[notify {} -> {}] {}", channel, recipient, message);
    }
}
