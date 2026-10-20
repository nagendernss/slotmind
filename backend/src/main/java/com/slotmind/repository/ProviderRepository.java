package com.slotmind.repository;

import com.slotmind.domain.Provider;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProviderRepository extends JpaRepository<Provider, String> {
    @EntityGraph(attributePaths = {"clinic"})
    @Query("select p from Provider p order by p.id")
    List<Provider> findAllWithClinic();
}
