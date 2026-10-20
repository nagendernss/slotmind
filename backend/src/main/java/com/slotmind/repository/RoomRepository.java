package com.slotmind.repository;

import com.slotmind.domain.Room;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RoomRepository extends JpaRepository<Room, String> {
    @EntityGraph(attributePaths = {"clinic"})
    @Query("select r from Room r order by r.id")
    List<Room> findAllWithClinic();
}
