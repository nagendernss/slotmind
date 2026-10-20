package com.slotmind.repository;

import com.slotmind.domain.RescheduleSuggestion;
import com.slotmind.domain.SuggestionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SuggestionRepository extends JpaRepository<RescheduleSuggestion, String> {

    String FETCH = "select distinct g from RescheduleSuggestion g join fetch g.appointment a "
            + "left join fetch g.originalSlot os left join fetch os.provider osp left join fetch osp.clinic "
            + "left join fetch os.room osr left join fetch osr.clinic "
            + "left join fetch g.recommendedSlot rs left join fetch rs.provider rsp left join fetch rsp.clinic "
            + "left join fetch rs.room rsr left join fetch rsr.clinic ";

    @Query(FETCH + "where (:status is null or g.status = :status) order by g.createdAt desc, g.id desc")
    List<RescheduleSuggestion> search(@Param("status") SuggestionStatus status);

    @Query(FETCH + "where g.id = :id")
    Optional<RescheduleSuggestion> findDetailed(@Param("id") String id);

    @Query(FETCH + "where a.id = :appointmentId order by g.createdAt desc, g.id desc")
    List<RescheduleSuggestion> findDetailedByAppointmentId(@Param("appointmentId") String appointmentId);

    boolean existsByPendingKey(String pendingKey);

    List<RescheduleSuggestion> findByAppointmentIdAndStatus(String appointmentId, SuggestionStatus status);

    List<RescheduleSuggestion> findByStatus(SuggestionStatus status);

    @Query("select g.recommendedSlot.id from RescheduleSuggestion g where g.status = com.slotmind.domain.SuggestionStatus.PENDING "
            + "and g.recommendedSlot is not null and g.appointment.id <> :appointmentId")
    List<String> findSlotIdsHeldByOthers(@Param("appointmentId") String appointmentId);

    @Query("select g.recommendedSlot.id from RescheduleSuggestion g where g.appointment.id = :appointmentId "
            + "and g.status = com.slotmind.domain.SuggestionStatus.REJECTED and g.recommendedSlot is not null")
    List<String> findRejectedSlotIds(@Param("appointmentId") String appointmentId);

    /** Every slot already offered to this appointment (pending, superseded or rejected) — used by "suggest another". */
    @Query("select g.recommendedSlot.id from RescheduleSuggestion g where g.appointment.id = :appointmentId "
            + "and g.status <> com.slotmind.domain.SuggestionStatus.ACCEPTED and g.recommendedSlot is not null")
    List<String> findPreviouslySuggestedSlotIds(@Param("appointmentId") String appointmentId);
}
