package com.example.hetzneriprenewal;

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

@Component
public class DuckDnsClient {

    private static final String UPDATE_URL = "https://www.duckdns.org/update?domains=%s&token=%s&ip=%s";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final DuckDnsProperties properties;

    public DuckDnsClient(DuckDnsProperties properties) {
        this.properties = properties;
    }

    /* Claude: ip передаётся явно: без него DuckDNS записал бы адрес, с которого пришёл запрос, то есть домашний.
       URL с токеном нигде не логируется. */
    public void update(String ip) {
        URI uri = URI.create(UPDATE_URL.formatted(encode(properties.domains()), encode(properties.token()), encode(ip)));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("DuckDNS недоступен", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Обновление DuckDNS прервано", e);
        }
        if (!"OK".equals(response.body().strip())) {
            throw new IllegalStateException("DuckDNS отклонил обновление: HTTP %d, ответ «%s»"
                    .formatted(response.statusCode(), response.body().strip()));
        }
    }

    public String domains() {
        return properties.domains();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

}
