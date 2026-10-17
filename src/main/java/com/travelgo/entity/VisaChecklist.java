package com.travelgo.entity;

import com.travelgo.enums.VisaType;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Published versions remain immutable so an application keeps the requirements it was given. */
@Entity
@Table(name = "visa_checklists", uniqueConstraints = @UniqueConstraint(columnNames = {"destination_id", "visa_type", "revision"}))
public class VisaChecklist {
    public enum Status { DRAFT, PUBLISHED, RETIRED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version private Long lockVersion;
    @Column(nullable=false,columnDefinition="bigint default 0") private long editRevision;
    public void recordEdit(){editRevision++;}
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "destination_id", nullable = false) private Destination destination;
    @Enumerated(EnumType.STRING) @Column(name = "visa_type", nullable = false) private VisaType visaType;
    @Column(nullable = false) private int revision;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.DRAFT;
    @Column(length = 1000) private String sourceUrl;
    private LocalDate sourceReviewedOn;
    private Integer minimumLeadDays;
    private Integer estimatedProcessingMinDays;
    private Integer estimatedProcessingMaxDays;
    @Column(length = 2000) private String guidance;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "created_by_id", nullable = false) private User createdBy;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "published_by_id") private User publishedBy;
    @Column(nullable = false) private LocalDateTime createdAt;
    private LocalDateTime publishedAt;
    @OneToMany(mappedBy = "checklist", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC") private List<VisaChecklistItem> items = new ArrayList<>();
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getLockVersion() { return lockVersion; }
    public Destination getDestination() { return destination; }
    public void setDestination(Destination value) { destination = value; }
    public VisaType getVisaType() { return visaType; }
    public void setVisaType(VisaType value) { visaType = value; }
    public int getRevision() { return revision; }
    public void setRevision(int value) { revision = value; }
    public Status getStatus() { return status; }
    public void setStatus(Status value) { status = value; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String value) { sourceUrl = value; }
    public LocalDate getSourceReviewedOn() { return sourceReviewedOn; }
    public void setSourceReviewedOn(LocalDate value) { sourceReviewedOn = value; }
    public Integer getMinimumLeadDays() { return minimumLeadDays; }
    public void setMinimumLeadDays(Integer value) { minimumLeadDays = value; }
    public Integer getEstimatedProcessingMinDays() { return estimatedProcessingMinDays; }
    public void setEstimatedProcessingMinDays(Integer value) { estimatedProcessingMinDays = value; }
    public Integer getEstimatedProcessingMaxDays() { return estimatedProcessingMaxDays; }
    public void setEstimatedProcessingMaxDays(Integer value) { estimatedProcessingMaxDays = value; }
    public String getGuidance() { return guidance; }
    public void setGuidance(String value) { guidance = value; }
    public User getCreatedBy() { return createdBy; }
    public void setCreatedBy(User value) { createdBy = value; }
    public User getPublishedBy() { return publishedBy; }
    public void setPublishedBy(User value) { publishedBy = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime value) { publishedAt = value; }
    public List<VisaChecklistItem> getItems() { return items; }
}
