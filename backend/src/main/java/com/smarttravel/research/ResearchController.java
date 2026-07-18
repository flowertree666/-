package com.smarttravel.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.time.LocalDate;

@RestController @RequestMapping("/api/research")
public class ResearchController {
    private final WebResearchService research; private final ResearchInsightRepository repository; private final ObjectMapper mapper;
    public ResearchController(WebResearchService research,ResearchInsightRepository repository,ObjectMapper mapper){this.research=research;this.repository=repository;this.mapper=mapper;}
    @PostMapping("/analyze") public ResearchReport analyze(@RequestParam @NotBlank String city,@RequestParam LocalDate targetDate){
        ResearchReport report=research.research(city,targetDate);try{repository.save(ResearchInsight.builder().city(city).reportJson(mapper.writeValueAsString(report)).createdAt(LocalDateTime.now()).build());}catch(Exception ignored){}return report;
    }
    @GetMapping("/latest") public ResearchReport latest(@RequestParam String city){ResearchInsight value=repository.findFirstByCityIgnoreCaseOrderByCreatedAtDesc(city).orElseThrow(()->new IllegalArgumentException("暂无该城市的研究报告"));try{return mapper.readValue(value.getReportJson(),ResearchReport.class);}catch(Exception e){throw new IllegalStateException("报告解析失败",e);}}
}
