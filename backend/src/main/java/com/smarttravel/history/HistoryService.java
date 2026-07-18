package com.smarttravel.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttravel.planner.*;
import com.smarttravel.research.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class HistoryService {
    private final TravelHistoryRepository repository; private final ObjectMapper mapper; private final ResearchInsightRepository researchRepository;
    public HistoryService(TravelHistoryRepository repository,ObjectMapper mapper,ResearchInsightRepository researchRepository){this.repository=repository;this.mapper=mapper;this.researchRepository=researchRepository;}
    @Transactional public Long save(PlanRequest request,PlanResponse response){
        try{String researchJson=researchRepository.findFirstByCityIgnoreCaseOrderByCreatedAtDesc(request.city()).map(ResearchInsight::getReportJson).filter(json->matchesTargetDate(json,request.startDate())).orElse(null);return repository.save(TravelHistory.builder().title(request.city()+" · "+request.days()+"日行程")
            .city(request.city()).days(request.days()).startDate(request.startDate())
            .preferences(String.join(",",Optional.ofNullable(request.preferences()).orElse(List.of())))
            .pace(request.pace()).transport(request.transport()).freeText(request.freeText())
            .planJson(mapper.writeValueAsString(response)).researchJson(researchJson).createdAt(LocalDateTime.now()).build()).getId();
        }catch(Exception e){throw new IllegalStateException("保存历史记录失败",e);}
    }
    @Transactional(readOnly=true) public List<Summary> list(){return repository.findTop50ByOrderByCreatedAtDesc().stream()
        .map(h->new Summary(h.getId(),h.getTitle(),h.getCity(),h.getDays(),h.getStartDate(),h.getPreferences(),h.getCreatedAt())).toList();}
    @Transactional(readOnly=true) public Detail detail(Long id){TravelHistory h=repository.findById(id).orElseThrow(()->new IllegalArgumentException("历史记录不存在"));
        try{String researchJson=h.getResearchJson();if(researchJson==null||researchJson.isBlank())researchJson=researchRepository.findFirstByCityIgnoreCaseAndCreatedAtLessThanEqualOrderByCreatedAtDesc(h.getCity(),h.getCreatedAt()).map(ResearchInsight::getReportJson).orElse(null);ResearchReport research=researchJson==null?null:mapper.readValue(researchJson,ResearchReport.class);return new Detail(h.getId(),mapper.readValue(h.getPlanJson(),PlanResponse.class),research,h.getCreatedAt());}catch(Exception e){throw new IllegalStateException("历史记录解析失败",e);}}
    @Transactional public void delete(Long id){if(!repository.existsById(id))throw new IllegalArgumentException("历史记录不存在");repository.deleteById(id);}
    private boolean matchesTargetDate(String json,LocalDate targetDate){try{return targetDate.equals(mapper.readValue(json,ResearchReport.class).targetDate());}catch(Exception e){return false;}}
    public record Summary(Long id,String title,String city,int days,LocalDate startDate,String preferences,LocalDateTime createdAt){}
    public record Detail(Long id,PlanResponse plan,ResearchReport research,LocalDateTime createdAt){}
}
