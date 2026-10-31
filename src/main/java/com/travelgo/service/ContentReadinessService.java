package com.travelgo.service;

import com.travelgo.config.TravelImages;
import com.travelgo.entity.Hotel;
import com.travelgo.entity.TourPackage;
import com.travelgo.repository.HotelRepository;
import com.travelgo.repository.TourPackageRepository;
import com.travelgo.repository.TravelPolicyRepository;
import com.travelgo.repository.VisaChecklistRepository;
import com.travelgo.repository.SystemSettingsRepository;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A review workspace, not an automatic declaration of supplier or legal approval. */
@Service
@Transactional(readOnly = true)
public class ContentReadinessService {
    private final AdminAccess access;
    private final TourPackageRepository packages;
    private final HotelRepository hotels;
    private final VisaChecklistRepository checklists;
    private final TravelPolicyRepository policies;
    private final TravelImages images;
    private final Clock clock;
    private final SystemSettingsRepository settings;
    private final boolean policyRequired;
    private final boolean supplierRequired;

    public ContentReadinessService(AdminAccess access, TourPackageRepository packages, HotelRepository hotels,
            VisaChecklistRepository checklists, TravelPolicyRepository policies, SystemSettingsRepository settings, TravelImages images, Clock clock,
            @Value("${travelgo.bookings.require-published-policy:false}") boolean policyRequired,
            @Value("${travelgo.bookings.require-supplier-confirmation:false}") boolean supplierRequired) {
        this.access = access; this.packages = packages; this.hotels = hotels; this.checklists = checklists;
        this.policies = policies; this.images = images; this.clock = clock;
        this.settings = settings;
        this.policyRequired = policyRequired; this.supplierRequired = supplierRequired;
    }

    public Review overview() {
        access.requireAdministrator("SYSTEM_SETTINGS_VIEW", "SYSTEM_SETTINGS_MANAGE");
        var tours = packages.findAll().stream().sorted(Comparator.comparing(TourPackage::getId)).map(this::tour).toList();
        var properties = hotels.findAll().stream().sorted(Comparator.comparing(Hotel::getId)).map(this::hotel).toList();
        var requirements = checklists.findAllByOrderByCreatedAtDescIdDesc().stream().map(c -> new Requirement(
                c.getId(), place(c.getDestination()), c.getVisaType().name(), c.getRevision(), c.getStatus().name(),
                c.getSourceUrl(), c.getSourceReviewedOn() == null ? "Not recorded" : c.getSourceReviewedOn().toString(),
                c.getItems().size())).toList();
        var policy = policies.findFirstByPublishedTrueOrderByPublishedAtDescIdDesc();
        var currencySettings = settings.findAll();
        String recordedCurrency = currencySettings.isEmpty() ? "USD (default)" : currencySettings.size() > 1 ? "Multiple settings records"
                : currencySettings.get(0).getCurrency() == null ? "Not recorded" : currencySettings.get(0).getCurrency();
        boolean currencyConsistent = currencySettings.isEmpty() || currencySettings.size() == 1 && "USD".equals(currencySettings.get(0).getCurrency());
        return new Review(Instant.now(clock), tours, properties, requirements,
                policy.map(p -> p.getId()).orElse(null), policy.map(p -> p.getTitle()).orElse(null),
                policyRequired, supplierRequired, recordedCurrency, currencyConsistent, clock.getZone().getId());
    }

    private CatalogueItem tour(TourPackage p) {
        var gaps = new ArrayList<String>();
        missing(gaps, p.getDescription(), "Description"); missing(gaps, p.getItinerary(), "Day-by-day itinerary");
        missing(gaps, p.getIncludedServices(), "Included services"); missing(gaps, p.getExcludedServices(), "Excluded services");
        missing(gaps, p.getTravelerInformation(), "Traveler guidance"); missing(gaps, p.getImage(), "Recorded cover image");
        if (p.getBasePrice() == null || p.getBasePrice().signum() <= 0) gaps.add("Positive package price");
        if (p.getDurationDays() == null || p.getDurationDays() < 1) gaps.add("Valid duration");
        if (p.getMaxCapacity() == null || p.getMaxCapacity() < 1) gaps.add("Valid capacity");
        String destination = place(p.getDestination());
        String gallery = p.getGallery().stream().map(i -> value(i.getUrl()) + " | " + value(i.getCaption())
                + " | " + i.getDisplayOrder()).collect(java.util.stream.Collectors.joining("\n"));
        return new CatalogueItem("Tour", p.getId(), p.getName(), destination,
                p.isActive() && p.getDestination() != null && p.getDestination().isActive(), p.getBasePrice(),
                "per traveler", p.getImage(), images.tour(p.getImage(), destination), "Destination / agency tour image",
                p.getDescription(), p.getItinerary(), p.getIncludedServices(), p.getExcludedServices(), p.getTravelerInformation(),
                gallery, p.getFlightDetails(), p.getCategory() == null ? null : p.getCategory().getName(), p.getDurationDays(), p.getMaxCapacity(), null, null, List.copyOf(gaps),
                digest("Tour", p.getId(), p.getName(), p.getDestination() == null ? null : p.getDestination().getId(), destination, p.isActive(), destinationActive(p.getDestination()),
                        p.getBasePrice(), p.getDurationDays(), p.getMaxCapacity(), p.getDescription(), p.getItinerary(),
                        p.getIncludedServices(), p.getExcludedServices(), p.getTravelerInformation(), p.getImage(), gallery,
                        p.getFlightDetails(), p.getCategory() == null ? null : p.getCategory().getId(), p.getCategory() == null ? null : p.getCategory().getName()));
    }

    private CatalogueItem hotel(Hotel h) {
        var gaps = new ArrayList<String>();
        missing(gaps, h.getAddress(), "Property address"); missing(gaps, h.getDescription(), "Property description");
        if (h.getPricePerNight() == null || h.getPricePerNight().signum() <= 0) gaps.add("Positive nightly room price");
        if (!images.hotelPropertyPhoto(h.getImage())) gaps.add("Agency-supplied property photograph");
        String destination = place(h.getDestination());
        return new CatalogueItem("Hotel", h.getId(), h.getName(), destination,
                h.isActive() && h.getDestination() != null && h.getDestination().isActive(), h.getPricePerNight(),
                "per room / night", h.getImage(), images.hotel(h.getImage(), destination), images.hotelCaption(h.getImage(), destination),
                h.getDescription(), null, null, null, null, null, null, null, null, null, h.getAddress(), h.getStarRating(), List.copyOf(gaps),
                digest("Hotel", h.getId(), h.getName(), h.getDestination() == null ? null : h.getDestination().getId(), destination, h.isActive(), destinationActive(h.getDestination()),
                        h.getPricePerNight(), h.getAddress(), h.getDescription(), h.getImage(), h.getStarRating()));
    }

    /** Export existing content plus blank evidence columns; this endpoint never records approval. */
    public byte[] catalogueWorksheet() {
        Review review = overview();
        StringBuilder csv = new StringBuilder("\uFEFF");
        row(csv, "Type", "Record ID", "Name", "Destination", "Available for requests", "Currency", "Recorded price", "Price basis",
                "Recorded image", "Displayed image", "Image description", "Gallery references", "Description", "Itinerary", "Included services",
                "Excluded services", "Traveler guidance", "Flight details", "Category", "Duration days", "Capacity", "Property address", "Star rating", "Missing recorded fields",
                "Recorded content SHA-256", "Generated UTC", "Review status", "Supplier name and source reference", "Supplier availability checked on",
                "Price and currency approved by", "Photo owner and license or supplier permission reference", "Image depicts this property or itinerary",
                "Agency reviewer", "Reviewed on", "Review decision and conditions");
        java.util.stream.Stream.concat(review.tours().stream(), review.hotels().stream()).forEach(item -> row(csv,
                item.kind(), item.id(), item.name(), item.destination(), item.available(), "USD", item.price(), item.priceBasis(),
                item.recordedImage(), item.displayedImage(), item.imageDescription(), item.gallery(), item.description(), item.itinerary(),
                item.inclusions(), item.exclusions(), item.travelerGuidance(), item.flightDetails(), item.category(), item.durationDays(), item.capacity(), item.address(), item.starRating(),
                String.join("; ", item.gaps()), item.contentHash(), review.generatedAt(), "Pending agency review", "", "", "", "", "", "", "", ""));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public byte[] workflowWorksheet() {
        Review review = overview();
        String document = """
                # TravelGO client workflow acceptance worksheet

                Status: awaiting client review and signature. This download does not approve or publish content.
                Generated UTC: %s
                Current published policy: %s
                Recorded currency setting: %s
                Currency consistency with supported USD workflow: %s
                Effective booking clock time zone: %s

                Client legal operator: ____________________
                Authorized client reviewer and role: ____________________
                Supported destinations and traveler nationalities: ____________________
                Approved payment provider, channels and currency: ____________________
                Policy, supplier and photo evidence references: ____________________
                Release or source archive identifier: ____________________

                Review each implemented rule with the client. Mark accepted, change requested or out of scope and record the reason.

                | Implemented workflow | Client decision | Evidence or requested change |
                | --- | --- | --- |
                | Catalogue and prices currently use USD; approval of LKR or other currencies is not inferred. | | |
                | Every booking requires visa approval before final payment, including visa-exempt or domestic trips under the current project rules. | | |
                | A booking request is not an external supplier reservation; final confirmation needs approved visa and successful full payment. | | |
                | Published policy acceptance required in this running configuration: %s. | | |
                | Supplier confirmation required in this running configuration: %s. | | |
                | Customers can change or cancel eligible pending bookings before visa review or any payment record; supplier confirmation also closes direct changes. | | |
                | Traveler counts require a complete matching traveler list. Room allocation uses the existing calculation and max(1, duration minus 1) nights. | | |
                | Visa officers review documents before issuing nonnegative visa and documentation charges with a positive total. | | |
                | Upfront payment equals stored visa plus documentation charges and is due within 24 hours of issue; expired unpaid charges require officer reissue. | | |
                | Successful upfront payment starts processing; only processing applications can be approved or rejected. | | |
                | Final payment equals the stored package and optional hotel costs, requires approved visa and is due before the deadline three days before departure. | | |
                | Rejection cancels the booking and refunds the documentation charge only; the visa charge is retained under the current rules. | | |
                | Other cancellation refunds require staff review and agreed supplier/agency terms; provider refund settlement needs actual evidence. | | |
                | Simulated checkout does not establish merchant approval or real payment/refund acceptance. | | |
                | A visa checklist describes officer-reviewed requirements; embassy processing time and approval are not guaranteed. | | |

                ## Decision

                Unresolved changes and owner: ____________________
                Approved scope and exceptions: ____________________
                Client signature and date: ____________________
                Agency representative signature and date: ____________________

                Record the signed worksheet and current catalogue evidence in the client handover. If a content hash changes, review that record again.
                Content hashes cover recorded fields and image references, not downloaded image bytes or photo permission. Recheck images and their evidence after an asset changes at the same path.
                This worksheet is not a contract, legal opinion or approval of unpublished policies. Do not submit passport copies, card details or provider credentials here.
                """.formatted(review.generatedAt(), review.policyId() == null ? "None published" : "Version " + review.policyId(),
                        review.recordedCurrency(), review.currencyConsistent() ? "Consistent" : "Review required; no amounts have been converted",
                        review.effectiveTimezone(),
                        review.policyRequired() ? "Yes" : "No", review.supplierRequired() ? "Yes" : "No");
        return document.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean destinationActive(com.travelgo.entity.Destination destination) { return destination != null && destination.isActive(); }
    private static String place(com.travelgo.entity.Destination d) { return d == null ? "Destination missing" : value(d.getCity()) + ", " + value(d.getCountry()); }
    private static String value(Object value) { return value == null ? "" : value.toString(); }
    private static void missing(List<String> gaps, String field, String label) { if (field == null || field.isBlank()) gaps.add(label); }

    private static String digest(Object... fields) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(bytes)) {
                for (Object field : fields) {
                    if (field == null) { output.writeInt(-1); continue; }
                    byte[] encoded = field.toString().getBytes(StandardCharsets.UTF_8);
                    output.writeInt(encoded.length); output.write(encoded);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The content worksheet could not be prepared.", impossible);
        }
    }

    /** Quote every CSV cell and neutralize formulas even when a catalogue name begins with whitespace. */
    private static void row(StringBuilder csv, Object... cells) {
        for (int index = 0; index < cells.length; index++) {
            if (index > 0) csv.append(',');
            String text = value(cells[index]);
            String leading = text.stripLeading();
            if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0
                    || text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n")) text = "'" + text;
            csv.append('"').append(text.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    public record Review(Instant generatedAt, List<CatalogueItem> tours, List<CatalogueItem> hotels, List<Requirement> requirements,
            Long policyId, String policyTitle, boolean policyRequired, boolean supplierRequired, String recordedCurrency, boolean currencyConsistent, String effectiveTimezone) {
        public long incompleteTours() { return tours.stream().filter(t -> !t.gaps().isEmpty()).count(); }
        public long incompleteHotels() { return hotels.stream().filter(h -> !h.gaps().isEmpty()).count(); }
    }
    public record CatalogueItem(String kind, Long id, String name, String destination, boolean available, BigDecimal price,
            String priceBasis, String recordedImage, String displayedImage, String imageDescription, String description, String itinerary,
            String inclusions, String exclusions, String travelerGuidance, String gallery, String flightDetails, String category, Integer durationDays, Integer capacity,
            String address, Integer starRating, List<String> gaps, String contentHash) { }
    public record Requirement(Long id, String destination, String visaType, int revision, String status, String source,
            String reviewedOn, int documentCount) { }
}
