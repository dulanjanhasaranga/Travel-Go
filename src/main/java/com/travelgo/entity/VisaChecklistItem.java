package com.travelgo.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "visa_checklist_items")
public class VisaChecklistItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "checklist_id", nullable = false) private VisaChecklist checklist;
    @Column(nullable = false, length = 180) private String label;
    @Column(nullable = false) private boolean required;
    @Column(name = "item_position", nullable = false) private int position;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public VisaChecklist getChecklist() { return checklist; }
    public void setChecklist(VisaChecklist value) { checklist = value; }
    public String getLabel() { return label; }
    public void setLabel(String value) { label = value; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean value) { required = value; }
    public int getPosition() { return position; }
    public void setPosition(int value) { position = value; }
}
