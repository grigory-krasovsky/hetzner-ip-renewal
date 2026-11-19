package com.example.hetzneriprenewal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/* Claude: IP, от которых бот уходил при смене, с датой блокировки. Hetzner возвращает удалённые IP в пул
   и может выдать их снова, поэтому новый IP сверяется с этим списком. Список хранится в файле и переживает
   перезапуск бота. Формат файла — строка на IP: «1.2.3.4 2026-11-19»; IP можно дописать и вручную, без даты
   он считается заблокированным в день запуска бота. */
@Component
public class BlockedIps {

    private static final Logger log = LoggerFactory.getLogger(BlockedIps.class);

    private final Path file;
    private final long retentionDays;
    private final Map<String, LocalDate> blockedAt = new LinkedHashMap<>();

    public BlockedIps(RenewalProperties properties) {
        this.file = Path.of(properties.blockedIpsFile());
        this.retentionDays = properties.blockedIpRetention().toDays();
        load();
    }

    public synchronized boolean contains(String ip) {
        LocalDate date = blockedAt.get(ip);
        return date != null && !expired(date);
    }

    public synchronized void add(String ip) {
        blockedAt.values().removeIf(this::expired);
        blockedAt.put(ip, LocalDate.now());
        save();
    }

    private boolean expired(LocalDate date) {
        return date.plusDays(retentionDays).isBefore(LocalDate.now());
    }

    private void load() {
        if (!Files.exists(file)) {
            log.info("Списка заблокированных IP {} ещё нет — начинаю с пустого", file);
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            log.error("Не удалось прочитать список заблокированных IP {} — начинаю с пустого", file, e);
            return;
        }
        boolean undated = false;
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts[0].isEmpty() || parts[0].startsWith("#")) {
                continue;
            }
            LocalDate date = LocalDate.now();
            if (parts.length > 1) {
                try {
                    date = LocalDate.parse(parts[1]);
                } catch (DateTimeParseException e) {
                    log.warn("Список заблокированных IP: дата «{}» не разобрана, считаю IP {} заблокированным сегодня",
                            parts[1], parts[0]);
                    undated = true;
                }
            } else {
                undated = true;
            }
            blockedAt.put(parts[0], date);
        }
        log.info("Заблокированных IP в списке: {}", blockedAt.size());
        // Claude: дата проставляется в файле, иначе после каждого перезапуска срок хранения начинался бы заново
        if (undated) {
            save();
        }
    }

    private void save() {
        List<String> lines = blockedAt.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .toList();
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, lines);
        } catch (IOException e) {
            log.error("Не удалось сохранить список заблокированных IP {} — после перезапуска бот его забудет", file, e);
        }
    }

}
