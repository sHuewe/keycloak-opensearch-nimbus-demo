package com.example.demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public final class Main {
    private final SessionStore sessionStore = new SessionStore();
    private final AuthManagerClient authManager;
    private final OpenSearchService openSearch;

    private Main() {
        String authManagerInternalUrl = env("AUTH_MANAGER_INTERNAL_URL", "http://auth-manager:8082");
        String authManagerPublicUrl = env("AUTH_MANAGER_PUBLIC_URL", "http://localhost:8082");
        String appPublicUrl = env("APP_PUBLIC_URL", "http://localhost:8081");
        String opensearchUrl = env("OPENSEARCH_URL", "http://opensearch:9200");

        this.authManager = new AuthManagerClient(authManagerInternalUrl, authManagerPublicUrl, appPublicUrl);
        this.openSearch = new OpenSearchService(opensearchUrl, authManager);
    }

    public static void main(String[] args) throws Exception {
        new Main().start();
    }

    private void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", wrap(new Handler() {
            @Override public void handle(HttpExchange exchange, SessionData session) throws Exception {
                home(exchange, session);
            }
        }));
        server.createContext("/login", wrap(new Handler() {
            @Override public void handle(HttpExchange exchange, SessionData session) throws Exception {
                redirect(exchange, authManager.createLoginUrl(session));
            }
        }));
        server.createContext("/search", wrap(new Handler() {
            @Override public void handle(HttpExchange exchange, SessionData session) throws Exception {
                text(exchange, 200, openSearch.search(session), "application/json; charset=utf-8");
            }
        }));
        server.createContext("/whoami", wrap(new Handler() {
            @Override public void handle(HttpExchange exchange, SessionData session) throws Exception {
                text(exchange, 200, openSearch.whoAmI(session), "application/json; charset=utf-8");
            }
        }));
        server.createContext("/refresh", wrap(new Handler() {
            @Override public void handle(HttpExchange exchange, SessionData session) throws Exception {
                authManager.forceRefresh(session);
                redirect(exchange, "/token-info");
            }
        }));
        server.createContext("/token-info", wrap(new Handler() {
            @Override public void handle(HttpExchange exchange, SessionData session) throws Exception {
                String token = authManager.getAccessToken(session);
                html(exchange, 200,
                        "<h1>Access Token</h1>" +
                        "<p>Der Token wurde vom Auth-Manager geliefert. Die App kommuniziert nicht mit Keycloak.</p>" +
                        "<pre style=\"overflow-wrap:anywhere;white-space:pre-wrap\">" + esc(token) + "</pre>" +
                        "<p><a href=\"/refresh\">Refresh im Auth-Manager auslösen</a> | <a href=\"/\">Zurück</a></p>");
            }
        }));

        server.start();
        System.out.println("Demo app listening on http://0.0.0.0:8081");
    }

    private HttpHandler wrap(final Handler handler) {
        return new HttpHandler() {
            @Override public void handle(HttpExchange exchange) throws IOException {
                SessionData session = sessionStore.getOrCreate(exchange);
                try {
                    handler.handle(exchange, session);
                } catch (AuthManagerClient.NotLoggedInException e) {
                    redirect(exchange, "/login");
                } catch (OpenSearchHttpException e) {
                    e.printStackTrace();
                    if (e.getStatusCode() == 403) {
                        html(exchange, 403,
                                "<h1>403 Forbidden</h1>" +
                                "<p>OpenSearch hat den Benutzer authentifiziert, aber dem Access Token fehlt das benötigte Leserecht.</p>" +
                                "<pre>" + esc(e.getResponseBody()) + "</pre>" +
                                "<p><a href=\"/\">Zurück</a></p>");
                    } else {
                        html(exchange, e.getStatusCode(),
                                "<h1>OpenSearch HTTP " + e.getStatusCode() + "</h1><pre>" +
                                esc(e.getResponseBody()) + "</pre><p><a href=\"/\">Zurück</a></p>");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    html(exchange, 500, "<h1>Fehler</h1><pre>" + esc(e.toString()) + "</pre><p><a href=\"/\">Zurück</a></p>");
                } finally {
                    exchange.close();
                }
            }
        };
    }

    private void home(HttpExchange exchange, SessionData session) throws Exception {
        boolean loggedIn = authManager.isAuthenticated(session);
        StringBuilder body = new StringBuilder();
        body.append("<h1>App → Auth Manager → Keycloak → OpenSearch</h1>")
                .append("<p>Die App selbst kennt weder Keycloak-Endpunkte noch Client Secret oder Refresh Token.</p>")
                .append("<p>Auth-ID dieser Demo-Session: <code>").append(esc(session.getAuthId())).append("</code></p>");

        if (!loggedIn) {
            body.append("<p><a href=\"/login\">Über Auth-Manager anmelden</a></p>");
        } else {
            body.append("<p><strong>Angemeldet.</strong></p><ul>")
                    .append("<li><a href=\"/search\">Dummy-Daten aus OpenSearch lesen</a></li>")
                    .append("<li><a href=\"/whoami\">OpenSearch authinfo anzeigen</a></li>")
                    .append("<li><a href=\"/token-info\">Access Token anzeigen</a></li>")
                    .append("<li><a href=\"/refresh\">Refresh im Auth-Manager erzwingen</a></li>")
                    .append("</ul>");
        }

        body.append("<hr><pre>")
                .append("1. App /login -> Auth Manager /login?authId=...\n")
                .append("2. Nur Auth Manager kommuniziert mit Keycloak\n")
                .append("3. Keycloak Callback geht an Auth Manager :8082/callback\n")
                .append("4. Auth Manager speichert Access + Refresh Token pro authId\n")
                .append("5. App fragt Auth Manager vor OpenSearch-Aufruf nach gültigem Access Token\n")
                .append("6. Auth Manager refresht bei Bedarf selbstständig\n")
                .append("7. App sendet Authorization: Bearer <token> an OpenSearch")
                .append("</pre>");
        html(exchange, 200, body.toString());
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
        String document = "<!doctype html><html><head><meta charset=\"utf-8\"><title>OIDC Demo</title>" +
                "<style>body{font-family:sans-serif;max-width:1000px;margin:40px auto;padding:0 20px}" +
                "pre{background:#eee;padding:12px;border-radius:6px}li{margin:8px 0}</style></head><body>" + body + "</body></html>";
        text(exchange, status, document, "text/html; charset=utf-8");
    }

    private static void text(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream output = exchange.getResponseBody();
        output.write(bytes);
        output.close();
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private interface Handler {
        void handle(HttpExchange exchange, SessionData session) throws Exception;
    }
}
