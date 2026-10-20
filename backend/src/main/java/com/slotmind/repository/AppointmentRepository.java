package com.slotmind.repository;

import com.slotmind.domain.Appointment;
import com.slotmind.domain.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppointmentRepository extends JpaRepository<Appointment, String> {

    String FETCH = "select a from Appointment a join fetch a.provider p left join fetch p.clinic "
            + "join fetch a.slot s join fetch s.provider sp left join fetch sp.clinic left join fetch s.room r left join fetch r.clinic ";

    @Query(FETCH + "where (:status is null or a.status = :status) order by s.startTime, a.id")
    List<Appointment> search(@Param("status") AppointmentStatus status);

    @Query(FETCH + "where a.id = :id")
    Optional<Appointment> findDetailed(@Param("id") String id);

    /** Appointments affected by a provider going offline: active ones still on that provider, soonest first. */
    @Query("select a.id from Appointment a join a.slot s where a.provider.id = :providerId and a.status in :statuses "
            + "order by s.startTime")
    List<String> findIdsByProviderIdAndStatusIn(@Param("providerId") String providerId,
                                                @Param("statuses") Collection<AppointmentStatus> statuses);

    @Query("select a from Appointment a where a.slot.id = :slotId")
    Optional<Appointment> findBySlotId(@Param("slotId") String slotId);

    @Query(FETCH + "where s.id in :slotIds")
    List<Appointment> findBySlotIdIn(@Param("slotIds") Collection<String> slotIds);

    @Query("select a from Appointment a join fetch a.slot where a.patientName = :name and a.status = :status")
    List<Appointment> findByPatientNameAndStatus(@Param("name") String name, @Param("status") AppointmentStatus status);
}
