package com.slotmind.repository;

import com.slotmind.domain.Slot;
import com.slotmind.domain.SlotStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SlotRepository extends JpaRepository<Slot, String> {

    String FETCH = "select s from Slot s join fetch s.provider p left join fetch p.clinic "
            + "left join fetch s.room r left join fetch r.clinic ";

    /** Slot list view with provider, clinic and room fetched in one query (no N+1). */
    @Query(FETCH + "where (:providerId is null or p.id = :providerId) and (:status is null or s.status = :status) "
            + "order by s.startTime, s.id")
    List<Slot> search(@Param("providerId") String providerId, @Param("status") SlotStatus status);

    @Query(FETCH + "where s.id = :id")
    Optional<Slot> findDetailed(@Param("id") String id);

    /** Engine candidate base: OPEN future slots, fully fetched. */
    @Query(FETCH + "where s.status = com.slotmind.domain.SlotStatus.OPEN and s.startTime > :now order by s.startTime, s.id")
    List<Slot> findOpenAfter(@Param("now") LocalDateTime now);

    @Query("select s.provider.id, count(s) from Slot s where s.status = com.slotmind.domain.SlotStatus.BOOKED "
            + "and s.startTime >= :from and s.startTime < :to group by s.provider.id")
    List<Object[]> countBookedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
