package com.smarttravel.history;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;

@Entity @Table(name="travel_history") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TravelHistory {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=120) private String title;
    @Column(nullable=false,length=50) private String city;
    private int days;
    private LocalDate startDate;
    @Column(length=500) private String preferences;
    @Column(length=30) private String pace;
    @Column(length=30) private String transport;
    @Column(length=1000) private String freeText;
    @Lob @Column(nullable=false,columnDefinition="LONGTEXT") private String planJson;
    @Lob @Column(columnDefinition="LONGTEXT") private String researchJson;
    @Column(nullable=false) private LocalDateTime createdAt;
}
