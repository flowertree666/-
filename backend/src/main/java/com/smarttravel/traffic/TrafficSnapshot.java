package com.smarttravel.traffic;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;

@Entity @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Table(indexes = {@Index(name = "idx_traffic_attraction_time", columnList = "attractionId,collectedAt")})
public class TrafficSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private Long attractionId;
    private String attractionName;
    private String city;
    private String roadName;
    private int trafficStatus;
    private String statusDescription;
    @Column(length = 1500) private String description;
    private Double averageSpeed;
    private Integer congestionDistance;
    private String congestionTrend;
    private boolean peakHourNoise;
    private boolean holiday;
    private boolean possibleIncident;
    private LocalDateTime collectedAt;
}
