package com.example.demo;

import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.openid.connect.sdk.Nonce;

public final class SessionData {
    private volatile State expectedState;
    private volatile Nonce expectedNonce;
    private volatile TokenSet tokens;

    public State getExpectedState() {
        return expectedState;
    }

    public void setExpectedState(State expectedState) {
        this.expectedState = expectedState;
    }

    public Nonce getExpectedNonce() {
        return expectedNonce;
    }

    public void setExpectedNonce(Nonce expectedNonce) {
        this.expectedNonce = expectedNonce;
    }

    public TokenSet getTokens() {
        return tokens;
    }

    public void setTokens(TokenSet tokens) {
        this.tokens = tokens;
    }

    public void clearLoginAttempt() {
        this.expectedState = null;
        this.expectedNonce = null;
    }

    public void clear() {
        clearLoginAttempt();
        this.tokens = null;
    }
}
