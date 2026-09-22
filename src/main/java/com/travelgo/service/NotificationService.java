package com.travelgo.service;

import com.travelgo.entity.Notification;
import com.travelgo.entity.User;
import com.travelgo.repository.NotificationRepository;
import com.travelgo.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Persistent inbox. Read mutations always resolve the account from the authenticated session. */
@Service
@Transactional(readOnly = true)
public class NotificationService {
    public static final Set<String> CATEGORIES = Set.of("all", "booking", "visa", "payment", "support", "package");
    private final NotificationRepository repository;
    private final UserRepository users;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, UserRepository users, Clock clock) {
        this.repository = repository;
        this.users = users;
        this.clock = clock;
    }

    public record Item(Long id, String type, String title, String message, boolean read,
                       LocalDateTime createdAt, LocalDateTime readAt, String url) {}
    public record Summary(long unreadCount, List<Item> items) {}
    public record ReadResult(long unreadCount, String url) {}

    public User currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) throw new AccessDeniedException("Sign in required.");
        return users.findByEmail(authentication.getName()).filter(User::isActive)
            .orElseThrow(() -> new AccessDeniedException("An active account is required."));
    }

    public Summary summary() {
        User account = currentUser();
        return new Summary(repository.countByUser_IdAndIsReadFalse(account.getId()),
            repository.findTop5ByUser_IdOrderByCreatedAtDescIdDesc(account.getId()).stream().map(n -> item(n, account)).toList());
    }

    public Page<Item> inbox(String category, boolean unread, int page) {
        if (!CATEGORIES.contains(category)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid notification category.");
        if (page < 0 || page > 100000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a valid page.");
        User account = currentUser();
        Specification<Notification> specification = (root, query, cb) -> cb.equal(root.get("user").get("id"), account.getId());
        if (unread) specification = specification.and((root, query, cb) -> cb.isFalse(root.get("isRead")));
        if (!category.equals("all")) {
            String prefix = category.toUpperCase(Locale.ROOT) + "\\_%";
            specification = specification.and((root, query, cb) -> cb.like(root.get("type"), prefix, '\\'));
        }
        return repository.findAll(specification, PageRequest.of(page, 12, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
            .map(n -> item(n, account));
    }

    @Transactional
    public ReadResult markRead(Long id) {
        User account = currentUser();
        Notification notification = repository.findByIdAndUser_Id(id, account.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String url = destination(notification, account);
        repository.markRead(id, account.getId(), LocalDateTime.now(clock));
        return new ReadResult(repository.countByUser_IdAndIsReadFalse(account.getId()), url);
    }

    @Transactional
    public long markAllRead() {
        User account = currentUser();
        repository.markAllRead(account.getId(), LocalDateTime.now(clock));
        return repository.countByUser_IdAndIsReadFalse(account.getId());
    }

    /** Called from workflow transactions only after an actual state change. Retries should return before publishing. */
    @Transactional
    public Notification publish(User recipient, String type, String title, String message,
                                String relatedEntityType, Long relatedEntityId) {
        if (recipient == null || recipient.getId() == null || type == null || !type.matches("[A-Z][A-Z_]{0,59}")
            || title == null || title.isBlank() || title.length() > 255 || message == null || message.isBlank())
            throw new IllegalArgumentException("A recipient, notification type, title and message are required.");
        if (relatedEntityType != null && !Set.of("BOOKING", "VISA", "PAYMENT", "INQUIRY", "SUPPORT", "PACKAGE").contains(relatedEntityType))
            throw new IllegalArgumentException("Unsupported notification link type.");
        Notification notification = new Notification();
        notification.setUser(recipient);
        notification.setType(type);
        notification.setTitle(title);
        notification.setMessage(message);
        notification.setRelatedEntityType(relatedEntityType);
        notification.setRelatedEntityId(relatedEntityId);
        notification.setCreatedAt(LocalDateTime.now(clock));
        return repository.save(notification);
    }

    private Item item(Notification notification, User account) {
        return new Item(notification.getId(), notification.getType() == null ? "GENERAL" : notification.getType(),
            notification.getTitle(), notification.getMessage(), notification.isRead(), notification.getCreatedAt(),
            notification.getReadAt(), destination(notification, account));
    }

    /** URLs are derived from a small local route allowlist, never stored or accepted from a browser. */
    private String destination(Notification notification, User account) {
        String role = account.getRole().getRoleName();
        String type = notification.getRelatedEntityType();
        Long id = notification.getRelatedEntityId();
        if (type == null || id == null || id <= 0) return "/notifications";
        return switch (type) {
            case "BOOKING" -> role.equals("CUSTOMER") ? "/customer/bookings/" + id
                : (role.equals("TRAVEL_CONSULTANT") || role.equals("PACKAGE_MANAGER")) ? "/staff/bookings"
                : role.equals("ADMIN") ? "/admin/dashboard" : "/notifications";
            case "VISA" -> role.equals("CUSTOMER") ? "/customer/visas" : role.equals("VISA_OFFICER") ? "/staff/visas" : "/notifications";
            case "PAYMENT" -> role.equals("CUSTOMER") ? "/customer/bookings" : role.equals("VISA_OFFICER") ? "/staff/payments" : "/notifications";
            case "INQUIRY", "SUPPORT" -> role.equals("CUSTOMER") ? "/customer/inquiries" : role.equals("ADMIN") ? "/admin/inquiries/" + id
                : (role.equals("TRAVEL_CONSULTANT") || role.equals("PACKAGE_MANAGER")) ? "/staff/inquiries/" + id : "/notifications";
            case "PACKAGE" -> "/packages/" + id;
            default -> "/notifications";
        };
    }
}
