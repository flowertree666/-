package com.smarttravel.research;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity @Table(name="research_insight") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResearchInsight {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=50) private String city;
    @Lob @Column(nullable=false,columnDefinition="LONGTEXT") private String reportJson;
    @Column(nullable=false) private LocalDateTime createdAt;
}
