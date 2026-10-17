package com.travelgo.config;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Presentation-only image mapping; recorded catalogue paths and supplier records remain unchanged. */
@Component("travelImages")
public class TravelImages {
    private static final String OFFICIAL_LOGO = "/images/travelgo-logo.png";
    private static final String DEFAULT_SCENE = "/images/hero-travel.jpg";
    private static final String PROPERTY_PLACEHOLDER = "/images/property-photo-unavailable.svg";
    /** Original paths are retained in SQL; only these established destination aliases change at render time. */
    private static final Map<String, String> LOCAL_ALIASES = Map.ofEntries(
        Map.entry("/images/europe_pkg_4k.jpg", "/images/paris.jpg"),
        Map.entry("/images/europe_classical.jpg", "/images/paris.jpg"),
        Map.entry("/images/europe_classical_4k.jpg", "/images/paris.jpg"),
        Map.entry("/images/paris_rome_tour.jpg", "/images/paris.jpg"),
        Map.entry("/images/tokyo_pkg_4k.jpg", "/images/tokyo.jpg"),
        Map.entry("/images/tokyo_kyoto_tour.jpg", "/images/tokyo.jpg"),
        Map.entry("/images/tokyo_kyoto_4k.jpg", "/images/tokyo.jpg"),
        Map.entry("/images/dubai_pkg_4k.jpg", "/images/dubai.jpg"),
        Map.entry("/images/dubai_desert_tour.jpg", "/images/dubai.jpg"),
        Map.entry("/images/dubai_desert_4k.jpg", "/images/dubai.jpg"),
        Map.entry("/images/rome_pkg_4k.jpg", "/images/rome.jpg"),
        Map.entry("/images/rome_renaissance_tour.jpg", "/images/rome.jpg"),
        Map.entry("/images/rome_renaissance_4k.jpg", "/images/rome.jpg"),
        Map.entry("/images/london_pkg_4k.jpg", "/images/london.jpg"),
        Map.entry("/images/london_uk.jpg", "/images/london.jpg"),
        Map.entry("/images/london_royal_highlights_4k.jpg", "/images/london.jpg"),
        Map.entry("/images/swiss_pkg_4k.jpg", "/images/swiss-alps-photo.jpg"),
        Map.entry("/images/swiss_alps.jpg", "/images/swiss-alps-photo.jpg"),
        Map.entry("/images/swiss_alps_retreat_4k.jpg", "/images/swiss-alps-photo.jpg"),
        Map.entry("/images/santorini_pkg_4k.jpg", "/images/santorini-photo.jpg"),
        Map.entry("/images/santorini.jpg", "/images/santorini-photo.jpg"),
        Map.entry("/images/santorini_sunset_getaway_4k.jpg", "/images/santorini-photo.jpg"),
        Map.entry("/images/maldives_pkg_4k.jpg", "/images/maldives-photo.jpg"),
        Map.entry("/images/maldives.jpg", "/images/maldives-photo.jpg"),
        Map.entry("/images/maldives_island_escape_4k.jpg", "/images/maldives-photo.jpg"),
        Map.entry("/images/kenya_pkg_4k.jpg", "/images/kenya-safari-photo.jpg"),
        Map.entry("/images/kenya_safari.jpg", "/images/kenya-safari-photo.jpg"),
        Map.entry("/images/kenya_safari_adventure_4k.jpg", "/images/kenya-safari-photo.jpg"));
    private static final Map<String, String> PHOTOS = Map.of(
        "/photo-1502602898657-3e91760cbb34", "paris",
        "/photo-1503899036084-c55cdd92da26", "tokyo",
        "/photo-1536098561742-ca998e48cbcc", "tokyo",
        "/photo-1512453979798-5ea266f8880c", "dubai",
        "/photo-1518684079-3c830dcef090", "dubai",
        "/photo-1552832230-c0197dd311b5", "rome",
        "/photo-1783443075946-86473bf6d319", "santorini-photo",
        "/photo-1752999050383-0ba4d17bee2a", "swiss-alps-photo",
        "/photo-1678543736046-45ac8ad142f7", "maldives-photo",
        "/photo-1517384491025-9132643e876a", "kenya-safari-photo");
    private static final Set<String> RESPONSIVE_SCENES = Set.of("paris", "tokyo", "dubai", "rome", "london", "istanbul", "singapore");
    private static final Set<String> DESTINATION_SCENES = Set.of(
        "paris", "tokyo", "dubai", "rome", "london", "istanbul", "singapore", "santorini", "maldives",
        "swiss_alps", "kenya_safari", "europe_classical", "swiss", "kenya", "europe", "hero-travel", "travel-auth", "cust-visa", "cust-flights",
        "tokyo_kyoto_tour", "paris_rome_tour", "dubai_desert_tour", "rome_renaissance_tour", "london_uk",
        "tokyo_kyoto", "dubai_desert", "rome_renaissance", "london_royal_highlights", "santorini_sunset_getaway",
        "maldives_island_escape", "kenya_safari_adventure", "swiss_alps_retreat", "bangkok",
        "santorini-photo", "swiss-alps-photo", "maldives-photo", "kenya-safari-photo");

    public String resolve(String recordedImage) {
        String safe = safeImage(recordedImage);
        return safe == null ? DEFAULT_SCENE : safe;
    }

    /** The editor preview shares the same mapping as rendered catalogue images. */
    public Map<String, String> aliases() {
        return LOCAL_ALIASES;
    }

    /** A missing tour cover uses its destination, rather than an unrelated beach image. */
    public String tour(String recordedImage, String destination) {
        String safe = safeImage(recordedImage);
        return safe == null || DEFAULT_SCENE.equals(safe) ? destinationScene(destination) : safe;
    }

    /** Gallery entries retain their supplied photo; invalid historical references use an honest placeholder. */
    public String gallery(String recordedImage) {
        String safe = safeImage(recordedImage);
        return safe == null ? PROPERTY_PLACEHOLDER : safe;
    }

    /** Destination photography never claims to show a particular hotel. */
    public boolean hotelPropertyPhoto(String recordedImage) {
        String safe = safeImage(recordedImage);
        if (safe == null || PROPERTY_PLACEHOLDER.equals(safe)) return false;
        if (!safe.startsWith("/images/")) {
            String host = URI.create(safe).getHost();
            return !"images.unsplash.com".equalsIgnoreCase(host) && !"unsplash.com".equalsIgnoreCase(host)
                    && !"images.pexels.com".equalsIgnoreCase(host) && !"cdn.pixabay.com".equalsIgnoreCase(host);
        }
        String name = safe.substring("/images/".length()).replaceFirst("\\.[^.]+$", "")
                .replaceFirst("(?:_pkg_4k|-4k|_4k|-large)$", "");
        return !DESTINATION_SCENES.contains(name) && !name.startsWith("auth-");
    }

    public String hotel(String recordedImage, String destination) {
        if (hotelPropertyPhoto(recordedImage)) return safeImage(recordedImage);
        String scene = destinationScene(destination);
        return DEFAULT_SCENE.equals(scene) ? PROPERTY_PLACEHOLDER : scene;
    }

    public String hotelCaption(String recordedImage) {
        return hotelPropertyPhoto(recordedImage) ? "Accommodation image supplied by the agency" : "Property photo unavailable · Destination preview";
    }

    public String hotelCaption(String recordedImage, String destination) {
        if (PROPERTY_PLACEHOLDER.equals(hotel(recordedImage, destination))) return "Property photo unavailable";
        return hotelCaption(recordedImage);
    }

    public String hotelAlt(String recordedImage, String hotelName, String destination) {
        if (hotelPropertyPhoto(recordedImage)) return hotelName;
        if (PROPERTY_PLACEHOLDER.equals(hotel(recordedImage, destination))) return "Property photo unavailable";
        return "Destination preview: " + destination;
    }

    public String srcset(String recordedImage) {
        String image = resolve(recordedImage);
        if (image.equals("/images/santorini-photo.jpg") || image.equals("/images/swiss-alps-photo.jpg"))
            return image + " 1400w, " + image.replace(".jpg", "-large.jpg") + " 3840w";
        if (image.equals("/images/maldives-photo.jpg"))
            return image + " 1400w, " + image.replace(".jpg", "-large.jpg") + " 2250w";
        if (image.equals("/images/kenya-safari-photo.jpg"))
            return image + " 1400w, " + image.replace(".jpg", "-large.jpg") + " 2820w";
        String name = image.replace("/images/", "").replace(".jpg", "");
        if (!RESPONSIVE_SCENES.contains(name) || !image.equals("/images/" + name + ".jpg")) return null;
        int width = name.equals("dubai") ? 3264 : name.equals("singapore") ? 3320 : 3840;
        return image + " 1400w, " + image.replace(".jpg", "-4k.jpg") + " " + width + "w";
    }

    /** The saved agency logo has no destination aliases or stock-photo fallback. */
    public String logo(String recordedImage) {
        if (recordedImage == null || recordedImage.isBlank()) return OFFICIAL_LOGO;
        String value = recordedImage.trim();
        if (value.startsWith("/images/") && value.matches("/images/[A-Za-z0-9_./-]+") && !value.contains("..")) return value;
        try {
            URI uri = URI.create(value);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) return value;
        } catch (IllegalArgumentException ignored) { /* Use the official local asset for invalid historical values. */ }
        return OFFICIAL_LOGO;
    }

    private String safeImage(String recordedImage) {
        if (recordedImage == null || recordedImage.isBlank()) return null;
        String value = recordedImage.trim();
        if (value.startsWith("/images/") && value.matches("/images/[A-Za-z0-9_./-]+") && !value.contains("..")) return LOCAL_ALIASES.getOrDefault(value, value);
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return null;
            if ("images.unsplash.com".equalsIgnoreCase(uri.getHost())) {
                String name = PHOTOS.get(uri.getPath());
                if (name != null) return "/images/" + name + ".jpg";
            }
            return value;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String destinationScene(String destination) {
        String place = destination == null ? "" : destination.toLowerCase(Locale.ROOT);
        if (place.contains("paris")) return "/images/paris.jpg";
        if (place.contains("tokyo") || place.contains("kyoto") || place.contains("japan")) return "/images/tokyo.jpg";
        if (place.contains("dubai")) return "/images/dubai.jpg";
        if (place.contains("rome")) return "/images/rome.jpg";
        if (place.contains("london")) return "/images/london.jpg";
        if (place.contains("istanbul")) return "/images/istanbul.jpg";
        if (place.contains("singapore")) return "/images/singapore.jpg";
        if (place.contains("santorini")) return "/images/santorini-photo.jpg";
        if (place.contains("maldives") || place.contains("malé") || place.contains("male,")) return "/images/maldives-photo.jpg";
        if (place.contains("switzerland") || place.contains("swiss") || place.contains("interlaken") || place.contains("zurich")) return "/images/swiss-alps-photo.jpg";
        if (place.contains("kenya") || place.contains("nairobi") || place.contains("masai mara") || place.contains("maasai mara")) return "/images/kenya-safari-photo.jpg";
        return DEFAULT_SCENE;
    }
}
