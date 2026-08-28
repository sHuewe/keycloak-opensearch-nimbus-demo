package com.example.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWT;
import com.nimbusds.oauth2.sdk.*;
import com.nimbusds.oauth2.sdk.auth.ClientAuthentication;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.openid.connect.sdk.*;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public final class AuthManagerMain {
    private final Map<String, AuthSession> sessions = new ConcurrentHashMap<String, AuthSession>();
    private final Map<String, String> stateToAuthId = new ConcurrentHashMap<String, String>();

    private final URI authorizationEndpoint;
    private final URI tokenEndpoint;
    private final URI redirectUri;
    private final ClientID clientId;
    private final ClientAuthentication clientAuthentication;
    private final IDTokenValidator idTokenValidator;

    private AuthManagerMain() throws Exception {
        String publicKeycloakBase = env("KEYCLOAK_PUBLIC_BASE_URL", "http://keycloak.localhost:8080");
        String internalKeycloakBase = env("KEYCLOAK_INTERNAL_BASE_URL", "http://keycloak:8080");
        String realm = env("KEYCLOAK_REALM", "demo");
        this.redirectUri = URI.create(env("KEYCLOAK_REDIRECT_URI", "http://localhost:8082/callback"));
        this.clientId = new ClientID(env("KEYCLOAK_CLIENT_ID", "demo-app"));
        this.clientAuthentication = new ClientSecretBasic(
                clientId,
                new Secret(env("KEYCLOAK_CLIENT_SECRET", "demo-app-secret")));

        String issuer = publicKeycloakBase + "/realms/" + realm;
        this.authorizationEndpoint = URI.create(issuer + "/protocol/openid-connect/auth");
        this.tokenEndpoint = URI.create(internalKeycloakBase + "/realms/" + realm + "/protocol/openid-connect/token");
        URL jwks = new URL(internalKeycloakBase + "/realms/" + realm + "/protocol/openid-connect/certs");
        this.idTokenValidator = new IDTokenValidator(
                new Issuer(issuer), clientId, JWSAlgorithm.RS256, jwks);
    }

    public static void main(String[] args) throws Exception {
        new AuthManagerMain().start();
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8082), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/login", new SafeHandler() {
            @Override protected void doHandle(HttpExchange exchange) throws Exception { startLogin(exchange); }
        });
        server.createContext("/callback", new SafeHandler() {
            @Override protected void doHandle(HttpExchange exchange) throws Exception { handleCallback(exchange); }
        });
        server.createContext("/token", new SafeHandler() {
            @Override protected void doHandle(HttpExchange exchange) throws Exception { token(exchange, false); }
        });
        server.createContext("/refresh", new SafeHandler() {
            @Override protected void doHandle(HttpExchange exchange) throws Exception { token(exchange, true); }
        });
        server.createContext("/status", new SafeHandler() {
            @Override protected void doHandle(HttpExchange exchange) throws Exception { status(exchange); }
        });

        server.start();
        System.out.println("Auth manager listening on http://0.0.0.0:8082");
    }

    private void startLogin(HttpExchange exchange) throws Exception {
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        String authId = required(query, "authId");
        String returnUrl = query.containsKey("returnUrl") ? query.get("returnUrl") : "http://localhost:8081/";

        State state = new State();
        Nonce nonce = new Nonce();
        AuthSession session = new AuthSession(nonce, returnUrl);
        sessions.put(authId, session);
        stateToAuthId.put(state.getValue(), authId);

        AuthenticationRequest request = new AuthenticationRequest.Builder(
                new ResponseType(ResponseType.Value.CODE),
                new Scope(OIDCScopeValue.OPENID, OIDCScopeValue.PROFILE),
                clientId,
                redirectUri)
                .endpointURI(authorizationEndpoint)
                .state(state)
                .nonce(nonce)
                .build();

        redirect(exchange, request.toURI().toString());
    }

    private void handleCallback(HttpExchange exchange) throws Exception {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null) throw new IllegalArgumentException("Missing callback query");

        AuthenticationResponse response = AuthenticationResponseParser.parse(
                URI.create(redirectUri.toString() + "?" + rawQuery));
        if (response.getState() == null) throw new SecurityException("Missing state");

        String authId = stateToAuthId.remove(response.getState().getValue());
        if (authId == null) throw new SecurityException("Unknown or already used state");
        AuthSession session = sessions.get(authId);
        if (session == null) throw new SecurityException("Unknown auth session");

        if (response instanceof AuthenticationErrorResponse) {
            throw new SecurityException("Keycloak login failed: " + ((AuthenticationErrorResponse) response).getErrorObject());
        }

        AuthorizationCode code = ((AuthenticationSuccessResponse) response).getAuthorizationCode();
        TokenSet tokens = exchangeCode(code, session.nonce);
        session.tokens = tokens;
        redirect(exchange, session.returnUrl);
    }

    private TokenSet exchangeCode(AuthorizationCode code, Nonce expectedNonce) throws Exception {
        TokenRequest request = new TokenRequest(
                tokenEndpoint,
                clientAuthentication,
                new AuthorizationCodeGrant(code, redirectUri));
        TokenResponse response = OIDCTokenResponseParser.parse(request.toHTTPRequest().send());
        if (!response.indicatesSuccess()) {
            throw new SecurityException("Token endpoint rejected code: " + response.toErrorResponse().getErrorObject());
        }
        OIDCTokenResponse success = (OIDCTokenResponse) response.toSuccessResponse();
        JWT idToken = success.getOIDCTokens().getIDToken();
        idTokenValidator.validate(idToken, expectedNonce);
        return tokenSet(success.getOIDCTokens().getAccessToken(), success.getOIDCTokens().getRefreshToken(), idToken);
    }

    private void token(HttpExchange exchange, boolean forceRefresh) throws Exception {
        String authId = required(query(exchange.getRequestURI().getRawQuery()), "authId");
        AuthSession session = sessions.get(authId);
        if (session == null || session.tokens == null) {
            text(exchange, 404, "NO_TOKEN");
            return;
        }
        synchronized (session) {
            if (forceRefresh || session.tokens.expiresWithinSeconds(10)) {
                session.tokens = refresh(session.tokens);
            }
            text(exchange, 200, session.tokens.accessToken.getValue());
        }
    }

    private void status(HttpExchange exchange) throws Exception {
        String authId = required(query(exchange.getRequestURI().getRawQuery()), "authId");
        AuthSession session = sessions.get(authId);
        text(exchange, 200, session != null && session.tokens != null ? "AUTHENTICATED" : "NOT_AUTHENTICATED");
    }

    private TokenSet refresh(TokenSet current) throws Exception {
        if (current.refreshToken == null) throw new SecurityException("No refresh token available");
        TokenRequest request = new TokenRequest(
                tokenEndpoint,
                clientAuthentication,
                new RefreshTokenGrant(current.refreshToken));
        TokenResponse response = TokenResponse.parse(request.toHTTPRequest().send());
        if (!response.indicatesSuccess()) {
            throw new SecurityException("Refresh failed: " + response.toErrorResponse().getErrorObject());
        }
        AccessToken access = response.toSuccessResponse().getTokens().getAccessToken();
        RefreshToken refresh = response.toSuccessResponse().getTokens().getRefreshToken();
        if (refresh == null) refresh = current.refreshToken;
        return tokenSet(access, refresh, current.idToken);
    }

    private static TokenSet tokenSet(AccessToken access, RefreshToken refresh, JWT idToken) {
        long lifetime = access.getLifetime() > 0 ? access.getLifetime() : 30;
        return new TokenSet(access, refresh, idToken, System.currentTimeMillis() + lifetime * 1000L);
    }

    private abstract static class SafeHandler implements HttpHandler {
        @Override public final void handle(HttpExchange exchange) throws IOException {
            try { doHandle(exchange); }
            catch (Exception e) { e.printStackTrace(); text(exchange, 500, e.toString()); }
            finally { exchange.close(); }
        }
        protected abstract void doHandle(HttpExchange exchange) throws Exception;
    }

    private static final class AuthSession {
        private final Nonce nonce;
        private final String returnUrl;
        private volatile TokenSet tokens;
        private AuthSession(Nonce nonce, String returnUrl) { this.nonce = nonce; this.returnUrl = returnUrl; }
    }

    private static final class TokenSet {
        private final AccessToken accessToken;
        private final RefreshToken refreshToken;
        private final JWT idToken;
        private final long expiresAt;
        private TokenSet(AccessToken accessToken, RefreshToken refreshToken, JWT idToken, long expiresAt) {
            this.accessToken = accessToken; this.refreshToken = refreshToken; this.idToken = idToken; this.expiresAt = expiresAt;
        }
        private boolean expiresWithinSeconds(long seconds) {
            return System.currentTimeMillis() + seconds * 1000L >= expiresAt;
        }
    }

    private static Map<String, String> query(String raw) throws Exception {
        Map<String, String> result = new ConcurrentHashMap<String, String>();
        if (raw == null || raw.isEmpty()) return result;
        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);
            String key = java.net.URLDecoder.decode(kv[0], "UTF-8");
            String value = kv.length > 1 ? java.net.URLDecoder.decode(kv[1], "UTF-8") : "";
            result.put(key, value);
        }
        return result;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void text(HttpExchange exchange, int status, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.close();
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }
}
