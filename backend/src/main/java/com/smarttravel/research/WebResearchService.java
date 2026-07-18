package com.smarttravel.research;

import com.fasterxml.jackson.databind.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.smarttravel.amap.AmapService;

@Service
public class WebResearchService {
    private static final String UA="SmartTravelResearchBot/1.0 (+academic-project; respects robots.txt)";
    private final ObjectMapper mapper; private final RestClient deepSeek; private final String apiKey,model;
    private final RestClient bocha; private final String bochaApiKey;
    private final AmapService amap;
    private final int maxResults,maxPages,minEvidence,delayMs;
    public WebResearchService(ObjectMapper mapper,@Value("${app.deepseek.base-url}")String baseUrl,
            @Value("${app.deepseek.api-key:}")String apiKey,@Value("${app.deepseek.model}")String model,
            @Value("${app.research.max-search-results:12}")int maxResults,@Value("${app.research.max-pages:8}")int maxPages,
            @Value("${app.research.min-evidence:5}")int minEvidence,@Value("${app.research.request-delay-ms:800}")int delayMs,@Value("${app.research.bocha-api-key:}")String bochaApiKey,
            @Value("${app.research.bocha-base-url}")String bochaBaseUrl,AmapService amap){
        this.mapper=mapper;this.apiKey=apiKey;this.model=model;this.maxResults=maxResults;this.maxPages=maxPages;this.minEvidence=minEvidence;this.delayMs=delayMs;
        this.deepSeek=RestClient.builder().baseUrl(baseUrl).build();
        this.bochaApiKey=bochaApiKey;this.bocha=RestClient.builder().baseUrl(bochaBaseUrl).build();
        this.amap=amap;
    }

    public ResearchReport research(String city,LocalDate targetDate){
        List<String> queries=expandQueries(city,targetDate); LinkedHashMap<String,SearchHit> hits=new LinkedHashMap<>();Map<String,Integer> domainCounts=new HashMap<>();
        for(String query:queries){for(SearchHit hit:search(query)){String domain=host(hit.url());if(relevant(hit.title()+" "+hit.description(),city)&&safeUrl(hit.url())&&!isListingOrHomepage(hit.url())&&domainCounts.getOrDefault(domain,0)<3){String normalized=normalize(hit.url());if(!hits.containsKey(normalized)){hits.put(normalized,hit);domainCounts.merge(domain,1,Integer::sum);}}if(hits.size()>=maxResults)break;}if(hits.size()>=maxResults)break;}
        List<Page> pages=new ArrayList<>();
        LocalDate windowStart=targetDate.minusMonths(3), windowEnd=targetDate.isBefore(LocalDate.now())?targetDate:LocalDate.now();
        for(SearchHit hit:hits.values()){if(pages.size()>=maxPages)break;if(!allowedByRobots(hit.url()))continue;Page page=fetch(hit,city);if(page!=null&&page.date()!=null&&!page.date().isBefore(windowStart)&&!page.date().isAfter(windowEnd))pages.add(page);sleep();}
        if(pages.size()<minEvidence&&bochaApiKey!=null&&!bochaApiKey.isBlank()){
            Set<String> used=new HashSet<>();pages.forEach(p->used.add(normalize(p.url())));String ym=targetDate.getYear()+"年"+targetDate.getMonthValue()+"月";
            for(var attraction:amap.searchAttractions(city).stream().limit(8).toList()){
                for(SearchHit hit:search(city+" "+attraction.getName()+" 最新 活动 评价 "+ym)){
                    if(pages.size()>=maxPages)break;if(used.contains(normalize(hit.url()))||!safeUrl(hit.url())||isListingOrHomepage(hit.url())||!allowedByRobots(hit.url()))continue;
                    Page page=fetch(hit,attraction.getName());if(page!=null&&page.date()!=null&&!page.date().isBefore(windowStart)&&!page.date().isAfter(windowEnd)){pages.add(page);used.add(normalize(page.url()));}sleep();
                }
                if(pages.size()>=minEvidence)break;
            }
        }
        List<ResearchReport.Evidence> evidence=new ArrayList<>();int id=1;
        for(Page p:pages)evidence.add(new ResearchReport.Evidence(id++,p.title(),p.url(),host(p.url()),sourceType(p.url()),p.publishedAt(),p.dateSource(),LocalDateTime.now(),excerpt(p.text(),180)));
        return analyze(city,targetDate,pages,evidence);
    }

    private List<String> expandQueries(String city,LocalDate targetDate){
        String ym=targetDate.getYear()+"年"+targetDate.getMonthValue()+"月";
        List<String> fallback=List.of("site:gov.cn "+city+" 文旅 景区 "+ym,"site:gov.cn "+city+" 景区 开放 公告 "+targetDate.getYear(),
            city+" 景区 官方 最新公告 "+ym,city+" 当季 景观 花期 活动 "+ym,"site:you.ctrip.com "+city+" 游记 "+targetDate.getYear(),
            "site:mafengwo.cn "+city+" 旅游 评价 "+targetDate.getYear(),city+" 旅游 近期 拥挤 排队 体验 "+ym,
            city+" 博物馆 公园 暑期 活动 "+ym,city+" 文旅 新闻 景点 推荐 "+ym,city+" 景区 临时关闭 预约 限流 "+ym,
            city+" 旅游攻略 游客体验 "+ym,city+" 周末去哪儿 景区 "+ym);
        if(apiKey==null||apiKey.isBlank())return fallback;
        try{String prompt="为中国城市“"+city+"”生成6条网页检索词，出发日期是"+targetDate+"，只检索出发日前3个月内的时令景观、官方活动、开放公告、游客评价和拥挤反馈。查询词要包含年份或月份，优先文旅局、景区官网、政府网站，再补充公开论坛。只返回JSON：{\"queries\":[\"...\"]}，不得编造URL。";
            JsonNode r=chat("你是旅游信息检索词规划器。",prompt,0.2);List<String> out=new ArrayList<>(fallback);r.path("queries").forEach(q->{if(!q.asText().isBlank()&&!out.contains(q.asText()))out.add(q.asText());});return out.stream().limit(18).toList();
        }catch(Exception e){return fallback;}
    }

    private List<SearchHit> search(String query){
        if(bochaApiKey==null||bochaApiKey.isBlank())return List.of();
        try{Map<String,Object> body=Map.of("query",query,"freshness","oneYear","summary",true,"count",20);
            JsonNode root=bocha.post().uri("/v1/web-search").header("Authorization","Bearer "+bochaApiKey)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            List<SearchHit> out=new ArrayList<>();for(JsonNode item:root.path("data").path("webPages").path("value")){
                String url=item.path("url").asText(),title=item.path("name").asText(),desc=item.path("summary").asText(item.path("snippet").asText());
                String date=item.path("datePublished").asText();if(!url.isBlank())out.add(new SearchHit(title,url,desc,date));
            }return out;
        }catch(Exception e){return List.of();}
    }

    private Page fetch(SearchHit hit,String city){
        try{org.jsoup.Connection.Response response=Jsoup.connect(hit.url()).userAgent(UA).timeout(12000).followRedirects(true).maxBodySize(1_500_000).execute();
            String finalUrl=response.url().toString();if(!safeUrl(finalUrl)||isListingOrHomepage(finalUrl)||!allowedByRobots(finalUrl))return null;Document doc=response.parse();
            doc.select("script,style,noscript,nav,footer,header,form,aside").remove();Element main=Optional.ofNullable(doc.selectFirst("article")).orElse(Optional.ofNullable(doc.selectFirst("main")).orElse(doc.body()));
            String text=main.text().replaceAll("\\s+"," ").strip();if(text.length()<300)return null;String title=doc.title().isBlank()?hit.title():doc.title();
            if(!relevant(title+" "+text.substring(0,Math.min(1600,text.length())),city))return null;
            if(title.isBlank()||title.equalsIgnoreCase("首页")||title.length()<5)return null;
            String published=publishedAt(doc,text),dateSource="页面日期";if(published.isBlank()&&!hit.publishedAt().isBlank()){published=hit.publishedAt();dateSource="博查搜索API发布日期";}
            return new Page(title,finalUrl,text.substring(0,Math.min(6000,text.length())),published,dateSource,parseDate(published));
        }catch(Exception e){return null;}
    }

    private boolean allowedByRobots(String raw){
        try{URI uri=URI.create(raw);String robots=uri.getScheme()+"://"+uri.getAuthority()+"/robots.txt";String text=Jsoup.connect(robots).userAgent(UA).timeout(5000).ignoreContentType(true).execute().body();
            boolean applies=false;String path=uri.getPath();for(String line:text.split("\\R")){line=line.split("#",2)[0].strip();if(line.toLowerCase().startsWith("user-agent:"))applies=line.substring(11).strip().equals("*");else if(applies&&line.toLowerCase().startsWith("disallow:")){String denied=line.substring(9).strip();if(!denied.isBlank()&&path.startsWith(denied))return false;}}return true;
        }catch(Exception e){return false;}
    }

    private ResearchReport analyze(String city,LocalDate targetDate,List<Page> pages,List<ResearchReport.Evidence> evidence){
        String notice="分析仅基于本次成功访问的公开页面，不代表全网完整信息；开放状态和客流请以景区官方实时公告为准。";
        if(pages.isEmpty())return new ResearchReport(city,targetDate,0,"数据不足","低","未找到发布日期可验证且位于出发日前3个月内的有效页面。",List.of(),List.of(),List.of("请补充近期官方来源，或临近出发日期重新分析"),List.of(),evidence,LocalDateTime.now(),notice);
        try{StringBuilder source=new StringBuilder();for(int i=0;i<pages.size();i++)source.append("[S").append(i+1).append("] ").append(pages.get(i).title()).append("\nURL: ").append(pages.get(i).url()).append("\n正文: ").append(pages.get(i).text()).append("\n\n");
            String system="你是基于证据的旅游研究分析器。只能使用给定网页证据；近期指页面明确出现的日期或活动时间。每条事实结论必须带[S数字]，证据不足就写数据不足。热度0-100是样本内相对指标，不得声称真实客流。只返回JSON。";
            String prompt="分析"+city+"在"+targetDate+"出发时的近期旅游热度、时令景观和游客评价，并提取证据中明确出现的具体景点。返回{\"heatScore\":整数,\"heatTrend\":\"上升/平稳/下降/数据不足\",\"confidence\":\"高/中/低\",\"summary\":\"带来源编号\",\"seasonalHighlights\":[\"带来源编号\"],\"positiveFeedback\":[\"带来源编号\"],\"cautions\":[\"带来源编号\"],\"hotAttractions\":[{\"name\":\"景点准确名称\",\"heatScore\":0到100,\"reason\":\"带来源编号\",\"evidenceIds\":[1]}]}。没有证据的景点不得输出。网页如下：\n"+source;
            JsonNode a=chat(system,prompt,0.15);long official=evidence.stream().filter(x->x.sourceType().equals("官方/政府")).count();
            String confidence=evidence.size()>=8&&official>=2?a.path("confidence").asText("中"):evidence.size()>=minEvidence?"中":"低";
            return new ResearchReport(city,targetDate,Math.max(0,Math.min(100,a.path("heatScore").asInt(0))),a.path("heatTrend").asText("数据不足"),confidence,a.path("summary").asText("数据不足"),strings(a,"seasonalHighlights"),strings(a,"positiveFeedback"),strings(a,"cautions"),hotAttractions(a),evidence,LocalDateTime.now(),notice);
        }catch(Exception e){return new ResearchReport(city,targetDate,0,"数据不足","低","已抓取公开页面，但AI分析暂时不可用。",List.of(),List.of(),List.of("请稍后重新分析"),List.of(),evidence,LocalDateTime.now(),notice);}
    }
    private JsonNode chat(String system,String user,double temperature)throws Exception{Map<String,Object> body=Map.of("model",model,"temperature",temperature,"response_format",Map.of("type","json_object"),"messages",List.of(Map.of("role","system","content",system),Map.of("role","user","content",user)));JsonNode r=deepSeek.post().uri("/chat/completions").header("Authorization","Bearer "+apiKey).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);return mapper.readTree(r.path("choices").path(0).path("message").path("content").asText());}
    private List<String> strings(JsonNode n,String field){List<String> out=new ArrayList<>();n.path(field).forEach(x->out.add(x.asText()));return out;}
    private List<ResearchReport.HotAttraction> hotAttractions(JsonNode n){List<ResearchReport.HotAttraction> out=new ArrayList<>();for(JsonNode x:n.path("hotAttractions")){List<Integer> ids=new ArrayList<>();x.path("evidenceIds").forEach(i->ids.add(i.asInt()));String name=x.path("name").asText();if(!name.isBlank()&&!ids.isEmpty())out.add(new ResearchReport.HotAttraction(name,Math.max(0,Math.min(100,x.path("heatScore").asInt())),x.path("reason").asText(),ids));}return out.stream().sorted(Comparator.comparingInt(ResearchReport.HotAttraction::heatScore).reversed()).limit(15).toList();}
    private boolean safeUrl(String raw){try{URI u=URI.create(raw);if(!Set.of("http","https").contains(u.getScheme()))return false;InetAddress a=InetAddress.getByName(u.getHost());return !(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress());}catch(Exception e){return false;}}
    private String normalize(String u){return u.replaceFirst("[#?].*$","").replaceAll("/$","");}
    private String host(String u){try{return URI.create(u).getHost();}catch(Exception e){return "";}}
    private String sourceType(String u){String h=host(u);return h.endsWith("gov.cn")||h.contains("wlj")?"官方/政府":h.contains(".org.cn")?"机构":"公开网页/论坛";}
    private boolean isListingOrHomepage(String raw){try{URI u=URI.create(raw);String p=Optional.ofNullable(u.getPath()).orElse("/").replaceAll("/+","/");if(p.equals("/")||p.equalsIgnoreCase("/index.html")||p.equalsIgnoreCase("/index.shtml"))return true;String q=Optional.ofNullable(u.getQuery()).orElse("").toLowerCase();String lower=p.toLowerCase();return lower.matches(".*/(search|query|list|channel)/?.*")||q.contains("keyword=")||q.contains("query=")||q.contains("search=");}catch(Exception e){return true;}}
    private boolean relevant(String text,String city){if(text==null||city==null)return false;String normalized=city.replaceAll("(市|地区|自治州|特别行政区)$","");return normalized.length()>=2&&text.contains(normalized);}
    private String publishedAt(Document doc,String text){String[] selectors={"meta[property=article:published_time]","meta[name=publishdate]","meta[name=pubdate]","meta[name=date]"};for(String selector:selectors){Element e=doc.selectFirst(selector);if(e!=null&&!e.attr("content").isBlank())return e.attr("content");}Element time=doc.selectFirst("time[datetime]");if(time!=null&&!time.attr("datetime").isBlank())return time.attr("datetime");java.util.regex.Matcher m=java.util.regex.Pattern.compile("(20\\d{2})[-年/.](0?[1-9]|1[0-2])[-月/.](0?[1-9]|[12]\\d|3[01])日?").matcher(text.substring(0,Math.min(1200,text.length())));return m.find()?m.group():"";}
    private LocalDate parseDate(String value){if(value==null||value.isBlank())return null;java.util.regex.Matcher m=java.util.regex.Pattern.compile("(20\\d{2})\\D+(0?[1-9]|1[0-2])\\D+(0?[1-9]|[12]\\d|3[01])").matcher(value);if(m.find())try{return LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));}catch(Exception ignored){}try{return OffsetDateTime.parse(value).toLocalDate();}catch(Exception ignored){}try{return ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate();}catch(Exception ignored){return null;}}
    private String excerpt(String s,int n){return s.length()<=n?s:s.substring(0,n)+"…";}
    private void sleep(){try{Thread.sleep(Math.max(0,delayMs));}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private record SearchHit(String title,String url,String description,String publishedAt){}
    private record Page(String title,String url,String text,String publishedAt,String dateSource,LocalDate date){}
}
