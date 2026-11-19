package com.example.hetzneriprenewal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Service
public class HetznerService {

    private static final Logger log = LoggerFactory.getLogger(HetznerService.class);

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    // Claude: если нужное состояние не наступило за это время, действие отправляется снова — запрос мог потеряться
    private static final Duration ACTION_REPEAT_INTERVAL = Duration.ofMinutes(1);
    private static final Duration STEP_TIMEOUT = Duration.ofMinutes(5);
    private static final DateTimeFormatter IP_NAME_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final HetznerApi api;
    private final BlockedIps blockedIps;
    private final long serverId;
    private final Duration shutdownTimeout;
    private final int maxIpAttempts;

    public HetznerService(HetznerApi api, BlockedIps blockedIps, HetznerProperties hetzner, RenewalProperties renewal) {
        this.api = api;
        this.blockedIps = blockedIps;
        this.serverId = hetzner.serverId();
        this.shutdownTimeout = renewal.shutdownTimeout();
        this.maxIpAttempts = renewal.maxIpAttempts();
    }

    public ServerInfo getServer() {
        return ServerInfo.from(api.get("servers/" + serverId).path("server"));
    }

    /* Claude: Меняет основной IPv4 сервера. Новый IP создаётся сразу привязанным к выключенному серверу, как при
       смене вручную. Старый IP попадает в список заблокированных: удалённый IP Hetzner возвращает в пул и может
       выдать снова. Каждый шаг ждёт завершения действия Hetzner и проверяет состояние сервера или IP: ответ
       на запрос может потеряться в сети. */
    public Rotation rotateIp() {
        ServerInfo server = getServer();
        if (server.ipv4Id() == null) {
            throw new IllegalStateException("У сервера нет основного IPv4");
        }
        long oldIpId = server.ipv4Id();
        String oldIp = server.ipv4();
        blockedIps.add(oldIp);
        boolean autoDelete = api.get("primary_ips/" + oldIpId).path("primary_ip").path("auto_delete").asBoolean();

        Rotation rotation;
        try {
            powerOff();
            log.info("Отвязываю старый IP {}", oldIp);
            require("Отвязка старого IP", () -> !isAssigned(oldIpId),
                    () -> api.post("primary_ips/%d/actions/unassign".formatted(oldIpId), null));
            rotation = createFreshIp(server.name(), autoDelete, oldIpId, oldIp);
        } catch (RuntimeException e) {
            try {
                powerOn();
            } catch (RuntimeException powerOnError) {
                e.addSuppressed(powerOnError);
            }
            throw e;
        }
        deleteIp(oldIpId, oldIp);
        try {
            powerOn();
        } catch (RuntimeException e) {
            throw new IllegalStateException("IP сменён на %s, но сервер не включился: %s".formatted(rotation.ip(), e.getMessage()), e);
        }
        return rotation;
    }

    public void powerOn() {
        log.info("Включаю сервер");
        require("Включение сервера", () -> getServer().running(),
                () -> api.post("servers/%d/actions/poweron".formatted(serverId), null));
    }

    private void powerOff() {
        log.info("Выключаю сервер");
        if (converge("Штатное выключение", this::isOff,
                () -> api.post("servers/%d/actions/shutdown".formatted(serverId), null), shutdownTimeout)) {
            return;
        }
        log.warn("Сервер не выключился за {} с, выключаю принудительно", shutdownTimeout.toSeconds());
        require("Выключение сервера", this::isOff,
                () -> api.post("servers/%d/actions/poweroff".formatted(serverId), null));
    }

    /* Claude: Создаёт привязанный IP, пока Hetzner не выдаст такой, которого нет в списке заблокированных.
       Отклонённый IP отвязывается, но не удаляется до конца смены: иначе Hetzner сразу выдал бы его снова.
       В конце, удачном или нет, отклонённые IP удаляются. */
    private Rotation createFreshIp(String serverName, boolean autoDelete, long oldIpId, String oldIp) {
        String baseName = serverName + "-ipv4-" + LocalDateTime.now().format(IP_NAME_SUFFIX);
        List<PrimaryIp> rejected = new ArrayList<>();
        try {
            for (int attempt = 1; ; attempt++) {
                // Claude: имя IP уникально в проекте, а попытки могут уложиться в одну секунду
                String name = attempt == 1 ? baseName : baseName + "-" + attempt;
                JsonNode created = create(name, autoDelete);
                long id = created.path("id").asLong();
                String ip = created.path("ip").asText();
                // Claude: привязывает IP само создание, здесь только проверка
                require("Привязка нового IP", () -> Objects.equals(getServer().ipv4Id(), id), MissingNode::getInstance);
                if (!blockedIps.contains(ip)) {
                    log.info("Создан и привязан новый IP {}", ip);
                    return new Rotation(ip, rejected.stream().map(PrimaryIp::ip).toList());
                }
                log.warn("Hetzner выдал IP {}, от которого бот уже уходил, — отвязываю и прошу другой", ip);
                require("Отвязка заблокированного IP " + ip, () -> !isAssigned(id),
                        () -> api.post("primary_ips/%d/actions/unassign".formatted(id), null));
                // Claude: в список только после отвязки — привязанный IP удалился бы вместе с привязкой, и сервер остался бы без IP
                rejected.add(new PrimaryIp(id, ip));
                if (attempt >= maxIpAttempts) {
                    throw new IllegalStateException("Hetzner %d раз подряд выдал IP, от которых бот уже уходил: %s"
                            .formatted(attempt, String.join(", ", rejected.stream().map(PrimaryIp::ip).toList())));
                }
            }
        } catch (RuntimeException e) {
            log.error("Не удалось создать и привязать новый IP ({}), привязываю обратно старый {}", e.getMessage(), oldIp);
            try {
                require("Привязка старого IP", () -> Objects.equals(getServer().ipv4Id(), oldIpId),
                        () -> api.post("primary_ips/%d/actions/assign".formatted(oldIpId),
                                Map.of("assignee_id", serverId, "assignee_type", "server")));
            } catch (RuntimeException assignError) {
                e.addSuppressed(assignError);
            }
            throw e;
        } finally {
            rejected.forEach(ip -> deleteIp(ip.id(), ip.ip()));
        }
    }

    // Claude: если ответ на создание потерялся в сети, IP мог всё же создаться — тогда он находится по имени
    private JsonNode create(String name, boolean autoDelete) {
        Map<String, Object> request = Map.of(
                "name", name,
                "type", "ipv4",
                "assignee_type", "server",
                "assignee_id", serverId,
                "auto_delete", autoDelete);
        JsonNode response;
        try {
            response = api.post("primary_ips", request);
        } catch (UncheckedIOException e) {
            JsonNode found;
            try {
                found = api.get("primary_ips?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)).path("primary_ips").path(0);
            } catch (RuntimeException lookupError) {
                e.addSuppressed(lookupError);
                throw e;
            }
            if (found.isMissingNode()) {
                throw e;
            }
            log.warn("Ответ на создание IP потерялся, но IP {} создан", found.path("ip").asText());
            return found;
        }
        waitForAction(response.path("action"), Instant.now().plus(STEP_TIMEOUT));
        return response.path("primary_ip");
    }

    private void deleteIp(long id, String ip) {
        try {
            api.delete("primary_ips/" + id);
        } catch (RuntimeException e) {
            // Claude: not_found — повтор запроса после потерянного ответа: IP уже удалён первой попыткой
            boolean alreadyDeleted = e instanceof HetznerApi.ApiException apiError && "not_found".equals(apiError.code());
            if (!alreadyDeleted) {
                log.error("Не удалось удалить IP {} — удалите его вручную в консоли Hetzner", ip, e);
                return;
            }
        }
        log.info("IP {} удалён", ip);
    }

    private boolean isOff() {
        return "off".equals(getServer().status());
    }

    private boolean isAssigned(long ipId) {
        return api.get("primary_ips/" + ipId).path("primary_ip").hasNonNull("assignee_id");
    }

    private void require(String step, BooleanSupplier done, Supplier<JsonNode> action) {
        if (!converge(step, done, action, STEP_TIMEOUT)) {
            throw new IllegalStateException("%s: не получилось за %d мин — подробности в логе бота"
                    .formatted(step, STEP_TIMEOUT.toMinutes()));
        }
    }

    /* Claude: Отправляет действие Hetzner, ждёт его завершения и проверяет, что сервер или IP пришли в нужное
       состояние. Если не пришли, раз в минуту повторяет действие: ответ на запрос мог потеряться в сети.
       Ошибки сети и API до истечения срока только логируются. Возвращает false, если срок истёк. */
    private boolean converge(String step, BooleanSupplier done, Supplier<JsonNode> action, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Instant nextAction = Instant.now();
        while (true) {
            try {
                if (done.getAsBoolean()) {
                    return true;
                }
                if (!Instant.now().isBefore(nextAction)) {
                    nextAction = Instant.now().plus(ACTION_REPEAT_INTERVAL);
                    waitForAction(action.get().path("action"), deadline);
                    continue;
                }
            } catch (RuntimeException e) {
                log.warn("{}: {}", step, e.getMessage());
            }
            if (Instant.now().isAfter(deadline)) {
                return false;
            }
            sleep();
        }
    }

    /* Claude: Состояние сервера или IP в API меняется раньше, чем завершается действие, а следующее действие
       до его завершения Hetzner отклоняет. Поэтому каждый шаг дожидается самого действия. */
    private void waitForAction(JsonNode action, Instant deadline) {
        if (!action.hasNonNull("id")) {
            return;
        }
        long id = action.path("id").asLong();
        String command = action.path("command").asText();
        JsonNode current = action;
        while ("running".equals(current.path("status").asText())) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Действие Hetzner %s не завершилось вовремя".formatted(command));
            }
            sleep();
            try {
                current = api.get("actions/" + id).path("action");
            } catch (UncheckedIOException e) {
                log.warn("Не удалось узнать, выполнено ли действие Hetzner {}: {}", command, e.getMessage());
            }
        }
        if (!"success".equals(current.path("status").asText())) {
            throw new IllegalStateException("Действие Hetzner %s завершилось с ошибкой: %s"
                    .formatted(command, current.path("error").path("message").asText()));
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание ответа Hetzner прервано", e);
        }
    }

    // Claude: rejectedIps — IP, которые Hetzner выдал во время смены, но они уже были заблокированы
    public record Rotation(String ip, List<String> rejectedIps) {

    }

    private record PrimaryIp(long id, String ip) {

    }

    public record ServerInfo(String name, String status, Long ipv4Id, String ipv4) {

        private static ServerInfo from(JsonNode server) {
            JsonNode ipv4 = server.path("public_net").path("ipv4");
            boolean hasIpv4 = ipv4.hasNonNull("id");
            return new ServerInfo(
                    server.path("name").asText(),
                    server.path("status").asText(),
                    hasIpv4 ? ipv4.path("id").asLong() : null,
                    hasIpv4 ? ipv4.path("ip").asText() : null);
        }

        public boolean running() {
            return "running".equals(status);
        }

    }

}
