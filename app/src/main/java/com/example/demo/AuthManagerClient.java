package com.example.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class AuthManagerClient {
    private final String internalBaseUrl;
    private final String publicBaseUrl;
    private final String appPublicUrl;

    public AuthManagerClient(String internalBaseUrl, String publicBaseUrl, String appPublicUrl) {
        this.internalBaseUrl = internalBaseUrl;
        this.publicBaseUrl = publicBaseUrl;
        this.appPublicUrl = appPublicUrl;
    }

    public String createLoginUrl(SessionData session) throws Exception {
        return publicBaseUrl + "/login?authId=" + enc(session.getAuthId())
                + "&returnUrl=" + enc(appPublicUrl + "/");
    }

    public String getAccessToken(SessionData session) throws Exception {
        Response response = get(internalBaseUrl + "/token?authId=" + enc(session.getAuthId()));
        if (response.status == 404) {
            throw new NotLoggedInException();
        }
        if (response.status >= 400) {
            throw new IOException("Auth manager returned HTTP " + response.status + ": " + response.body);
        }
        return response.body.trim();
    }

    public String forceRefresh(SessionData session) throws Exception {
        Response response = get(internalBaseUrl + "/refresh?authId=" + enc(session.getAuthId()));
        if (response.status == 404) {
            throw new NotLoggedInException();
        }
        if (response.status >= 400) {
            throw new IOException("Auth manager returned HTTP " + response.status + ": " + response.body);
        }
        return response.body.trim();
    }

    public boolean isAuthenticated(SessionData session) throws Exception {
        Response response = get(internalBaseUrl + "/status?authId=" + enc(session.getAuthId()));
        return response.status == 200 && "AUTHENTICATED".equals(response.body.trim());
    }

    private static Response get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        return new Response(status, readAll(input));
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        try {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
            return result.toString();
        } finally {
            reader.close();
        }
    }

    private static String enc(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static final class Response {
        private final int status;
        private final String body;
        private Response(int status, String body) { this.status = status; this.body = body; }
    }

    public static final class NotLoggedInException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
