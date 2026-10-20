package com.slotmind.support;

import com.slotmind.service.DomainEvents;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every domain event at publish time (synchronously, inside the publishing transaction). */
@TestComponent
public class DomainEventRecorder {
    public final List<Object> events = new CopyOnWriteArrayList<>();

    @EventListener
    public void on(DomainEvents.ProviderWentOffline e) { events.add(e); }

    @EventListener
    public void on(DomainEvents.SlotCancelled e) { events.add(e); }

    @EventListener
    public void on(DomainEvents.SlotFreed e) { events.add(e); }

    @EventListener
    public void on(DomainEvents.SuggestionAccepted e) { events.add(e); }

    @EventListener
    public void on(DomainEvents.WaitlistBooked e) { events.add(e); }

    public <T> List<T> of(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }
}
