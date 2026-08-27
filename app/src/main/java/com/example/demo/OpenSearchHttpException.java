package com.example.demo;

import java.io.IOException;

public final class OpenSearchHttpException extends IOException {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String responseBody;

    public OpenSearchHttpException(int statusCode, String responseBody) {
        super("OpenSearch returned HTTP " + statusCode + ": " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
