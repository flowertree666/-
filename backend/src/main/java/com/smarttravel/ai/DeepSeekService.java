package com.smarttravel.ai;

import com.fasterxml.jackson.databind.*;
import com.smarttravel.planner.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.*;
import com.smarttravel.research.ResearchInsightRepository;
import com.smarttravel.rag.RagService;

@Service
public class DeepSeekService {
    private final ObjectMapper mapper; private final RestClient client;
    private final String apiKey; private final String model; private final boolean enabled;
    private final ResearchInsightRepository researchRepository;
    private final RagService rag;
    public DeepSeekService(ObjectMapper mapper, @Value("${app.deepseek.base-url}") String baseUrl,
            @Value("${app.deepseek.api-key:}") String apiKey, @Value("${app.deepseek.model}") String model,
            @Value("${app.deepseek.enabled:true}") boolean enabled,ResearchInsightRepository researchRepository, RagService rag) {
        this.mapper=mapper; this.apiKey=apiKey; this.model=model; this.enabled=enabled;
        this.researchRepository=researchRepository;
        this.rag=rag;
        this.client=RestClient.builder().baseUrl(baseUrl).build();
    }
    public Result personalize(PlanRequest request, List<PlanResponse.RoutePlan> drafts) {
        if (!enabled || apiKey == null || apiKey.isBlank()) return new Result(drafts,false,model,"未配置 API Key，已使用约束规划引擎");
        try {
            String research=researchRepository.findFirstByCityIgnoreCaseOrderByCreatedAtDesc(request.city()).map(x->x.getReportJson()).orElse("暂无联网研究报告");
            String ragQuery=request.city()+" "+String.join(" ",Optional.ofNullable(request.preferences()).orElse(List.of()))+" "+Optional.ofNullable(request.freeText()).orElse("");
            var ragContext=rag.contextFor(ragQuery, request.city(), request.startDate());
            String input=mapper.writeValueAsString(Map.of("user",request,"draftPlans",drafts,"recentWebResearch",research,"ragContext",ragContext));
            String system="""
                你是严谨的中国旅行规划师。基于用户需求和已通过时间、距离约束校验的草案做个性化讲解。
                不得虚构景点、开放时间、客流、票价或交通信息，不得新增删除景点，也不得改变日期和时间。
                只返回JSON对象：{"plans":[{"title":"与草案标题完全一致","description":"30字内","advice":"80字内个性化建议","days":[{"day":1,"theme":"12字内","dailyAdvice":"80字内，只针对该日景点、日期和时间安排的建议","tips":{"景点ID":"40字内玩法建议"}}]}]}
                建议须结合偏好、节奏、交通方式、自由文本、近期联网研究和ragContext。ragContext中的official_fact可用于说明预约、开放或限制；map_fact只可用于定位；derived_rule与operational_constraint只可用于行程约束。不得把过期或要求复核的内容说成实时事实。全天登山或长距离徒步后的次日必须强调恢复和低强度，不得再次推荐登山。引用联网信息时保留[S数字]来源编号。不确定的信息提示复核官方公告。
                """;
            Map<String,Object> body=Map.of("model",model,"temperature",0.45,"response_format",Map.of("type","json_object"),
                    "messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",input)));
            JsonNode response=client.post().uri("/chat/completions").header("Authorization","Bearer "+apiKey)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            String content=response.path("choices").path(0).path("message").path("content").asText();
            return new Result(merge(drafts,mapper.readTree(content)),true,model,"DeepSeek 个性化生成成功");
        } catch(Exception e) { return new Result(drafts,false,model,"AI 服务暂不可用，已自动降级为约束规划引擎"); }
    }
    public PlaceRequirements extractPlaceRequirements(PlanRequest request){
        String freeText=Optional.ofNullable(request.freeText()).orElse("").strip();PlaceRequirements deterministic=deterministicRequirements(freeText);
        if(freeText.isBlank()||!enabled||apiKey==null||apiKey.isBlank())return deterministic;
        try{String prompt="从用户对"+request.city()+"旅行的补充要求中提取明确命名的必去景点、指定第几天、是否要求全天、明确排除的景点，以及是否要求爬山、看日出和起床时间。只有专有地点名称才算景点，日出、美食、拍照等不是景点。day没有指定时为0；fullDay仅当用户明确说一整天/全天时为true；wakeTime未指定时为07:00，统一HH:mm格式。返回JSON：{\"mustVisit\":[{\"name\":\"景点名\",\"day\":2,\"fullDay\":true}],\"exclude\":[\"景点名\"],\"wakeTime\":\"04:00\",\"hiking\":true,\"sunrise\":true}。用户要求："+freeText;
            Map<String,Object> body=Map.of("model",model,"temperature",0.0,"response_format",Map.of("type","json_object"),"messages",List.of(Map.of("role","system","content","你是严格的旅行地点实体提取器，不补充用户没说过的地点。"),Map.of("role","user","content",prompt)));
            JsonNode response=client.post().uri("/chat/completions").header("Authorization","Bearer "+apiKey).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);JsonNode json=mapper.readTree(response.path("choices").path(0).path("message").path("content").asText());List<RequiredPlace> places=new ArrayList<>();for(JsonNode x:json.path("mustVisit")){String name=x.path("name").asText().strip();if(!name.isBlank())places.add(new RequiredPlace(name,Math.max(0,x.path("day").asInt(0)),x.path("fullDay").asBoolean(false)));}String wake=json.path("wakeTime").asText(deterministic.wakeTime());if(!wake.matches("(?:[01]\\d|2[0-3]):[0-5]\\d"))wake=deterministic.wakeTime();return new PlaceRequirements(places.stream().distinct().limit(10).toList(),texts(json,"exclude"),deterministic.hiking()||json.path("hiking").asBoolean(false),deterministic.sunrise()||json.path("sunrise").asBoolean(false),deterministic.feasibilityIssue(),deterministic.sunrise()?"04:00":wake);
        }catch(Exception e){return deterministic;}
    }
    private PlaceRequirements deterministicRequirements(String text){boolean sunrise=text.contains("日出"),hiking=text.matches(".*(爬山|登山|徒步).*");String issue=sunrise&&text.matches(".*(下午|傍晚|晚上).*")?"日出只会出现在清晨，无法按“下午/晚上看日出”安排。请改为凌晨或清晨出发，或将目标改为看日落。":"";String wake=sunrise?"04:00":"07:00";java.util.regex.Matcher m=java.util.regex.Pattern.compile("(凌晨|清晨|早上)\\s*([0-9]{1,2})点").matcher(text);if(m.find()){int hour=Integer.parseInt(m.group(2));if(hour>=0&&hour<=8)wake=String.format("%02d:00",hour);}return new PlaceRequirements(List.of(),List.of(),hiking,sunrise,issue,wake);}
    private List<PlanResponse.RoutePlan> merge(List<PlanResponse.RoutePlan> drafts,JsonNode root) {
        Map<String,JsonNode> byTitle=new HashMap<>(); root.path("plans").forEach(p->byTitle.put(p.path("title").asText(),p));
        return drafts.stream().map(plan->{
            JsonNode ai=byTitle.get(plan.title()); if(ai==null)return plan;
            Map<Integer,JsonNode> aiDays=new HashMap<>(); ai.path("days").forEach(d->aiDays.put(d.path("day").asInt(),d));
            var days=plan.days().stream().map(day->{ JsonNode ad=aiDays.get(day.day()); if(ad==null)return day; JsonNode tips=ad.path("tips");
                var stops=day.stops().stream().map(s->new PlanResponse.Stop(s.attractionId(),s.name(),s.category(),s.time(),s.endTime(),s.transferToNextMinutes(),s.durationMinutes(),s.heatScore(),s.crowdIndex(),s.crowdLevel(),s.openingHours(),s.crowdAdvice(),s.bestVisitTime(),s.crowdBasis(),s.summary(),tips.path(String.valueOf(s.attractionId())).asText(""),s.longitude(),s.latitude(),s.freshness(),s.traffic())).toList();
                return new PlanResponse.DayPlan(day.day(),day.date(),clean(ad.path("theme").asText(),day.theme()),clean(ad.path("dailyAdvice").asText(),day.dailyAdvice()),day.weather(),day.totalMinutes(),day.travelKm(),day.accommodation(),day.scheduleNotes(),stops,day.costs());
            }).toList();
                return new PlanResponse.RoutePlan(plan.title(),clean(ai.path("description").asText(),plan.description()),clean(ai.path("advice").asText(),"已按你的偏好生成，可灵活预留休息时间。"),plan.score(),days,plan.alternatives(),plan.budget());
        }).toList();
    }
    private String clean(String value,String fallback){return value==null||value.isBlank()?fallback:value.strip();}
    private List<String> texts(JsonNode node,String field){List<String> out=new ArrayList<>();node.path(field).forEach(x->{String v=x.asText().strip();if(!v.isBlank())out.add(v);});return out.stream().distinct().limit(10).toList();}
    public record Result(List<PlanResponse.RoutePlan> plans,boolean used,String model,String status){}
    public record RequiredPlace(String name,int day,boolean fullDay){}
    public record PlaceRequirements(List<RequiredPlace> mustVisit,List<String> exclude,boolean hiking,boolean sunrise,String feasibilityIssue,String wakeTime){}
}
