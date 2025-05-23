package com.example.AOD.OTT.Netflix.repository;

import com.example.AOD.OTT.Netflix.domain.NetflixContentActor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NetflixContentActorRepository extends JpaRepository<NetflixContentActor, Long> {
    Optional<NetflixContentActor> findByName(String actorName);
}
