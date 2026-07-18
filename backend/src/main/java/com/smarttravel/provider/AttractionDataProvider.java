package com.smarttravel.provider;

import com.smarttravel.attraction.Attraction;
import java.util.List;

/** 合规数据源扩展点：仅接入开放 API、获授权数据或允许采集的公开页面。 */
public interface AttractionDataProvider {
    String sourceName();
    List<Attraction> fetch(String city);
}
