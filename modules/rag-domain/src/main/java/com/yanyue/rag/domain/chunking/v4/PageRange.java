package com.yanyue.rag.domain.chunking.v4;

public record PageRange(Integer startPage, Integer endPage) {
    public PageRange {
        if ((startPage == null) != (endPage == null)) {
            throw new IllegalArgumentException("页码范围必须同时存在或同时为空");
        }
        if (startPage != null && (startPage < 1 || endPage < startPage)) {
            throw new IllegalArgumentException("页码范围无效");
        }
    }

    public static PageRange unknown() {
        return new PageRange(null, null);
    }
}
