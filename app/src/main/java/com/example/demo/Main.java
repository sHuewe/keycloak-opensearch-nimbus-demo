package com.example.demo;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Executors;

public final class Main {
    private final SessionStore sessionStore = new SessionStore();
    private final NimbusOidcClient oidcClient;
    private final OpenSearchService openSearch;

    private Main() throws Exception {
        String keycloakBaseUrl = env("KEYCLOAK_BASE_URL", "http://keycloak.localhost:8080");
        String realm = env("KEYCLOAK_REALM", "demo");
        String redirectUri = env("KEYCLOAK_REDIRECT_URI", "http://localhost:8081/callback");
        String clientId = env("KEYCLOAK_CLIENT_ID", "demo-app");
        String clientSecret = env("KEYCLOAK_CLIENT_SECRET", "demo-app-secret");
        String opensearchUrl = env("OPENSEARCH_URL", "http://opensearch:9200");

        this.oidcClient = new NimbusOidcClient(
                keycloakBaseUrl, realm, redirectUri, clientId, clientSecret);
        this.openSearch = new OpenSearchService(opensearchUrl, oidcClient);
    }

    public static void main(String[] args) throws Exception {
        new Main().start();
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", wrap(new Handler() {
            @Override
            public void handle(HttpExchange exchange, SessionData session) throws Exception {
                home(exchange, session);
            }
        }));

        server.createContext("/login", wrap(new Handler() {
            @Override
            public void handle(HttpExchange exchange, SessionData session) throws Exception {
                URI login = oidcClient.createLoginUri(session);
                redirect(exchange, login.toString());
            }
        }));

        server.createContext("/callback", wrap(new Handler() {
            @Override
            public void handle(HttpExchange exchange, SessionData session) throws Exception {
                String query = exchange.getRequestURI().getRawQuery();
                if (query == null) {
                    throw new IllegalArgumentException("Missing callback query string");
                }
                oidcClient.handleCallback(query, session);
                redirect(exchange, "/");
            }
        }));

        server.createContext("/search", wrap(new Handler() {
            @Override
            public void handle(HttpExchange exchange, SessionData session) throws Exception {
                requireLogin(session);
                text(exchange, 200, openSearch.search(session), "application/json; charset=utf-8");
            }
        }));

        server.createContext("/whoami", wrap(new Handler() {
            @Override
            public void handle(HttpExchange exchange, SessionData session) throws Exception {
                requireLogin(session);
                text(exchange, 200, openSearch.whoAmI(session), "application/json; charset=utf-8");
            }
        }));

        server.createContext("/refresh", wrap(new Handler() {
            @Override
            public void handle(HttpExchange exchange, SessionData session) throws Exception {
                requireLogin(session);
                oidcClient.forceRefresh(session);
                redirect(exchange, "/token-info");
            }
        }));

        server.createContext("/token-info", wrap(new Handler() {
            @Override
            public void handle(HttpExchange exchange, SessionData session) throws Exception {
                tokenInfo(exchange, session);
            }
        }));

        server.createContext("/logout", wrap(new Handler() {
            @Override
            public void handle(HttpExchange exchange, SessionData session) throws Exception {
                session.clear();
                redirect(exchange, "/");
            }
        }));

        server.start();
        System.out.println("Java 8 Nimbus OIDC demo listening on http://0.0.0.0:8081");
    }

    private HttpHandler wrap(final Handler handler) {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                SessionData session = sessionStore.getOrCreate(exchange);
                try {
                    handler.handle(exchange, session);
                } catch (NimbusOidcClient.NotLoggedInException e) {
                    redirect(exchange, "/login");
                } catch (OpenSearchHttpException e) {
                    e.printStackTrace();
                    if (e.getStatusCode() == 403) {
                        html(exchange, 403,
                                "<h1>403 Forbidden</h1>" +
                                "<p>Keycloak hat den Benutzer authentifiziert, aber OpenSearch " +
                                "erteilt dem aktuellen Token kein Leserecht auf <code>demo-data</code>.</p>" +
                                "<p>Prüfe in <a href=\"/token-info\">/token-info</a>, ob der Claim " +
                                "<code>roles</code> noch <code>os_allow_read</code> enthält.</p>" +
                                "<pre>" + esc(e.getResponseBody()) + "</pre>" +
                                "<p><a href=\"/\">Zurück</a></p>");
                    } else {
                        html(exchange, e.getStatusCode(),
                                "<h1>OpenSearch HTTP " + e.getStatusCode() + "</h1><pre>" +
                                esc(e.getResponseBody()) + "</pre>" +
                                "<p><a href=\"/\">Zurück</a></p>");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    html(exchange, 500,
                            "<h1>Fehler</h1><pre>" + esc(e.toString()) + "</pre>" +
                            "<p><a href=\"/\">Zurück</a></p>");
                } finally {
                    exchange.close();
                }
            }
        };
    }

    private void home(HttpExchange exchange, SessionData session) throws IOException {
        boolean loggedIn = session.getTokens() != null;
        StringBuilder body = new StringBuilder();
        body.append("<h1>Keycloak → Nimbus → OpenSearch</h1>")
                .append("<p>Reines Java 8. Kein Spring Boot, kein Spring Security.</p>");

        if (!loggedIn) {
            body.append("<p><a href=\"/login\">Mit Keycloak anmelden</a></p>");
        } else {
            body.append("<p><strong>Angemeldet.</strong></p>")
                    .append("<ul>")
                    .append("<li><a href=\"/search\">Dummy-Daten aus OpenSearch lesen</a></li>")
                    .append("<li><a href=\"/whoami\">OpenSearch authinfo anzeigen</a></li>")
                    .append("<li><a href=\"/token-info\">Tokens / JWT Claims ansehen</a></li>")
                    .append("<li><a href=\"/refresh\">Refresh Token jetzt verwenden</a></li>")
                    .append("<li><a href=\"/logout\">Lokale Session löschen</a></li>")
                    .append("</ul>");
        }

        body.append("<hr><pre>")
                .append("1. /login erzeugt mit Nimbus AuthenticationRequest + state + nonce\n")
                .append("2. Keycloak redirectet /callback?code=...&state=...\n")
                .append("3. Nimbus tauscht AuthorizationCode gegen Tokens\n")
                .append("4. Nimbus validiert das ID Token gegen Keycloaks JWKS\n")
                .append("5. OpenSearch-Aufruf setzt AccessToken.toAuthorizationHeader()\n")
                .append("6. Vor Ablauf wird per RefreshTokenGrant ein neues Access Token geholt\n")
                .append("7. Keycloak-Rolle os_allow_read wird in OpenSearch auf demo_reader gemappt\n")
                .append("8. demo_reader darf ausschließlich demo-data lesen")
                .append("</pre>");

        html(exchange, 200, body.toString());
    }

    private void tokenInfo(HttpExchange exchange, SessionData session) throws Exception {
        TokenSet tokens = oidcClient.requireTokens(session);
        JWT accessJwt = com.nimbusds.jwt.JWTParser.parse(tokens.getAccessToken().getValue());
        JWTClaimsSet accessClaims = accessJwt.getJWTClaimsSet();
        JWTClaimsSet idClaims = tokens.getIdToken().getJWTClaimsSet();

        String body = "<h1>Token-Info</h1>" +
                "<p>Access Token läuft ungefähr ab: <strong>" +
                esc(formatDate(tokens.getAccessTokenExpiresAtMillis())) + "</strong></p>" +
                "<h2>Access Token Claims</h2><pre>" + esc(accessClaims.toJSONObject().toString()) + "</pre>" +
                "<h2>ID Token Claims</h2><pre>" + esc(idClaims.toJSONObject().toString()) + "</pre>" +
                "<h2>Access Token (raw)</h2><pre style=\"overflow-wrap:anywhere;white-space:pre-wrap\">" +
                esc(tokens.getAccessToken().getValue()) + "</pre>" +
                "<h2>Refresh Token (raw, nur Demo!)</h2><pre style=\"overflow-wrap:anywhere;white-space:pre-wrap\">" +
                esc(tokens.getRefreshToken() == null ? "<none>" : tokens.getRefreshToken().getValue()) + "</pre>" +
                "<p><a href=\"/refresh\">Refresh auslösen</a> | <a href=\"/\">Zurück</a></p>";
        html(exchange, 200, body);
    }

    private static void requireLogin(SessionData session) {
        if (session.getTokens() == null) {
            throw new NimbusOidcClient.NotLoggedInException();
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void html(HttpExchange exchange, int status, String body) throws IOException {
        String document = "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<title>Nimbus OIDC Demo</title>" +
                "<style>body{font-family:sans-serif;max-width:1000px;margin:40px auto;padding:0 20px}" +
                "pre{background:#eee;padding:12px;border-radius:6px}li{margin:8px 0}</style>" +
                "</head><body>" + body + "</body></html>";
        text(exchange, status, document, "text/html; charset=utf-8");
    }

    private static void text(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream output = exchange.getResponseBody();
        output.write(bytes);
        output.close();
    }

    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String formatDate(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }

    private interface Handler {
        void handle(HttpExchange exchange, SessionData session) throws Exception;
    }
}
