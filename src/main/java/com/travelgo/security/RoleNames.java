package com.travelgo.security;

/** Compatibility for the catalogue role stored by earlier TravelGO versions. */
public final class RoleNames {
    private RoleNames() { }

    public static String canonical(String storedRole) {
        return "PACKAGE_MANAGER".equals(storedRole) ? "TRAVEL_CONSULTANT" : storedRole;
    }
}
