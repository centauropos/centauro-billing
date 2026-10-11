package com.centauro.billing.infrastructure.mercadopago.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Envoltorio de los {@code /search} de MP: {@code paging} + {@code results}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MpSearchResponse<T>(Paging paging, List<T> results) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Paging(Integer total, Integer offset, Integer limit) {
    }
}
