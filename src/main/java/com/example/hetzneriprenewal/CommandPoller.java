package com.example.hetzneriprenewal;

import com.example.hetzneriprenewal.NtfyClient.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class CommandPoller {

    private static final Logger log = LoggerFactory.getLogger(CommandPoller.class);

    // Claude: команда, которая пролежала дольше (например, пока бот был выключен), уже неактуальна — особенно «сменить»
    private static final Duration MAX_COMMAND_AGE = Duration.ofMinutes(10);

    private final NtfyClient ntfy;
    private final IpMonitor monitor;

    // Claude: после запуска берутся только команды, отправленные позже него, дальше — после последней полученной
    private String since = String.valueOf(Instant.now().getEpochSecond());
    private boolean failing;

    public CommandPoller(NtfyClient ntfy, IpMonitor monitor) {
        this.ntfy = ntfy;
        this.monitor = monitor;
    }

    @Scheduled(fixedDelayString = "${ntfy.poll-interval}")
    public void poll() {
        List<NtfyClient.Message> messages;
        try {
            messages = ntfy.pollCommands(since);
        } catch (RuntimeException e) {
            if (!failing) {
                log.warn("Не удаётся получить команды из ntfy: {}", e.getMessage());
                failing = true;
            }
            return;
        }
        if (failing) {
            log.info("Связь с ntfy восстановлена");
            failing = false;
        }
        for (NtfyClient.Message message : messages) {
            since = message.id();
            try {
                handle(message);
            } catch (RuntimeException e) {
                log.error("Ошибка при выполнении команды «{}»", message.text(), e);
            }
        }
    }

    private void handle(NtfyClient.Message message) {
        log.info("Команда из ntfy: «{}»", message.text());
        Optional<Command> command = Command.parse(message.text());
        if (command.isEmpty()) {
            ntfy.send(Priority.DEFAULT, "Неизвестная команда",
                    "«%s». Доступны: статус, сменить, пауза, продолжить.".formatted(message.text()),
                    Command.STATUS);
            return;
        }
        Duration age = Duration.between(message.time(), Instant.now());
        if (age.compareTo(MAX_COMMAND_AGE) > 0) {
            ntfy.send(Priority.DEFAULT, "Команда пропущена",
                    "«%s» отправлена %d мин назад — это слишком давно, повторите её.".formatted(message.text(), age.toMinutes()));
            return;
        }
        switch (command.get()) {
            case STATUS -> monitor.sendStatus();
            case ROTATE -> monitor.rotateNow();
            case PAUSE -> monitor.pause();
            case RESUME -> monitor.resume();
        }
    }

}
