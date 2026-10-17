package com.travelgo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Each published row is an immutable agency policy version. No policy is seeded. */
@Entity
@Table(name="travel_policies")
public class TravelPolicy {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false, length=200) private String title;
    @Column(nullable=false, columnDefinition="TEXT") private String terms;
    @Column(nullable=false, columnDefinition="TEXT") private String privacy;
    @Column(nullable=false, columnDefinition="TEXT") private String cancellation;
    @Column(nullable=false) private boolean published;
    @ManyToOne(fetch=FetchType.LAZY) private User createdBy;
    @ManyToOne(fetch=FetchType.LAZY) private User publishedBy;
    @Column(nullable=false) private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
    public Long getId(){return id;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public String getTerms(){return terms;} public void setTerms(String v){terms=v;}
    public String getPrivacy(){return privacy;} public void setPrivacy(String v){privacy=v;}
    public String getCancellation(){return cancellation;} public void setCancellation(String v){cancellation=v;}
    public boolean isPublished(){return published;} public void setPublished(boolean v){published=v;}
    public User getCreatedBy(){return createdBy;} public void setCreatedBy(User v){createdBy=v;}
    public User getPublishedBy(){return publishedBy;} public void setPublishedBy(User v){publishedBy=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getPublishedAt(){return publishedAt;} public void setPublishedAt(LocalDateTime v){publishedAt=v;}
}
