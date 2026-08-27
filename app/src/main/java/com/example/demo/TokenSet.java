package com.example.demo;

import com.nimbusds.jwt.JWT;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.RefreshToken;

public final class TokenSet {
    private final AccessToken accessToken;
    private final RefreshToken refreshToken;
    private final JWT idToken;
    private final long accessTokenExpiresAtMillis;

    public TokenSet(AccessToken accessToken,
                    RefreshToken refreshToken,
                    JWT idToken,
                    long accessTokenExpiresAtMillis) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.idToken = idToken;
        this.accessTokenExpiresAtMillis = accessTokenExpiresAtMillis;
    }

    public AccessToken getAccessToken() {
        return accessToken;
    }

    public RefreshToken getRefreshToken() {
        return refreshToken;
    }

    public JWT getIdToken() {
        return idToken;
    }

    public long getAccessTokenExpiresAtMillis() {
        return accessTokenExpiresAtMillis;
    }

    public boolean expiresWithinSeconds(long seconds) {
        return System.currentTimeMillis() + seconds * 1000L >= accessTokenExpiresAtMillis;
    }
}
