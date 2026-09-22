package com.travelgo.service;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
/** Original four offers, recoverable without overwriting edited or inactive records. */
@Service
public class OriginalCatalogueService {
    private final TourPackageRepository tourPackageRepository;
    private final DestinationRepository destinationRepository;
    private final PackageCategoryRepository categoryRepository;
    private final HotelRepository hotelRepository;
    private final com.travelgo.repository.DepartureRepository departureRepository;
    public OriginalCatalogueService(TourPackageRepository p,DestinationRepository d,PackageCategoryRepository c,HotelRepository h, com.travelgo.repository.DepartureRepository dep){
        tourPackageRepository=p;destinationRepository=d;categoryRepository=c;hotelRepository=h;departureRepository=dep;
    }
    @org.springframework.transaction.annotation.Transactional
    public void install() {
        {
            System.out.println("  Seeding default travel catalog (Destinations, Categories, Packages, Hotels)...");
            // 1. Destinations
            // Update existing destination images first
            for (Destination d : destinationRepository.findAll()) {
                String c = d.getCity().toLowerCase(java.util.Locale.ROOT);
                if (c.equals("paris")) d.setImage("/images/europe_classical_4k.jpg");
                else if (c.equals("tokyo")) d.setImage("/images/tokyo_kyoto_4k.jpg");
                else if (c.equals("dubai")) d.setImage("/images/dubai_desert_4k.jpg");
                else if (c.equals("rome")) d.setImage("/images/rome_renaissance_4k.jpg");
                else if (c.equals("london")) d.setImage("/images/london_royal_highlights_4k.jpg");
                else if (c.equals("swiss alps")) d.setImage("/images/swiss_alps_retreat_4k.jpg");
                else if (c.equals("santorini")) d.setImage("/images/santorini_sunset_getaway_4k.jpg");
                else if (c.equals("maldives")) d.setImage("/images/maldives_island_escape_4k.jpg");
                else if (c.equals("nairobi")) d.setImage("/images/kenya_safari_adventure_4k.jpg");
                destinationRepository.save(d);
            }
            
            Destination paris = createDestinationIfNotExists("France", "Paris",
                    "The city of lights, renowned for art, gastronomy, world-class monuments, and historical culture.",
                    "/images/europe_classical_4k.jpg");
            Destination tokyo = createDestinationIfNotExists("Japan", "Tokyo",
                    "A dynamic blend of ultra-modern neon-lit skyscrapers and historic temples with rich traditions.",
                    "/images/tokyo_kyoto_4k.jpg");
            Destination dubai = createDestinationIfNotExists("UAE", "Dubai",
                    "A futuristic metropolis famous for luxury shopping, ultramodern architecture, and lively nightlife.",
                    "/images/dubai_desert_4k.jpg");
            Destination rome = createDestinationIfNotExists("Italy", "Rome",
                    "The Eternal City, home to nearly three millennia of globally influential art, architecture, and culture.",
                    "/images/rome_renaissance_4k.jpg");

            // 2. Categories
            PackageCategory cultural = createCategoryIfNotExists("Cultural Heritage", "Historical tours, landmark access, and cultural immersion.");
            PackageCategory luxury = createCategoryIfNotExists("Luxury & Leisure", "Exclusive experiences, private transfers, and premier excursions.");
            PackageCategory adventure = createCategoryIfNotExists("Adventure & Safari", "Outdoor expeditions, desert safaris, and nature trails.");

            // 3. Tour Packages
            createPackage("European Classical Journey", cultural, paris,
                    "Covers Paris and Rome with guided museum access and high-speed rail transfers between cities.",
                    new BigDecimal("1299.00"), 7, 20,
                    "Return Economy Ticket Included",
                    "Skip-the-line Louvre & Colosseum, rail transfers, bilingual tour guide",
                    "/images/europe_pkg_4k.jpg");

            createPackage("Tokyo & Kyoto Cultural Tour", cultural, tokyo,
                    "Discover Shinjuku, Asakusa Sensoji temple, Hakone Mt. Fuji views, and traditional Gion geisha districts.",
                    new BigDecimal("1549.00"), 8, 15,
                    "Tokyo Haneda/Narita Direct Flights Included",
                    "7-Day Shinkansen Bullet Train Pass, tea ceremony, private temple tour",
                    "/images/tokyo_pkg_4k.jpg");

            createPackage("Dubai City & Desert Expedition", adventure, dubai,
                    "Includes red dune safari, evening dhow cruise along Dubai Marina, and Burj Khalifa 124th floor tickets.",
                    new BigDecimal("899.00"), 5, 25,
                    "Direct Dubai Return Flights",
                    "4x4 Safari, BBQ dinner, Marina cruise, Burj Khalifa observation deck",
                    "/images/dubai_pkg_4k.jpg");

            createPackage("Italian Renaissance & Rome", luxury, rome,
                    "Experience ancient Roman amphitheaters, Vatican private tours, and authentic Tuscan wine tasting.",
                    new BigDecimal("1199.00"), 6, 18,
                    "Rome Fiumicino Return Flights",
                    "Vatican Museums VIP pass, Colosseum underground tour, guided wine tasting",
                    "/images/rome_pkg_4k.jpg");

            // 4. New Destinations & Packages (London, Swiss Alps, Santorini, Maldives, Kenya)
            Destination london = createDestinationIfNotExists("UK", "London",
                    "A vibrant capital blending centuries of history with world-class art, culture, and iconic landmarks.",
                    "/images/london_royal_highlights_4k.jpg");
            Destination swissAlps = createDestinationIfNotExists("Switzerland", "Swiss Alps",
                    "Pristine alpine lakes, snow-capped peaks, and cozy chalets in a breathtaking mountain setting.",
                    "/images/swiss_alps_retreat_4k.jpg");
            Destination santorini = createDestinationIfNotExists("Greece", "Santorini",
                    "Famous for stunning sunsets, white-washed buildings, and deep blue Aegean waters.",
                    "/images/santorini_sunset_getaway_4k.jpg");
            Destination maldives = createDestinationIfNotExists("Maldives", "Maldives",
                    "A tropical paradise of crystal-clear waters, white sandy beaches, and luxury overwater bungalows.",
                    "/images/maldives_island_escape_4k.jpg");
            Destination kenya = createDestinationIfNotExists("Kenya", "Nairobi",
                    "Experience majestic wildlife safaris, stunning savannas, and spectacular golden hour sunsets.",
                    "/images/kenya_safari_adventure_4k.jpg");

            createPackage("London Royal Highlights", cultural, london,
                    "Explore the Tower of London, Big Ben, and enjoy a Thames River dinner cruise.",
                    new BigDecimal("1150.00"), 5, 20,
                    "Direct Heathrow Return Flights",
                    "Tower of London passes, Thames cruise, Afternoon Tea experience",
                    "/images/london_pkg_4k.jpg");
            createPackage("Swiss Alps Retreat", luxury, swissAlps,
                    "Relax in a luxury chalet, enjoy scenic train rides, and discover pristine mountain lakes.",
                    new BigDecimal("1850.00"), 6, 12,
                    "Zurich Return Flights Included",
                    "Glacier Express tickets, Chalet accommodation, Spa access",
                    "/images/swiss_pkg_4k.jpg");
            createPackage("Santorini Sunset Getaway", luxury, santorini,
                    "Experience the romance of Oia sunsets with a private catamaran cruise and luxury villa stay.",
                    new BigDecimal("1650.00"), 5, 15,
                    "Athens Transfer & Flights Included",
                    "Private Catamaran cruise, Wine tasting, Villa accommodation",
                    "/images/santorini_pkg_4k.jpg");
            createPackage("Maldives Island Escape", luxury, maldives,
                    "Unwind in a private overwater bungalow surrounded by crystal clear turquoise waters and coral reefs.",
                    new BigDecimal("2999.00"), 7, 10,
                    "Male Return Flights & Seaplane Transfer",
                    "Overwater Bungalow, Snorkeling gear, All-inclusive dining",
                    "/images/maldives_pkg_4k.jpg");
            createPackage("Kenya Safari Adventure", adventure, kenya,
                    "Witness the Great Migration, spot the Big Five, and sleep under the stars in a luxury tented camp.",
                    new BigDecimal("2450.00"), 8, 14,
                    "Nairobi Return Flights Included",
                    "4x4 Game Drives, Maasai Mara park fees, Luxury tented camp",
                    "/images/kenya_pkg_4k.jpg");

            // 5. Hotels (Optional Add-ons)
            createHotelIfNotExists("Grand Hotel de Paris", paris, "12 Rue de Rivoli, Paris", 4, new BigDecimal("180.00"), "Charming Parisian boutique hotel near the Louvre.");
            createHotelIfNotExists("Tokyo Skyline Shinjuku", tokyo, "3-1 Shinjuku, Tokyo", 4, new BigDecimal("150.00"), "Modern high-rise hotel offering breathtaking city views.");
            createHotelIfNotExists("Burj View Luxury Suites", dubai, "Downtown Dubai, UAE", 5, new BigDecimal("250.00"), "Opulent 5-star hotel adjacent to Dubai Mall.");
            createHotelIfNotExists("Hotel Roma Centro", rome, "Via Nazionale 44, Rome", 3, new BigDecimal("120.00"), "Comfortable central hotel within walking distance of the Colosseum.");

            System.out.println("  Original catalogue checked; missing records added, existing records preserved.");
            
            // Seed departures for any existing packages that don't have them
            for (TourPackage pkg : tourPackageRepository.findAll()) {
                if (departureRepository.findByTourPackage_Id(pkg.getId()).isEmpty()) {
                    for (int i = 1; i <= 3; i++) {
                        Departure dep = new Departure();
                        dep.setTourPackage(pkg);
                        dep.setDepartureDate(java.time.LocalDate.now().plusDays(i * 15));
                        dep.setReturnDate(java.time.LocalDate.now().plusDays(i * 15 + pkg.getDurationDays()));
                        dep.setTotalCapacity(pkg.getMaxCapacity());
                        dep.setAvailableSeats(pkg.getMaxCapacity());
                        departureRepository.save(dep);
                    }
                }
            }
        }
    }

    private Destination createDestinationIfNotExists(String country, String city, String description, String image) {
        List<Destination> list = destinationRepository.findAll();
        for (Destination d : list) {
            if (d.getCity().equalsIgnoreCase(city) && d.getCountry().equalsIgnoreCase(country)) {
                return d;
            }
        }
        Destination dest = new Destination();
        dest.setCountry(country);
        dest.setCity(city);
        dest.setDescription(description);
        dest.setImage(image);
        dest.setActive(true);
        dest.setFeatured(false);
        return destinationRepository.save(dest);
    }

    private PackageCategory createCategoryIfNotExists(String name, String description) {
        List<PackageCategory> list = categoryRepository.findAll();
        for (PackageCategory c : list) {
            if (c.getName().equalsIgnoreCase(name)) {
                return c;
            }
        }
        PackageCategory cat = new PackageCategory();
        cat.setName(name);
        cat.setDescription(description);
        cat.setActive(true);
        return categoryRepository.save(cat);
    }

    private void createPackage(String name, PackageCategory category, Destination destination,
                               String description, BigDecimal price, int days, int capacity,
                               String flight, String services, String image) {
        if (tourPackageRepository.findAll().stream().anyMatch(p -> name.equalsIgnoreCase(p.getName()))) return;
        TourPackage pkg = new TourPackage();
        pkg.setName(name);
        pkg.setCategory(category);
        pkg.setDestination(destination);
        pkg.setDescription(description);
        pkg.setBasePrice(price);
        pkg.setDurationDays(days);
        pkg.setMaxCapacity(capacity);
        pkg.setFlightDetails(flight);
        pkg.setIncludedServices(services);
        pkg.setImage(image);
        pkg.setActive(true);
        pkg.setFeatured(false);
        TourPackage savedPkg = tourPackageRepository.save(pkg);

        // Seed 3 future departures for this package
        for (int i = 1; i <= 3; i++) {
            Departure dep = new Departure();
            dep.setTourPackage(savedPkg);
            dep.setDepartureDate(java.time.LocalDate.now().plusDays(i * 15));
            dep.setReturnDate(java.time.LocalDate.now().plusDays(i * 15 + days));
            dep.setTotalCapacity(capacity);
            dep.setAvailableSeats(capacity);
            departureRepository.save(dep);
        }
    }

    private void createHotelIfNotExists(String name, Destination destination, String address, int stars, BigDecimal price, String description) {
        if (hotelRepository.findByDestination_Id(destination.getId()).stream().anyMatch(h -> name.equalsIgnoreCase(h.getName()))) return;
        Hotel hotel = new Hotel();
        hotel.setName(name);
        hotel.setDestination(destination);
        hotel.setAddress(address);
        hotel.setStarRating(stars);
        hotel.setPricePerNight(price);
        hotel.setDescription(description);
        hotel.setActive(true);
        hotelRepository.save(hotel);
    }
}
