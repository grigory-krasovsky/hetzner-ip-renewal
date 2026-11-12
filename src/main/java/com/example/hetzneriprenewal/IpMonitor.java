package com.example.hetzneriprenewal;

import com.example.hetzneriprenewal.NtfyClient.Priority;
import io.github.sinuscosinustan.hetznercloud.objects.general.PublicNet;
import io.github.sinuscosinustan.hetznercloud.objects.general.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Component
public class IpMonitor {

    private static final Logger log = LoggerFactory.getLogger(IpMonitor.class);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    private final HetznerService hetzner;
    private final CascadeClient cascade;
    private final NtfyClient ntfy;
    private final RenewalProperties properties;
    private final Target cascadeTarget;
    private final Target dnsTarget;

    /* Claude: Состояние живёт только в памяти и сбрасывается перезапуском. Публичные методы synchronized:
       плановая проверка и команды из ntfy не должны выполняться одновременно. */
    private boolean started;
    private boolean paused;
    private Boolean serverRunning;
    private String serverIp;
    private Instant lastCheckAt;
    private Instant outageStartedAt;
    private boolean outageAlerted;
    private boolean cascadeBroken;
    private String lastRotation;

    public IpMonitor(HetznerService hetzner, CascadeClient cascade, DuckDnsClient duckDns, NtfyClient ntfy,
                     RenewalProperties properties) {
        this.hetzner = hetzner;
        this.cascade = cascade;
        this.ntfy = ntfy;
        this.properties = properties;
        this.cascadeTarget = new Target("Каскад", ip -> log.info("Каскад: {}", cascade.setTarget(ip)));
        this.dnsTarget = new Target("DuckDNS " + duckDns.domains(), duckDns::update);
    }

    @Scheduled(fixedDelayString = "${renewal.check-interval}")
    public synchronized void check() {
        if (!started) {
            started = true;
            ntfy.send(Priority.LOW, "Бот запущен", "Проверяю сервер каждые %d мин. Команды: статус, сменить, пауза, продолжить."
                    .formatted(properties.checkInterval().toMinutes()));
        }
        if (paused) {
            return;
        }
        String ip = currentIp();
        if (ip == null) {
            /* Claude: сервер не работает или не отвечает Hetzner API (скорее всего, пропал интернет у самого бота) —
               недоступность IP тогда ничего не говорит о блокировке, поэтому отсчёт начинается заново */
            resetOutage();
            return;
        }
        cascadeTarget.sync(ip);
        dnsTarget.sync(ip);

        lastCheckAt = Instant.now();
        if (isReachable(ip, properties.probePort())) {
            if (outageStartedAt != null) {
                log.info("IP {} снова доступен", ip);
                if (outageAlerted) {
                    ntfy.send(Priority.DEFAULT, "IP снова доступен", "IP %s снова отвечает, менять его не нужно.".formatted(ip));
                }
            }
            resetOutage();
            checkCascade(ip);
            return;
        }
        if (outageStartedAt == null) {
            outageStartedAt = lastCheckAt;
        }
        Duration outage = Duration.between(outageStartedAt, lastCheckAt);
        log.warn("IP {} недоступен {} мин", ip, outage.toMinutes());
        if (outage.compareTo(properties.outageThreshold()) >= 0) {
            resetOutage();
            rotate(ip, "IP %s не отвечает %d мин — похоже, заблокирован".formatted(ip, outage.toMinutes()));
        } else if (!outageAlerted && outage.compareTo(properties.alertThreshold()) >= 0) {
            outageAlerted = true;
            ntfy.send(Priority.HIGH, "IP недоступен",
                    "IP %s не отвечает %d мин. Если не восстановится, около %s сменю его."
                            .formatted(ip, outage.toMinutes(), TIME.format(rotationDue())),
                    Command.ROTATE, Command.PAUSE);
        }
    }

    public synchronized void sendStatus() {
        if (!paused) {
            check();
        }
        ntfy.send(Priority.LOW, "Статус", statusText(), Command.ROTATE, paused ? Command.RESUME : Command.PAUSE);
    }

    public synchronized void rotateNow() {
        String ip = currentIp();
        if (ip == null) {
            ntfy.send(Priority.DEFAULT, "IP не сменён",
                    "Сервер не работает, у него нет IPv4 или не отвечает Hetzner API — подробности в логе бота.");
            return;
        }
        resetOutage();
        rotate(ip, "Смена IP %s по команде".formatted(ip));
    }

    public synchronized void pause() {
        paused = true;
        resetOutage();
        log.info("Мониторинг на паузе");
        ntfy.send(Priority.DEFAULT, "Мониторинг на паузе",
                "IP не проверяется и не меняется автоматически, команда «сменить» работает. "
                        + "Пауза снимается командой «продолжить» или перезапуском бота.",
                Command.RESUME);
    }

    public synchronized void resume() {
        paused = false;
        log.info("Мониторинг возобновлён");
        check();
        ntfy.send(Priority.DEFAULT, "Мониторинг возобновлён", statusText(), Command.ROTATE, Command.PAUSE);
    }

    private String currentIp() {
        Server server;
        try {
            server = hetzner.getServer();
        } catch (RuntimeException e) {
            log.warn("Hetzner API недоступен: {}", e.getMessage());
            return null;
        }
        boolean running = "running".equals(server.getStatus());
        boolean changed = serverRunning == null ? !running : serverRunning != running;
        serverRunning = running;
        if (changed && running) {
            log.info("Сервер снова работает");
            ntfy.send(Priority.DEFAULT, "Сервер работает", "Сервер снова запущен, проверки возобновлены.");
        } else if (changed) {
            log.warn("Сервер в статусе {}", server.getStatus());
            ntfy.send(Priority.HIGH, "Сервер не работает",
                    "Статус сервера в Hetzner: %s. Пока он не запущен, IP не проверяется и не меняется."
                            .formatted(server.getStatus()));
        }
        if (!running) {
            return null;
        }
        PublicNet.IPv4 ipv4 = server.getPublicNet().getIpv4();
        if (ipv4 == null) {
            log.error("У сервера нет основного IPv4 — привяжите IP вручную в консоли Hetzner");
            return null;
        }
        serverIp = ipv4.getIp();
        return serverIp;
    }

    private void rotate(String oldIp, String reason) {
        log.warn("{} — меняю IP", reason);
        ntfy.send(Priority.HIGH, "Меняю IP", reason + ". Сервер будет недоступен несколько минут.");
        String newIp;
        try {
            newIp = hetzner.rotateIp();
        } catch (RuntimeException e) {
            log.error("Не удалось сменить IP", e);
            lastRotation = "%s, не удалась: %s".formatted(TIME.format(Instant.now()), e.getMessage());
            ntfy.send(Priority.URGENT, "Не удалось сменить IP",
                    "%s\nСнова попробую сам, если IP будет недоступен %d мин."
                            .formatted(e.getMessage(), properties.outageThreshold().toMinutes()),
                    Command.ROTATE, Command.STATUS);
            return;
        }
        log.info("IP сервера сменён: {} → {}", oldIp, newIp);
        serverIp = newIp;
        lastRotation = "%s, %s → %s".formatted(TIME.format(Instant.now()), oldIp, newIp);
        cascadeTarget.sync(newIp);
        dnsTarget.sync(newIp);
        ntfy.send(Priority.HIGH, "IP сменён", "%s → %s\nКаскад: %s\nDuckDNS: %s"
                .formatted(oldIp, newIp, cascadeState(newIp), dnsTarget.pointsTo(newIp) ? "обновлён" : "не обновлён"));
    }

    // Claude: на смену IP не влияет: если сервер доступен напрямую, а через каскад нет, сломан проброс на VPS
    private void checkCascade(String ip) {
        if (!cascadeTarget.pointsTo(ip)) {
            return;
        }
        boolean broken = !isReachable(cascade.host(), cascade.probePort());
        if (broken == cascadeBroken) {
            return;
        }
        cascadeBroken = broken;
        if (broken) {
            log.warn("Сервер доступен напрямую, но не через каскад {}:{}", cascade.host(), cascade.probePort());
            ntfy.send(Priority.HIGH, "Каскад не работает",
                    "Сервер %s отвечает напрямую, но не через %s:%d — похоже, сломан проброс на российском VPS или сам VPS."
                            .formatted(ip, cascade.host(), cascade.probePort()));
        } else {
            log.info("Каскад снова работает");
            ntfy.send(Priority.DEFAULT, "Каскад снова работает", "Сервер снова отвечает через " + cascade.host());
        }
    }

    private String statusText() {
        List<String> lines = new ArrayList<>();
        lines.add(paused ? "Мониторинг на паузе" : "Мониторинг включён");
        if (serverIp == null) {
            lines.add("Сервер: нет данных");
        } else {
            lines.add("Сервер: %s, IP %s".formatted(Boolean.TRUE.equals(serverRunning) ? "работает" : "не работает", serverIp));
            lines.add("Каскад: " + cascadeState(serverIp));
            lines.add("DuckDNS: " + (dnsTarget.pointsTo(serverIp) ? "указывает на текущий IP" : "не обновлён, повторю при проверке"));
        }
        if (lastCheckAt == null) {
            lines.add("Проверок ещё не было");
        } else if (outageStartedAt == null) {
            lines.add("Проверка %s: отвечает".formatted(TIME.format(lastCheckAt)));
        } else {
            lines.add("Проверка %s: не отвечает с %s, смена около %s"
                    .formatted(TIME.format(lastCheckAt), TIME.format(outageStartedAt), TIME.format(rotationDue())));
        }
        lines.add("Последняя смена IP: " + (lastRotation == null ? "с запуска бота не было" : lastRotation));
        return String.join("\n", lines);
    }

    private String cascadeState(String ip) {
        if (!cascadeTarget.pointsTo(ip)) {
            return "не переключён на текущий IP, повторю при проверке";
        }
        return cascadeBroken ? "переключён, но сервер через него не отвечает" : "переключён на текущий IP";
    }

    private Instant rotationDue() {
        return outageStartedAt.plus(properties.outageThreshold());
    }

    private void resetOutage() {
        outageStartedAt = null;
        outageAlerted = false;
    }

    private boolean isReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) properties.probeTimeout().toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /* Claude: То, что должно указывать на текущий IP сервера: каскад или DuckDNS. Запомненный IP меняется
       только после успешного обновления, поэтому при ошибке следующая проверка повторит его. О сбое
       уведомляет один раз на каждый IP, о восстановлении — тоже. */
    private final class Target {

        private final String name;
        private final Consumer<String> update;
        private String syncedIp;
        private String failedIp;

        private Target(String name, Consumer<String> update) {
            this.name = name;
            this.update = update;
        }

        private boolean pointsTo(String ip) {
            return ip.equals(syncedIp);
        }

        private void sync(String ip) {
            if (pointsTo(ip)) {
                return;
            }
            try {
                update.accept(ip);
            } catch (RuntimeException e) {
                log.error("{}: не удалось перевести на {}", name, ip, e);
                if (!ip.equals(failedIp)) {
                    failedIp = ip;
                    ntfy.send(Priority.HIGH, name + ": ошибка",
                            "Не удалось перевести на %s: %s\nПовторяю при каждой проверке.".formatted(ip, e.getMessage()));
                }
                return;
            }
            syncedIp = ip;
            log.info("{} указывает на {}", name, ip);
            if (failedIp != null) {
                ntfy.send(Priority.DEFAULT, name + ": исправлено", "Теперь указывает на " + ip);
                failedIp = null;
            }
        }

    }

}
