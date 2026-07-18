package com.smarttravel.research;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.time.LocalDateTime;

public interface ResearchInsightRepository extends JpaRepository<ResearchInsight,Long> {
    Optional<ResearchInsight> findFirstByCityIgnoreCaseOrderByCreatedAtDesc(String city);
    Optional<ResearchInsight> findFirstByCityIgnoreCaseAndCreatedAtLessThanEqualOrderByCreatedAtDesc(String city,LocalDateTime createdAt);
}
