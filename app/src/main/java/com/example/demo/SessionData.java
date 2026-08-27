package com.example.demo;

import java.util.UUID;

public final class SessionData {
    private final String authId;

    public SessionData() {
        // The demo app owns the authId. The auth manager only receives this
        // opaque identifier and stores the corresponding tokens under it.
        this.authId = UUID.randomUUID().toString();
    }

    public String getAuthId() {
        return authId;
    }
}
