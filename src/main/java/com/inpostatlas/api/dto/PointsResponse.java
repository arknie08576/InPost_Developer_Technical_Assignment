package com.inpostatlas.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PointsResponse(
        Integer count,
        Integer page,
        Integer per_page,
        Integer total_pages,
        List<PointDto> items,
        LinksDto _links
) {
    public List<PointDto> safeItems() {
        return items == null ? List.of() : items;
    }
}
