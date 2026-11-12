package com.example.hetzneriprenewal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class NtfyClient {

    private static final Logger log = LoggerFactory.getLogger(NtfyClient.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NtfyProperties properties;
    private final String baseUrl;

    public NtfyClient(NtfyProperties properties) {
        this.properties = properties;
        this.baseUrl = properties.url().replaceAll("/+$", "");
    }

    /* Claude: Отправляет уведомление в топик уведомлений. Кнопки шлют команду в топик команд токеном телефона.
       Ошибки только логируются: недоступный ntfy не должен мешать проверкам и смене IP. */
    public void send(Priority priority, String title, String message, Command... buttons) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", properties.alertsTopic());
        body.put("title", title);
        body.put("message", message);
        body.put("priority", priority.value);
        if (buttons.length > 0) {
            body.put("actions", Arrays.stream(buttons).map(this::action).toList());
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + properties.token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("ntfy не принял уведомление «{}»: HTTP {} {}", title, response.statusCode(), response.body().strip());
            }
        } catch (IOException e) {
            log.error("Не удалось отправить уведомление «{}» в ntfy: {}", title, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public List<Message> pollCommands(String since) {
        URI uri = URI.create("%s/%s/json?poll=1&since=%s"
                .formatted(baseUrl, properties.commandsTopic(), URLEncoder.encode(since, StandardCharsets.UTF_8)));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + properties.token())
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("ntfy ответил HTTP %d: %s".formatted(response.statusCode(), response.body().strip()));
            }
            List<Message> messages = new ArrayList<>();
            for (String line : response.body().split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode event = objectMapper.readTree(line);
                if ("message".equals(event.path("event").asText())) {
                    messages.add(new Message(
                            event.path("id").asText(),
                            Instant.ofEpochSecond(event.path("time").asLong()),
                            event.path("message").asText()));
                }
            }
            return messages;
        } catch (IOException e) {
            throw new UncheckedIOException("ntfy недоступен: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Опрос ntfy прерван", e);
        }
    }

    private Map<String, Object> action(Command command) {
        return Map.of(
                "action", "http",
                "label", command.label(),
                "url", baseUrl + "/" + properties.commandsTopic(),
                "method", "POST",
                "headers", Map.of("Authorization", "Bearer " + properties.commandToken()),
                "body", command.word(),
                "clear", true);
    }

    public record Message(String id, Instant time, String text) {

    }

    public enum Priority {

        LOW(2),
        DEFAULT(3),
        HIGH(4),
        URGENT(5);

        private final int value;

        Priority(int value) {
            this.value = value;
        }

    }

}
