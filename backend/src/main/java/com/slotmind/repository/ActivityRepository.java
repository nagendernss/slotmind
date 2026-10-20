package com.slotmind.repository;

import com.slotmind.domain.ActivityEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityRepository extends JpaRepository<ActivityEvent, Long> {
    List<ActivityEvent> findTop100ByOrderByIdDesc();
}
