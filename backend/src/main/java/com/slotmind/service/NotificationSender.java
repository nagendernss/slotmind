package com.slotmind.service;

import com.slotmind.domain.NotificationChannel;

/** Outbound channel (SMS/email). Real providers plug in here; default logs only. */
public interface NotificationSender {
    void send(NotificationChannel channel, String recipient, String message);
}
