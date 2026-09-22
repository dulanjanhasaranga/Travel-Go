package com.travelgo.service;

import java.math.BigDecimal;
import java.net.URI;

/** Shared constraints match stored catalogue columns. */
final class CatalogueInput {
    private CatalogueInput() {}
    static String required(String value,String label) {
        if(value==null || value.isBlank() || value.trim().length()>255)
            throw new IllegalArgumentException(label+" is required and must be at most 255 characters.");
        return value.trim();
    }
    static void text(String value,int limit,String label) {
        if(value!=null && value.length()>limit) throw new IllegalArgumentException(label+" is too long (maximum "+limit+" characters).");
    }
    static void image(String value) {
        text(value,255,"Image URL");
        if(value==null || value.isBlank()) return;
        if(value.startsWith("/images/") && !value.contains("..")) return;
        try { URI uri=URI.create(value); if("https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null) return; }
        catch(IllegalArgumentException ignored) {}
        throw new IllegalArgumentException("Use an HTTPS image URL or a local /images/ path.");
    }
    static void price(BigDecimal price) {
        if(price==null || price.signum()<=0 || price.stripTrailingZeros().scale()>2 || price.compareTo(new BigDecimal("99999999.99"))>0)
            throw new IllegalArgumentException("Enter a positive nightly price with at most two decimal places.");
    }
}
