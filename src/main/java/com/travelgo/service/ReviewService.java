package com.travelgo.service;

import com.travelgo.entity.Review;
import com.travelgo.repository.ReviewRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ReviewService {

    private final ReviewRepository repository;
    private final WorkflowRules rules;

    public ReviewService(ReviewRepository repository, WorkflowRules rules) {
        this.repository = repository;
        this.rules=rules;
    }

    public List<Review> findAll() {
        return repository.findAll();
    }

    public Optional<Review> findById(Long id) {
        return repository.findById(id);
    }

    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Review save(Review entity) {
        rules.requireRole("CUSTOMER");var booking=rules.lock(entity.getBooking().getId());rules.owner(booking);
        if(!canReview(booking))throw new IllegalArgumentException("You can review a confirmed trip after its scheduled end date, once per booking.");
        if(entity.getRating()==null||entity.getRating()<1||entity.getRating()>5||(entity.getComment()!=null&&entity.getComment().length()>3000))throw new IllegalArgumentException("Choose a rating from 1 to 5 and keep your review under 3,000 characters.");
        entity.setBooking(booking);entity.setUser(booking.getUser());entity.setTourPackage(booking.getTourPackage());
        return repository.save(entity);
    }
    public boolean canReview(com.travelgo.entity.Booking booking){return booking.getBookingStatus()==com.travelgo.enums.BookingStatus.CONFIRMED&&rules.now().toLocalDate().isAfter(booking.getTravelDate().plusDays(booking.getTourPackage().getDurationDays()-1))&&!repository.existsByBooking_Id(booking.getId());}

    public void deleteById(Long id) {
        repository.deleteById(id);
    }

    public List<Review> findByPackageId(Long packageId) {
        return repository.findByTourPackage_Id(packageId);
    }

    public List<Review> findByUserId(Long userId) {
        return repository.findByUser_Id(userId);
    }
    public List<Review> findByBookingId(Long id){return repository.findByBooking_Id(id);}
}
