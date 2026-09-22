package com.travelgo.controller;

import com.travelgo.entity.Booking;
import com.travelgo.entity.Review;
import com.travelgo.entity.User;
import com.travelgo.service.BookingService;
import com.travelgo.service.ReviewService;
import com.travelgo.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller for Customer Review submission.
 */
@Controller
@RequestMapping("/customer/reviews")
public class CustomerReviewController {

    private final ReviewService reviewService;
    private final BookingService bookingService;
    private final UserService userService;

    public CustomerReviewController(ReviewService reviewService,
                                   BookingService bookingService,
                                   UserService userService) {
        this.reviewService = reviewService;
        this.bookingService = bookingService;
        this.userService = userService;
    }

    private User resolveUser(UserDetails userDetails) {
        if (userDetails == null) return null;
        return userService.getUserByEmail(userDetails.getUsername()).orElse(null);
    }

    @PostMapping
    public String submitReview(@RequestParam("bookingId") Long bookingId,
                              @RequestParam("rating") Integer rating,
                              @RequestParam(value = "comment", required = false) String comment,
                              @AuthenticationPrincipal UserDetails userDetails,
                              RedirectAttributes redirectAttributes) {
        User user = resolveUser(userDetails);
        if (user == null) return "redirect:/auth/login";

        try {
            Booking booking = bookingService.findById(bookingId)
                    .orElseThrow(() -> new IllegalArgumentException("Booking not found"));

            // Ownership check
            if (!booking.getUser().getId().equals(user.getId())) {
                redirectAttributes.addFlashAttribute("errorMessage", "Access denied.");
                return "redirect:/customer/bookings";
            }

            if (rating < 1 || rating > 5) {
                redirectAttributes.addFlashAttribute("errorMessage", "Rating must be between 1 and 5.");
                return "redirect:/customer/bookings/" + bookingId;
            }

            if (!reviewService.canReview(booking)) {
                redirectAttributes.addFlashAttribute("errorMessage", "You can only review confirmed, completed trips once.");
                return "redirect:/customer/bookings/" + bookingId;
            }

            Review review = new Review();
            review.setUser(user);
            review.setBooking(booking);
            review.setTourPackage(booking.getTourPackage());
            review.setRating(rating);
            review.setComment(comment);
            reviewService.save(review);

            redirectAttributes.addFlashAttribute("successMessage", "Review submitted successfully!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Failed to submit review: " + e.getMessage());
        }
        return "redirect:/customer/bookings/" + bookingId;
    }
}
