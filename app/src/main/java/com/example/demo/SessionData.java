package com.example.demo;

import java.util.UUID;

public final class SessionData {
    private final String authId = UUID.randomUUID().toString();

    public String getAuthId() {
        return authId;
    }
}
