package com.smarttravel.history;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TravelHistoryRepository extends JpaRepository<TravelHistory,Long> {
    List<TravelHistory> findTop50ByOrderByCreatedAtDesc();
}
