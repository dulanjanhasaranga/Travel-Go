package com.travelgo.enums;

public enum ContactMessageStatus {
    OPEN, IN_PROGRESS, CONTACTED, RESOLVED, CLOSED;
    public String getDisplayName(){return switch(this){case OPEN->"New";case IN_PROGRESS->"In review";case CONTACTED->"Contacted";case RESOLVED->"Resolved";case CLOSED->"Closed";};}
}
