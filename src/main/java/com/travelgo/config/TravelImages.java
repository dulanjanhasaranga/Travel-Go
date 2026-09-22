package com.travelgo.config;

import java.net.URI;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Local copies of the original catalogue photography; custom catalogue images remain supported. */
@Component("travelImages")
public class TravelImages {
    public String srcset(String recordedImage) {
        String image = resolve(recordedImage);
        if(image.equals("/images/london.jpg")||image.equals("/images/istanbul.jpg"))return image+" 1400w, "+image.replace(".jpg","-4k.jpg")+" 3840w";
        if(image.equals("/images/singapore.jpg"))return image+" 1400w, "+image.replace(".jpg","-4k.jpg")+" 3320w";
        if (PHOTOS.values().stream().noneMatch(name -> image.equals("/images/" + name + ".jpg"))) return null;
        int masterWidth = image.contains("dubai") ? 3264 : 3840;
        return image + " 1400w, " + image.replace(".jpg", "-4k.jpg") + " " + masterWidth + "w";
    }
    private static final Map<String, String> PHOTOS = Map.of(
        "/photo-1502602898657-3e91760cbb34", "paris",
        "/photo-1503899036084-c55cdd92da26", "tokyo",
        "/photo-1512453979798-5ea266f8880c", "dubai",
        "/photo-1552832230-c0197dd311b5", "rome");

    public String resolve(String recordedImage) {
        if (recordedImage == null || recordedImage.isBlank()) return "/images/hero-travel.jpg";
        try {
            URI uri = URI.create(recordedImage);
            if ("images.unsplash.com".equalsIgnoreCase(uri.getHost())) {
                String name = PHOTOS.get(uri.getPath());
                if (name != null) return "/images/" + name + ".jpg";
            }
        } catch (IllegalArgumentException ignored) {
            return "/images/hero-travel.jpg";
        }
        return recordedImage;
    }
}
