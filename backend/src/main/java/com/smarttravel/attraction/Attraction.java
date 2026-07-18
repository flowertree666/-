package com.smarttravel.attraction;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;

@Entity @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Attraction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
    private String city;
    private String district;
    private String category;
    private String tags;
    @Column(length=1000) private String summary;
    private double longitude;
    private double latitude;
    private int durationMinutes;
    private int heatScore;
    private int crowdIndex;
    private String crowdTrend;
    private String openingHours;
    private String bestSeason;
    private String ticketInfo;
    private String sourceName;
    private String sourceUrl;
    private LocalDateTime collectedAt;
    private double confidence;
}
