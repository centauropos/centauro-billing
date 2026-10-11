package com.centauro.billing.domain.vo;

public record PageQuery(int page, int size, String sortProperty, boolean ascending) {

    public PageQuery {
        if (page < 0) {
            page = 0;
        }
        if (size < 1 || size > 100) {
            size = 20;
        }
    }
}