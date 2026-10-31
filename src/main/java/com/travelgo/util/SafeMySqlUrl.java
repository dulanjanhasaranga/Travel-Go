package com.travelgo.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Connection validation shared by production startup and exceptional offline operations. */
public final class SafeMySqlUrl {
    private SafeMySqlUrl() {}

    public static void validate(String url) {
        URI uri;
        Map<String, String> parameters = new HashMap<>();
        try {
            if (url == null || !url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            uri = URI.create(url.substring(5));
            if (!"mysql".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || uri.getPath() == null || !uri.getPath().matches("/[A-Za-z0-9_]+")
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)))
                throw new IllegalArgumentException();
            if (uri.getRawQuery() != null) for (String parameter : uri.getRawQuery().split("&", -1)) {
                String[] parts = parameter.split("=", 2);
                String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                if (key.isBlank() || key.chars().anyMatch(Character::isISOControl)
                        || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
                if (key.matches(".*password.*|.*username.*|user|passwd") || parameters.putIfAbsent(key, value) != null)
                    throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            // URI and decoding exceptions can contain the original credential-bearing URL.
            throw new IllegalArgumentException("Use one MySQL server and schema without embedded credentials or duplicate JDBC parameters.");
        }
        if (!loopback(uri.getHost()) && !"VERIFY_IDENTITY".equalsIgnoreCase(parameters.get("sslmode")))
            throw new IllegalArgumentException("Remote MySQL requires sslMode=VERIFY_IDENTITY and an approved certificate trust setup.");
        String localFiles = parameters.get("allowloadlocalinfile");
        if (localFiles != null && !"false".equalsIgnoreCase(localFiles) && !"no".equalsIgnoreCase(localFiles))
            throw new IllegalArgumentException("Disable local-file loading for the application database connection.");
    }

    private static boolean loopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    }
}
