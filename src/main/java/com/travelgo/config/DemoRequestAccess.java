package com.travelgo.config;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;

/**
 * Demo accounts are a local preview aid. Do not trust forwarded headers here:
 * a reverse proxy must never turn a remote request into a local demo login.
 */
public final class DemoRequestAccess {
    private DemoRequestAccess() { }

    public static boolean isLocal(HttpServletRequest request) {
        if (request == null || request.getRemoteAddr() == null || request.getRemoteAddr().isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
