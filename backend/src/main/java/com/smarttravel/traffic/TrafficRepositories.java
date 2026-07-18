package com.smarttravel.traffic;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.*;
import java.util.*;

interface TrafficWatchRepository extends JpaRepository<TrafficWatch, Long> {
    Optional<TrafficWatch> findByAttractionId(Long attractionId);
    List<TrafficWatch> findByExpiresOnGreaterThanEqual(LocalDate date);
}

interface TrafficSnapshotRepository extends JpaRepository<TrafficSnapshot, Long> {
    Optional<TrafficSnapshot> findFirstByAttractionIdOrderByCollectedAtDesc(Long attractionId);
    List<TrafficSnapshot> findByAttractionIdAndCollectedAtAfter(Long attractionId, LocalDateTime time);
}
