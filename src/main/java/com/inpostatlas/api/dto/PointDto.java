package com.inpostatlas.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PointDto(
        String name,
        String country,
        List<String> type,
        String status,
        LocationDto location,
        AddressDetailsDto address_details,
        Boolean location_247,
        Boolean easy_access_zone,
        List<String> functions,
        String image_url
) {
}
