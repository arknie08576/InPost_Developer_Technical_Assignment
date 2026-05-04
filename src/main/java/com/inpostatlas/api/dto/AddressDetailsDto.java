package com.inpostatlas.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AddressDetailsDto(
        String city,
        String province,
        String post_code,
        String street,
        String building_number
) {
}
