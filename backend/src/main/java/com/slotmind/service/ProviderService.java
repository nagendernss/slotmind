package com.slotmind.service;

import com.slotmind.domain.ActivityKind;
import com.slotmind.domain.Provider;
import com.slotmind.domain.ProviderStatus;
import com.slotmind.repository.ProviderRepository;
import com.slotmind.view.Views.ProviderView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class ProviderService {
    private final ProviderRepository providers;
    private final LoadService loadService;
    private final ApplicationEventPublisher events;
    private final ActivityService activity;

    public ProviderService(ProviderRepository providers, LoadService loadService, ApplicationEventPublisher events,
                           ActivityService activity) {
        this.providers = providers; this.loadService = loadService; this.events = events; this.activity = activity;
    }

    public List<ProviderView> list() {
        Map<String, Integer> load = loadService.bookedToday();
        return providers.findAllWithClinic().stream().map(p -> ProviderView.of(p, load)).toList();
    }

    /** OFFLINE publishes ProviderWentOffline; the agentic loop runs after commit, so this returns immediately. */
    @Transactional
    public ProviderView changeStatus(String id, ProviderStatus status) {
        if (status == null) throw new IllegalArgumentException("status is required (AVAILABLE|BUSY|OFFLINE)");
        Provider p = providers.findById(id).orElseThrow(() -> new NotFoundException("Provider", id));
        ProviderStatus before = p.getStatus();
        boolean wentOffline = p.changeStatus(status);
        providers.flush();
        activity.log(ActivityKind.INFO, "PATCH /providers/" + id + "/status → " + status + " (" + p.getName() + ", was " + before + ")");
        if (wentOffline) events.publishEvent(new DomainEvents.ProviderWentOffline(p.getId(), p.getName()));
        return ProviderView.of(p, loadService.bookedToday());
    }
}
