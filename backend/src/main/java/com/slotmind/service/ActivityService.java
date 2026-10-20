package com.slotmind.service;

import com.slotmind.domain.ActivityEvent;
import com.slotmind.domain.ActivityKind;
import com.slotmind.repository.ActivityRepository;
import com.slotmind.view.Views.ActivityView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** The "Agent activity" feed. Writes in REQUIRES_NEW so an entry survives a rollback of the surrounding work. */
@Service
@Transactional(readOnly = true)
public class ActivityService {
    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);
    private final ActivityRepository repo;

    public ActivityService(ActivityRepository repo) { this.repo = repo; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(ActivityKind kind, String message) {
        log.info("[activity {}] {}", kind, message);
        repo.save(new ActivityEvent(kind, message));
    }

    public List<ActivityView> recent() {
        return repo.findTop100ByOrderByIdDesc().stream().map(ActivityView::of).toList();
    }
}
