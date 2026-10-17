package com.travelgo.entity;

import jakarta.persistence.*;

/** Schema mapping only. Admission is an atomic JDBC transaction; rows contain opaque counters. */
@Entity
@Table(name="authentication_rate_cells", indexes=@Index(name="idx_auth_rate_expiry",columnList="expires_at_ms"))
public class AuthenticationRateCell {
    @Id @Column(name="opaque_key",length=68,nullable=false) private String key;
    @Column(name="admitted_count",nullable=false) private int count;
    @Column(name="expires_at_ms",nullable=false) private long expires;
    protected AuthenticationRateCell() { }
}
