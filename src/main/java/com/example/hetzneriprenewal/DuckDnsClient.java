package com.example.hetzneriprenewal;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DuckDnsClient {

    private static final Logger log = LoggerFactory.getLogger(DuckDnsClient.class);

    private static final String UPDATE_URL = "https://www.duckdns.org/update?domains=%s&token=%s&ip=%s";
    private static final int ATTEMPTS = 3;
    private static final Duration RETRY_PAUSE = Duration.ofSeconds(5);
    private static final Pattern HTML_TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int MAX_BODY_LENGTH = 200;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final DuckDnsProperties properties;

    public DuckDnsClient(DuckDnsProperties properties) {
        this.properties = properties;
    }

    /* Claude: ip передаётся явно: без него DuckDNS записал бы адрес, с которого пришёл запрос, то есть домашний.
       URL с токеном нигде не логируется. Сбой связи и 5xx повторяются: DuckDNS бывает недоступен по несколько
       секунд, а без повтора после смены IP прямой домен указывал бы на удалённый IP до следующей проверки.
       Отказ с другим кодом (KO — неверный токен или домен) не повторяется: повтор его не исправит. */
    public void update(String ip) {
        URI uri = URI.create(UPDATE_URL.formatted(encode(properties.domains()), encode(properties.token()), encode(ip)));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        for (int attempt = 1; ; attempt++) {
            String failure;
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body().strip();
                if ("OK".equals(body)) {
                    return;
                }
                failure = "HTTP %d, ответ «%s»".formatted(response.statusCode(), brief(body));
                if (response.statusCode() / 100 != 5 || attempt == ATTEMPTS) {
                    throw new IllegalStateException("DuckDNS отклонил обновление: " + failure);
                }
            } catch (IOException e) {
                if (attempt == ATTEMPTS) {
                    throw new UncheckedIOException("DuckDNS недоступен", e);
                }
                failure = e.toString();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Обновление DuckDNS прервано", e);
            }
            log.warn("DuckDNS не обновлён (попытка {} из {}): {}", attempt, ATTEMPTS, failure);
            sleep();
        }
    }

    public String domains() {
        return properties.domains();
    }

    // Claude: при сбое DuckDNS отдаёт HTML-страницу на ~10 КБ — от неё остаётся только заголовок («Duck DNS - site down»)
    private static String brief(String body) {
        Matcher title = HTML_TITLE.matcher(body);
        String text = (title.find() ? title.group(1) : body).replaceAll("\\s+", " ").strip();
        return text.length() <= MAX_BODY_LENGTH ? text : text.substring(0, MAX_BODY_LENGTH) + "…";
    }

    private static void sleep() {
        try {
            Thread.sleep(RETRY_PAUSE.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Обновление DuckDNS прервано", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

}
