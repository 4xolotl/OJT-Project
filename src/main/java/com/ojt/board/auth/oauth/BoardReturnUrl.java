package com.ojt.board.auth.oauth;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Reconstruct a local board destination; never redirect to arbitrary input. */
public final class BoardReturnUrl {
    private static final Set<String> PATHS = Set.of("/", "/index.html", "/post.html", "/write.html", "/edit.html");

    private BoardReturnUrl() { }

    public static String sanitize(String input) {
        if (input == null || input.length() > 2048 || !input.startsWith("/") || input.startsWith("//")) return "/";
        try {
            if (unsafe(input) || unsafe(URLDecoder.decode(input, StandardCharsets.UTF_8))) return "/";
            URI uri = URI.create(input);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || !PATHS.contains(uri.getRawPath())) return "/";
            Map<String, String> source = query(uri.getRawQuery());
            Map<String, String> params = new LinkedHashMap<>();
            String path = uri.getRawPath();
            if (path.equals("/post.html") || path.equals("/edit.html")) {
                String id = source.getOrDefault("id", "");
                if (!id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) <= 0) return "/";
                params.put("id", id);
            }
            String keyword = source.getOrDefault("keyword", "").strip();
            if (!keyword.isEmpty()) params.put("keyword", keyword.substring(0, Math.min(100, keyword.length())));
            String page = source.getOrDefault("page", "");
            if (page.matches("[0-9]{1,10}")) {
                long number = Long.parseLong(page);
                if (number > 0 && number <= Integer.MAX_VALUE) params.put("page", Long.toString(number));
            }
            String size = source.getOrDefault("size", "");
            if (size.equals("10") || size.equals("50")) params.put("size", size);
            if (path.equals("/index.html")) path = "/";
            String encoded = params.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + encode(entry.getValue()))
                    .collect(Collectors.joining("&"));
            String fragment = path.equals("/post.html") && "comments".equals(uri.getRawFragment()) ? "#comments" : "";
            return path + (encoded.isEmpty() ? "" : "?" + encoded) + fragment;
        } catch (IllegalArgumentException exception) {
            return "/";
        }
    }

    public static String loginFailure(String errorCode, String returnTo) {
        return "/login.html?oauthError=" + encode(errorCode) + "&returnTo=" + encode(sanitize(returnTo));
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> values = new LinkedHashMap<>();
        if (rawQuery == null) return values;
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            values.putIfAbsent(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private static boolean unsafe(String value) {
        return value.chars().anyMatch(character -> character == '\\' || Character.isISOControl(character));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
