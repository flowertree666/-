package com.smarttravel.traffic;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;

@Entity @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TrafficWatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(unique = true) private Long attractionId;
    private String attractionName;
    private String city;
    private double longitude;
    private double latitude;
    private LocalDate expiresOn;
    private LocalDateTime updatedAt;
}
