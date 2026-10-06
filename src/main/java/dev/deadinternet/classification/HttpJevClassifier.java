package dev.deadinternet.classification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Calls a JEV endpoint over HTTP: POST the request JSON, expect classification JSON back. Transport lives only here. */
public class HttpJevClassifier implements JevClassifier {

    private final URI endpoint;
    private final String token;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final JevResponseParser parser;
    private final HttpClient client;

    public HttpJevClassifier(URI endpoint, String token, Duration timeout, ObjectMapper mapper, JevResponseParser parser) {
        this.endpoint = endpoint;
        this.token = token == null ? "" : token;
        this.timeout = timeout;
        this.mapper = mapper;
        this.parser = parser;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @Override
    public JevClassification classify(JevClassificationRequest request) {
        String body;
        try {
            body = mapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new JevException("Could not serialize JEV request", e);
        }
        var builder = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!token.isBlank()) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new JevException("JEV request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JevException("JEV request interrupted", e);
        }
        if (response.statusCode() / 100 != 2) throw new JevException("JEV returned HTTP " + response.statusCode());
        return parser.parse(response.body());
    }
}
