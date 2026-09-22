package com.travelgo.entity;

import com.travelgo.enums.ContactMessageStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "contact_messages")
public class ContactMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String phone;
    private String inquiryType;
    private java.time.LocalDate preferredDate;
    private Integer travelerCount;
    private String budgetRange;
    @ManyToOne(fetch=FetchType.LAZY) private Destination destination;
    @ManyToOne(fetch=FetchType.LAZY) private TourPackage tourPackage;
    @Column(columnDefinition="TEXT") private String internalNotes;
    @Version @Column(columnDefinition="bigint default 0") private Long version;
    @Column(unique=true,length=64) private String submissionKey;
    public String getReference(){return "INQ-"+id;}
    public String getPhone(){return phone;} public void setPhone(String v){phone=v;}
    public String getInquiryType(){return inquiryType;} public void setInquiryType(String v){inquiryType=v;}
    public java.time.LocalDate getPreferredDate(){return preferredDate;} public void setPreferredDate(java.time.LocalDate v){preferredDate=v;}
    public Integer getTravelerCount(){return travelerCount;} public void setTravelerCount(Integer v){travelerCount=v;}
    public String getBudgetRange(){return budgetRange;} public void setBudgetRange(String v){budgetRange=v;}
    public Destination getDestination(){return destination;} public void setDestination(Destination v){destination=v;}
    public TourPackage getTourPackage(){return tourPackage;} public void setTourPackage(TourPackage v){tourPackage=v;}
    public String getInternalNotes(){return internalNotes;} public void setInternalNotes(String v){internalNotes=v;}
    public Long getVersion(){return version;}
    public String getSubmissionKey(){return submissionKey;} public void setSubmissionKey(String v){submissionKey=v;}
    public String getStatusLabel(){return status.getDisplayName();}
    public String getBudgetLabel(){if(budgetRange==null||budgetRange.isBlank())return "To be discussed";return switch(budgetRange){case "UNDER_1000"->"Under $1,000";case "1000_3000"->"$1,000–$3,000";case "3000_5000"->"$3,000–$5,000";case "OVER_5000"->"Over $5,000";default->"To be discussed";};}

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String senderName;

    @Column(nullable = false)
    private String senderEmail;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContactMessageStatus status = ContactMessageStatus.OPEN;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to_id")
    private User assignedTo;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Column(columnDefinition = "TEXT")
    private String replyText;

    private LocalDateTime repliedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public String getSenderEmail() { return senderEmail; }
    public void setSenderEmail(String senderEmail) { this.senderEmail = senderEmail; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public ContactMessageStatus getStatus() { return status; }
    public void setStatus(ContactMessageStatus status) { this.status = status; }
    public User getAssignedTo() { return assignedTo; }
    public void setAssignedTo(User assignedTo) { this.assignedTo = assignedTo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getReplyText() { return replyText; }
    public void setReplyText(String replyText) { this.replyText = replyText; }
    public LocalDateTime getRepliedAt() { return repliedAt; }
    public void setRepliedAt(LocalDateTime repliedAt) { this.repliedAt = repliedAt; }
}
