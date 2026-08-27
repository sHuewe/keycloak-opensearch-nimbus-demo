package com.example.demo;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionStore {
    private static final String COOKIE_NAME = "DEMOSESSION";
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<String, SessionData>();

    public SessionData getOrCreate(HttpExchange exchange) {
        String id = readSessionId(exchange);
        if (id != null) {
            SessionData existing = sessions.get(id);
            if (existing != null) {
                return existing;
            }
        }

        String newId = UUID.randomUUID().toString();
        SessionData session = new SessionData();
        sessions.put(newId, session);
        exchange.getResponseHeaders().add(
                "Set-Cookie",
                COOKIE_NAME + "=" + newId + "; Path=/; HttpOnly; SameSite=Lax");
        return session;
    }

    private String readSessionId(HttpExchange exchange) {
        Headers headers = exchange.getRequestHeaders();
        List<String> cookieHeaders = headers.get("Cookie");
        if (cookieHeaders == null) {
            return null;
        }

        for (String header : cookieHeaders) {
            String[] cookies = header.split(";");
            for (String cookie : cookies) {
                String trimmed = cookie.trim();
                String prefix = COOKIE_NAME + "=";
                if (trimmed.startsWith(prefix)) {
                    return trimmed.substring(prefix.length());
                }
            }
        }
        return null;
    }
}
