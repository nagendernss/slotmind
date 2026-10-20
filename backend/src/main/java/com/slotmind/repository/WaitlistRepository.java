package com.slotmind.repository;

import com.slotmind.domain.WaitlistEntry;
import com.slotmind.domain.WaitlistStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface WaitlistRepository extends JpaRepository<WaitlistEntry, String> {
    @Query("select w from WaitlistEntry w left join fetch w.clinic left join fetch w.offeredSlot s left join fetch s.provider p "
            + "left join fetch p.clinic left join fetch s.room r left join fetch r.clinic order by w.createdAt")
    List<WaitlistEntry> findAllDetailed();

    List<WaitlistEntry> findByStatusOrderByCreatedAtAsc(WaitlistStatus status);

    boolean existsByOfferedSlotIdAndStatus(String slotId, WaitlistStatus status);
}
