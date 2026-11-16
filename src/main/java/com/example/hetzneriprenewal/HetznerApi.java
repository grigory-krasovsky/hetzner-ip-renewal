package com.example.hetzneriprenewal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/* Claude: Клиент Hetzner Cloud API. Каждый запрос идёт по новому соединению: российские провайдеры замораживают
   соединение с Hetzner, как только по нему прошло около 16 КБ, а ответы API по отдельности в несколько раз меньше.
   Поэтому HttpClient создаётся на каждый запрос — отключить в нём повторное использование соединений нельзя. */
@Component
public class HetznerApi {

    private static final Logger log = LoggerFactory.getLogger(HetznerApi.class);

    private static final String BASE_URL = "https://api.hetzner.cloud/v1/";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int ATTEMPTS = 3;
    private static final Duration RETRY_PAUSE = Duration.ofSeconds(5);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String token;

    public HetznerApi(HetznerProperties properties) {
        this.token = properties.token();
    }

    public JsonNode get(String path) {
        return send(request(path).GET().build(), true);
    }

    // Claude: POST не повторяется: если ответ потерялся, действие могло выполниться — повтор решает вызывающий код по состоянию
    public JsonNode post(String path, Object body) {
        HttpRequest.Builder request = request(path);
        if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(toJson(body)));
        }
        return send(request.build(), false);
    }

    public void delete(String path) {
        send(request(path).DELETE().build(), true);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(BASE_URL + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token);
    }

    private JsonNode send(HttpRequest request, boolean retry) {
        for (int attempt = 1; ; attempt++) {
            try {
                return execute(request);
            } catch (IOException e) {
                if (!retry || attempt == ATTEMPTS) {
                    throw new UncheckedIOException("Hetzner API не ответил: " + e, e);
                }
                log.warn("Hetzner API не ответил на {} {} (попытка {} из {}): {}",
                        request.method(), request.uri().getPath(), attempt, ATTEMPTS, e.toString());
                sleep();
            }
        }
    }

    private JsonNode execute(HttpRequest request) throws IOException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Запрос к Hetzner API прерван", e);
        }
        JsonNode body = response.body().isBlank() ? MissingNode.getInstance() : objectMapper.readTree(response.body());
        if (response.statusCode() / 100 != 2) {
            JsonNode error = body.path("error");
            throw new ApiException(response.statusCode(), error.path("code").asText(), error.path("message").asText());
        }
        return body;
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_PAUSE.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание ответа Hetzner прервано", e);
        }
    }

    public static class ApiException extends RuntimeException {

        private final String code;

        ApiException(int status, String code, String message) {
            super("Hetzner API: %s (%s, HTTP %d)".formatted(message, code, status));
            this.code = code;
        }

        public String code() {
            return code;
        }

    }

}
