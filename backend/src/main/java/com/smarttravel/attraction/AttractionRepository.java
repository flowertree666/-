package com.smarttravel.attraction;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AttractionRepository extends JpaRepository<Attraction, Long> {
    List<Attraction> findByCityIgnoreCase(String city);
    boolean existsBySourceUrl(String sourceUrl);
    List<Attraction> findAllBySourceUrl(String sourceUrl);
    Optional<Attraction> findBySourceUrl(String sourceUrl);
}
