package com.example.demo;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWT;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.oauth2.sdk.RefreshTokenGrant;
import com.nimbusds.openid.connect.sdk.AuthenticationErrorResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.AuthenticationResponse;
import com.nimbusds.openid.connect.sdk.AuthenticationResponseParser;
import com.nimbusds.openid.connect.sdk.AuthenticationSuccessResponse;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCScopeValue;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;

import java.net.URI;
import java.net.URL;

public final class NimbusOidcClient {
    private final URI authorizationEndpoint;
    private final URI tokenEndpoint;
    private final URI redirectUri;
    private final Issuer issuer;
    private final URL jwksUri;
    private final ClientID clientId;
    private final ClientAuthentication clientAuthentication;
    private final IDTokenValidator idTokenValidator;

    public NimbusOidcClient(String keycloakBaseUrl,
                           String realm,
                           String redirectUri,
                           String clientId,
                           String clientSecret) throws Exception {
        String issuerString = keycloakBaseUrl + "/realms/" + realm;
        this.authorizationEndpoint = URI.create(issuerString + "/protocol/openid-connect/auth");
        this.tokenEndpoint = URI.create(issuerString + "/protocol/openid-connect/token");
        this.redirectUri = URI.create(redirectUri);
        this.issuer = new Issuer(issuerString);
        this.jwksUri = new URL(issuerString + "/protocol/openid-connect/certs");
        this.clientId = new ClientID(clientId);
        this.clientAuthentication = new ClientSecretBasic(
                this.clientId,
                new Secret(clientSecret));

        // Keycloak signs ID tokens with RS256 by default in this demo realm.
        // Nimbus verifies signature, iss, aud/client_id, timestamps and nonce.
        this.idTokenValidator = new IDTokenValidator(
                this.issuer,
                this.clientId,
                JWSAlgorithm.RS256,
                this.jwksUri);
    }

    public URI createLoginUri(SessionData session) {
        State state = new State();
        Nonce nonce = new Nonce();
        session.setExpectedState(state);
        session.setExpectedNonce(nonce);

        AuthenticationRequest request = new AuthenticationRequest.Builder(
                new ResponseType(ResponseType.Value.CODE),
                new Scope(OIDCScopeValue.OPENID, OIDCScopeValue.PROFILE),
                clientId,
                redirectUri)
                .endpointURI(authorizationEndpoint)
                .state(state)
                .nonce(nonce)
                .build();

        return request.toURI();
    }

    public TokenSet handleCallback(String rawQuery, SessionData session) throws Exception {
        if (session.getExpectedState() == null || session.getExpectedNonce() == null) {
            throw new SecurityException("No matching login attempt in this session");
        }

        URI callbackUri = URI.create(redirectUri.toString() + "?" + rawQuery);
        AuthenticationResponse response = AuthenticationResponseParser.parse(callbackUri);

        if (!session.getExpectedState().equals(response.getState())) {
            throw new SecurityException("OIDC state mismatch");
        }

        if (response instanceof AuthenticationErrorResponse) {
            AuthenticationErrorResponse error = (AuthenticationErrorResponse) response;
            throw new SecurityException("Keycloak login failed: " + error.getErrorObject());
        }

        AuthenticationSuccessResponse success = (AuthenticationSuccessResponse) response;
        AuthorizationCode code = success.getAuthorizationCode();
        TokenSet tokens = exchangeCode(code, session.getExpectedNonce());
        session.clearLoginAttempt();
        session.setTokens(tokens);
        return tokens;
    }

    private TokenSet exchangeCode(AuthorizationCode code, Nonce expectedNonce) throws Exception {
        AuthorizationGrant grant = new AuthorizationCodeGrant(code, redirectUri);
        TokenRequest request = new TokenRequest(tokenEndpoint, clientAuthentication, grant);

        TokenResponse response = OIDCTokenResponseParser.parse(
                request.toHTTPRequest().send());

        if (!response.indicatesSuccess()) {
            throw new SecurityException(
                    "Token endpoint rejected authorization code: " +
                    response.toErrorResponse().getErrorObject());
        }

        OIDCTokenResponse oidcResponse = (OIDCTokenResponse) response.toSuccessResponse();
        JWT idToken = oidcResponse.getOIDCTokens().getIDToken();
        AccessToken accessToken = oidcResponse.getOIDCTokens().getAccessToken();
        RefreshToken refreshToken = oidcResponse.getOIDCTokens().getRefreshToken();

        // This is intentionally explicit so the demo shows where trust is established.
        idTokenValidator.validate(idToken, expectedNonce);

        return toTokenSet(accessToken, refreshToken, idToken);
    }

    public synchronized TokenSet ensureValidAccessToken(SessionData session) throws Exception {
        TokenSet current = requireTokens(session);
        if (!current.expiresWithinSeconds(10)) {
            return current;
        }

        TokenSet refreshed = refresh(current);
        session.setTokens(refreshed);
        return refreshed;
    }

    public synchronized TokenSet forceRefresh(SessionData session) throws Exception {
        TokenSet refreshed = refresh(requireTokens(session));
        session.setTokens(refreshed);
        return refreshed;
    }

    private TokenSet refresh(TokenSet current) throws Exception {
        if (current.getRefreshToken() == null) {
            throw new SecurityException("No refresh token available; user must log in again");
        }

        AuthorizationGrant grant = new RefreshTokenGrant(current.getRefreshToken());
        TokenRequest request = new TokenRequest(tokenEndpoint, clientAuthentication, grant);
        TokenResponse response = TokenResponse.parse(request.toHTTPRequest().send());

        if (!response.indicatesSuccess()) {
            throw new SecurityException(
                    "Refresh failed: " + response.toErrorResponse().getErrorObject());
        }

        AccessToken newAccessToken = response.toSuccessResponse().getTokens().getAccessToken();
        RefreshToken newRefreshToken = response.toSuccessResponse().getTokens().getRefreshToken();

        if (newRefreshToken == null) {
            newRefreshToken = current.getRefreshToken();
        }

        // A refresh response need not contain a new ID token. Keep the validated login ID token.
        return toTokenSet(newAccessToken, newRefreshToken, current.getIdToken());
    }

    public TokenSet requireTokens(SessionData session) {
        TokenSet tokens = session.getTokens();
        if (tokens == null) {
            throw new NotLoggedInException();
        }
        return tokens;
    }

    private TokenSet toTokenSet(AccessToken accessToken,
                                RefreshToken refreshToken,
                                JWT idToken) {
        long lifetimeSeconds = accessToken.getLifetime();
        if (lifetimeSeconds <= 0) {
            // Keycloak normally supplies expires_in. Fallback keeps this demo conservative.
            lifetimeSeconds = 30;
        }
        long expiresAt = System.currentTimeMillis() + lifetimeSeconds * 1000L;
        return new TokenSet(accessToken, refreshToken, idToken, expiresAt);
    }

    public static final class NotLoggedInException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
