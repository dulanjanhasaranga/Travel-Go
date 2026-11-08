package com.travelgo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One reviewed public-content recovery. No schema, account or financial writes. */
@Service
public class PublicCatalogueRecoveryService {
    public static final String VERSION = "local-public-catalogue-20261108";
    public static final String SHA256 = "a6c53ba88a03027ef76fbe31b1c61a7a2806b40793677f0c5c832c5bd9d25b16";
    public static final String ACTION = "RESTORE_PUBLIC_CATALOGUE";
    private static final long MARKER_ID = 20261108L;
    private final EntityManager em;
    private final DestinationRepository destinations;
    private final PackageCategoryRepository categories;
    private final TourPackageRepository packages;
    private final HotelRepository hotels;
    private final DepartureRepository departures;
    private final AuditLogRepository audit;

    public PublicCatalogueRecoveryService(EntityManager em, DestinationRepository destinations,
            PackageCategoryRepository categories, TourPackageRepository packages,
            HotelRepository hotels, DepartureRepository departures, AuditLogRepository audit) {
        this.em=em;this.destinations=destinations;this.categories=categories;this.packages=packages;
        this.hotels=hotels;this.departures=departures;this.audit=audit;
    }

    @Transactional
    public Result restoreOnce() {
        JsonNode source=validatedSnapshot();
        // Serialize overlapping starts using an existing singleton row; no new table or constraint.
        if(em.createQuery("select s from SystemSettings s order by s.id",SystemSettings.class)
                .setMaxResults(1).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint("jakarta.persistence.lock.timeout",10000).getResultList().isEmpty())
            throw new IllegalStateException("Public catalogue recovery requires the existing agency settings.");
        if(audit.existsByActionAndEntityNameAndEntityId(ACTION,VERSION,MARKER_ID))return new Result(false,0);
        int inserted=0;
        Map<String,Destination> knownDestinations=new HashMap<>();
        for(Destination value:destinations.findAll())unique(knownDestinations,key(value.getCountry(),value.getCity()),value);
        Map<Long,Destination> destinationIds=new HashMap<>();
        for(JsonNode row:source.path("destinations")){
            String key=key(text(row,"country"),text(row,"city"));
            Destination value=knownDestinations.get(key);
            if(value==null){
                value=new Destination();value.setCountry(text(row,"country"));value.setCity(text(row,"city"));
                value.setDescription(text(row,"description"));value.setImage(text(row,"image"));
                value.setActive(row.path("is_active").asBoolean());value.setFeatured(row.path("featured").asBoolean());
                value=destinations.save(value);knownDestinations.put(key,value);inserted++;
            }
            destinationIds.put(row.path("id").asLong(),value);
        }
        Map<String,PackageCategory> knownCategories=new HashMap<>();
        for(PackageCategory value:categories.findAll())unique(knownCategories,key(value.getName()),value);
        Map<Long,PackageCategory> categoryIds=new HashMap<>();
        for(JsonNode row:source.path("package_categories")){
            String key=key(text(row,"name"));PackageCategory value=knownCategories.get(key);
            if(value==null){value=new PackageCategory();value.setName(text(row,"name"));value.setDescription(text(row,"description"));
                value.setActive(row.path("is_active").asBoolean());value=categories.save(value);knownCategories.put(key,value);inserted++;}
            categoryIds.put(row.path("id").asLong(),value);
        }
        Map<String,TourPackage> knownPackages=new HashMap<>();
        for(TourPackage value:packages.findAll())unique(knownPackages,key(value.getName()),value);
        Map<Long,TourPackage> packageIds=new HashMap<>();
        for(JsonNode row:source.path("tour_packages")){
            String key=key(text(row,"name"));TourPackage value=knownPackages.get(key);
            if(value==null){
                value=new TourPackage();value.setName(text(row,"name"));
                value.setDestination(required(destinationIds,row.path("destination_id").asLong()));
                value.setCategory(required(categoryIds,row.path("category_id").asLong()));
                value.setDescription(text(row,"description"));value.setBasePrice(new BigDecimal(row.path("base_price").asText()));
                value.setDurationDays(row.path("duration_days").asInt());value.setMaxCapacity(row.path("max_capacity").asInt());
                value.setFlightDetails(text(row,"flight_details"));value.setIncludedServices(text(row,"included_services"));
                value.setImage(text(row,"image"));value.setActive(row.path("is_active").asBoolean());value.setFeatured(row.path("featured").asBoolean());
                value.setItinerary(text(row,"itinerary"));value.setExcludedServices(text(row,"excluded_services"));
                value.setTravelerInformation(text(row,"traveler_information"));
                value=packages.save(value);knownPackages.put(key,value);inserted++;
            }
            packageIds.put(row.path("id").asLong(),value);
        }
        Map<String,Hotel> knownHotels=new HashMap<>();
        for(Hotel value:hotels.findAll())unique(knownHotels,key(value.getDestination().getId().toString(),value.getName()),value);
        for(JsonNode row:source.path("hotels")){
            Destination destination=required(destinationIds,row.path("destination_id").asLong());
            String key=key(destination.getId().toString(),text(row,"name"));
            if(!knownHotels.containsKey(key)){
                Hotel value=new Hotel();value.setName(text(row,"name"));value.setDestination(destination);value.setAddress(text(row,"address"));
                value.setStarRating(row.path("star_rating").isNull()?null:row.path("star_rating").asInt());
                value.setPricePerNight(new BigDecimal(row.path("price_per_night").asText()));value.setDescription(text(row,"description"));
                value.setImage(text(row,"image"));value.setActive(row.path("is_active").asBoolean());
                knownHotels.put(key,hotels.save(value));inserted++;
            }
        }
        Set<String> knownDepartures=new HashSet<>();
        for(Departure value:departures.findAll())knownDepartures.add(key(value.getTourPackage().getId().toString(),value.getDepartureDate().toString()));
        for(JsonNode row:source.path("departures")){
            TourPackage tour=required(packageIds,row.path("tour_package_id").asLong());
            LocalDate start=LocalDate.parse(text(row,"departure_date")),end=LocalDate.parse(text(row,"return_date"));
            String key=key(tour.getId().toString(),start.toString());
            if(!knownDepartures.contains(key)){
                int capacity=row.path("total_capacity").asInt();
                if(capacity<1||end.isBefore(start))throw new IllegalStateException("Invalid reviewed departure schedule.");
                Departure value=new Departure();value.setTourPackage(tour);value.setDepartureDate(start);value.setReturnDate(end);
                value.setTotalCapacity(capacity);
                // Local bookings are not migrated. New cloud departures start without phantom reservations.
                value.setAvailableSeats(capacity);departures.save(value);knownDepartures.add(key);inserted++;
            }
        }
        audit.save(new AuditLog(ACTION,VERSION,MARKER_ID,"system",
                "Owner-requested public catalogue recovery; sha256="+SHA256+"; inserted="+inserted+
                "; source=9 destinations,3 categories,9 tours,4 hotels,27 departures. Existing records and private data preserved."));
        em.flush();
        return new Result(true,inserted);
    }

    private JsonNode validatedSnapshot(){
        try(var input=new ClassPathResource("catalogue/"+VERSION+".json").getInputStream()){
            byte[] bytes=input.readNBytes(200001);
            // Stable across Git's Windows CRLF and Linux LF checkouts.
            bytes=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(bytes.length>200000||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new IllegalStateException("Public catalogue recovery snapshot checksum differs.");
            JsonNode root=new ObjectMapper().readTree(bytes),tables=root.path("tables");
            if(!VERSION.equals(root.path("version").asText())||tables.size()!=5)throw new IllegalStateException("Unexpected public catalogue snapshot.");
            for(var expected:Map.of("destinations",9,"package_categories",3,"tour_packages",9,"hotels",4,"departures",27).entrySet())
                if(!tables.path(expected.getKey()).isArray()||tables.path(expected.getKey()).size()!=expected.getValue())
                    throw new IllegalStateException("Public catalogue recovery record count differs.");
            return tables;
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("Cannot read the reviewed public catalogue recovery snapshot.",failure);}
    }
    private static String text(JsonNode row,String field){return row.path(field).isNull()?null:row.path(field).asText();}
    private static String key(String... values){return String.join("\u0000",Arrays.stream(values).map(value->Objects.requireNonNull(value).strip().toLowerCase(Locale.ROOT)).toList());}
    private static <T> T required(Map<Long,T> values,long id){return Objects.requireNonNull(values.get(id),"Missing reviewed catalogue relationship");}
    private static <T> void unique(Map<String,T> values,String key,T value){if(values.putIfAbsent(key,value)!=null)throw new IllegalStateException("Ambiguous existing catalogue records; recovery made no changes.");}
    public record Result(boolean applied,int insertedRecords){}
}
