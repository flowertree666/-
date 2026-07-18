package com.smarttravel.amap;

import com.smarttravel.attraction.Attraction;
import com.smarttravel.attraction.AttractionRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * Keeps destination validation consistent for the form and the route planner.
 * A locally curated dataset is usable even when the optional map provider is unavailable.
 */
@Service
public class DestinationValidationService {
    private final AmapService amap;
    private final AttractionRepository attractions;

    public DestinationValidationService(AmapService amap, AttractionRepository attractions) {
        this.amap = amap;
        this.attractions = attractions;
    }

    public AmapService.DestinationValidation validate(String input) {
        String value = input == null ? "" : input.strip();
        if (value.length() < 2) {
            return new AmapService.DestinationValidation(false, "", "", "目的地至少需要输入 2 个字符");
        }

        Optional<Attraction> datasetMatch = attractions.findAll().stream()
                .filter(this::isDatasetPoi)
                .filter(attraction -> sameCity(value, attraction.getCity()))
                .findFirst();
        if (datasetMatch.isPresent()) {
            String city = datasetMatch.get().getCity();
            return new AmapService.DestinationValidation(true, city, "dataset", "目的地有效（已命中本地旅行数据集）");
        }

        return amap.validateDestination(value);
    }

    private boolean isDatasetPoi(Attraction attraction) {
        return attraction.getSourceName() != null && attraction.getSourceName().endsWith(" RAG 数据集");
    }

    private boolean sameCity(String input, String city) {
        return normalize(input).equals(normalize(city));
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT)
                .replaceAll("(特别行政区|自治区|省|市)$", "");
    }
}
