package com.example.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class OpenSearchService {
    private final String baseUrl;
    private final AuthManagerClient authManager;

    public OpenSearchService(String baseUrl, AuthManagerClient authManager) {
        this.baseUrl = baseUrl;
        this.authManager = authManager;
    }

    public String search(SessionData session) throws Exception {
        String body = "{\"query\":{\"match_all\":{}}}";
        return call(session, "POST", "/demo-data/_search?pretty=true", body);
    }

    public String whoAmI(SessionData session) throws Exception {
        return call(session, "GET", "/_plugins/_security/authinfo?pretty=true", null);
    }

    private String call(SessionData session,
                        String method,
                        String path,
                        String body) throws Exception {
        String accessToken = authManager.getAccessToken(session);

        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);

        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(bytes);
            } finally {
                output.close();
            }
        }

        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = readAll(input);
        if (status >= 400) {
            throw new OpenSearchHttpException(status, response);
        }
        return response;
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
}
