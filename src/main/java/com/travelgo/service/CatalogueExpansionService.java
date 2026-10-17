package com.travelgo.service;
import com.travelgo.entity.*;
import com.travelgo.repository.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class CatalogueExpansionService {
    private final DestinationRepository destinations;private final TourPackageRepository packages;private final PackageCategoryRepository categories; private final DepartureRepository departures;
    public CatalogueExpansionService(DestinationRepository destinations,TourPackageRepository packages,PackageCategoryRepository categories, DepartureRepository departures){this.destinations=destinations;this.packages=packages;this.categories=categories;this.departures=departures;}
 @Transactional public List<TourPackage> install(){
  var category=categories.findAll().stream().filter(c->"City & Culture".equals(c.getName())).findFirst().orElseGet(()->{var c=new PackageCategory();c.setName("City & Culture");return categories.save(c);});
  List<TourPackage> result=new ArrayList<>();
  result.add(add(category,"United Kingdom","London","London Heritage & Thames Discovery",5,"1190.00","Follow the Thames through royal landmarks, riverside neighbourhoods and world-class collections.",
   "Day 1: Arrival orientation and a relaxed South Bank walk.\nDay 2: Westminster neighbourhood and a guided historic-city walk.\nDay 3: Tower Bridge riverside route and a Thames sightseeing cruise.\nDay 4: Museum visit and independent time in Covent Garden.\nDay 5: Free morning and departure.",
   "Guided city walks, one Thames sightseeing cruise, museum orientation and trip-planning support.","Comfortable with several hours of walking. Suitable for first-time city visitors; discuss step-free routing before booking.","london"));
  result.add(add(category,"Singapore","Singapore","Singapore Gardens & Neighbourhoods",4,"790.00","Balance the Marina Bay waterfront with garden walks, local food districts and time to explore at your own pace.",
   "Day 1: Arrival orientation and Marina Bay promenade.\nDay 2: Gardens by the Bay outdoor gardens and a guided city walk.\nDay 3: Chinatown and Kampong Glam neighbourhood visits with independent dining time.\nDay 4: Leisure morning and departure.",
   "Guided neighbourhood visits, outdoor garden orientation and local transport planning.","Suitable for couples and families comfortable walking in warm weather. Indoor conservatory tickets are not included.","singapore"));
  result.add(add(category,"Türkiye","Istanbul","Istanbul Old City & Bosphorus",6,"990.00","Explore layered city history, waterfront neighbourhoods and the Bosphorus with a mix of guided walks and free time.",
   "Day 1: Arrival and neighbourhood orientation.\nDay 2: Sultanahmet historic-quarter guided walk.\nDay 3: Bazaar district and independent shopping time.\nDay 4: Bosphorus sightseeing cruise.\nDay 5: Galata and Karaköy neighbourhood walk.\nDay 6: Leisure time and departure.",
   "Guided historic-quarter and neighbourhood walks, one Bosphorus sightseeing cruise and planning support.","Best for culture-focused travelers comfortable with hills, steps and uneven streets. Discuss mobility needs in advance.","istanbul"));
  return result;
 }
 private TourPackage add(PackageCategory category,String country,String city,String name,int days,String price,String description,String itinerary,String included,String audience,String image){
  var existing=packages.findAll().stream().filter(p->name.equals(p.getName())).findFirst();if(existing.isPresent())return existing.get();
  var destination=destinations.findAll().stream().filter(d->city.equalsIgnoreCase(d.getCity())&&country.equalsIgnoreCase(d.getCountry())).findFirst().orElseGet(()->{var d=new Destination();d.setCity(city);d.setCountry(country);d.setDescription(description);d.setImage("/images/"+image+".jpg");return destinations.save(d);});
  var p=new TourPackage();p.setCategory(category);p.setDestination(destination);p.setName(name);p.setDescription(description);p.setItinerary(itinerary);p.setTravelerInformation(audience);p.setIncludedServices(included);p.setExcludedServices("International flights, accommodation, visa and documentation charges, travel insurance, meals unless specified, attraction tickets not explicitly included and personal expenses.");
  p.setFlightDetails("International flights are not included; arrange separately with your consultant.");p.setBasePrice(new BigDecimal(price));p.setDurationDays(days);p.setMaxCapacity(12);p.setImage("/images/"+image+".jpg");p.setActive(true);
  TourPackage saved = packages.save(p);
  Departure dep = new Departure();
  dep.setTourPackage(saved);
  dep.setDepartureDate(java.time.LocalDate.of(2030,2,1));
  dep.setReturnDate(java.time.LocalDate.of(2030,2,1).plusDays(saved.getDurationDays()));
  dep.setAvailableSeats(saved.getMaxCapacity());
  dep.setTotalCapacity(saved.getMaxCapacity());
  departures.save(dep);
  return saved;
 }
}
