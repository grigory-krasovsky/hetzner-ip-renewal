package com.example.hetzneriprenewal;

import io.github.sinuscosinustan.hetznercloud.HetznerCloudAPI;
import io.github.sinuscosinustan.hetznercloud.objects.enums.IPAssigneeType;
import io.github.sinuscosinustan.hetznercloud.objects.enums.IPType;
import io.github.sinuscosinustan.hetznercloud.objects.general.Action;
import io.github.sinuscosinustan.hetznercloud.objects.general.PrimaryIP;
import io.github.sinuscosinustan.hetznercloud.objects.general.PublicNet;
import io.github.sinuscosinustan.hetznercloud.objects.general.Server;
import io.github.sinuscosinustan.hetznercloud.objects.request.AssignPrimaryIPRequest;
import io.github.sinuscosinustan.hetznercloud.objects.request.CreatePrimaryIPRequest;
import io.github.sinuscosinustan.hetznercloud.objects.response.CreatePrimaryIPResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class HetznerService {

    private static final Logger log = LoggerFactory.getLogger(HetznerService.class);

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
    private static final Duration ACTION_TIMEOUT = Duration.ofMinutes(5);
    private static final DateTimeFormatter IP_NAME_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final HetznerCloudAPI api;
    private final long serverId;
    private final Duration shutdownTimeout;

    public HetznerService(HetznerCloudAPI api, HetznerProperties hetzner, RenewalProperties renewal) {
        this.api = api;
        this.serverId = hetzner.serverId();
        this.shutdownTimeout = renewal.shutdownTimeout();
    }

    public Server getServer() {
        return api.getServer(serverId).getServer();
    }

    /* Claude: Меняет основной IPv4 сервера и возвращает новый адрес.
       Новый IP создаётся сразу привязанным к выключенному серверу, а не заранее: Hetzner убрал поле datacenter
       у Primary IP, а в библиотеке 5.0.1 нет поля location, так что создать непривязанный IP через неё нельзя. */
    public String rotateIp() {
        Server server = getServer();
        PublicNet.IPv4 ipv4 = server.getPublicNet().getIpv4();
        if (ipv4 == null) {
            throw new IllegalStateException("У сервера нет основного IPv4");
        }
        PrimaryIP oldIp = api.getPrimaryIP(ipv4.getId()).getPrimaryIP();

        PrimaryIP newIp;
        try {
            powerOff();
            log.info("Отвязываю старый IP {}", oldIp.getIp());
            waitFor(api.unassignPrimaryIP(oldIp.getId()).getAction());
            newIp = createAssignedIp(server.getName(), oldIp);
        } catch (RuntimeException e) {
            try {
                powerOn();
            } catch (RuntimeException powerOnError) {
                e.addSuppressed(powerOnError);
            }
            throw e;
        }
        deleteIp(oldIp);
        powerOn();
        return newIp.getIp();
    }

    private void powerOff() {
        log.info("Выключаю сервер");
        waitFor(api.shutdownServer(serverId).getAction());
        if (waitUntilOff(shutdownTimeout)) {
            return;
        }
        log.warn("Сервер не выключился за {} с, выключаю принудительно", shutdownTimeout.toSeconds());
        waitFor(api.powerOffServer(serverId).getAction());
    }

    private void powerOn() {
        log.info("Включаю сервер");
        waitFor(api.powerOnServer(serverId).getAction());
    }

    private PrimaryIP createAssignedIp(String serverName, PrimaryIP oldIp) {
        CreatePrimaryIPRequest request = CreatePrimaryIPRequest.builder()
                .name(serverName + "-ipv4-" + LocalDateTime.now().format(IP_NAME_SUFFIX))
                .type(IPType.ipv4)
                .assigneeType(IPAssigneeType.server)
                .assigneeId(serverId)
                .autoDelete(oldIp.getAutoDelete())
                .build();
        try {
            CreatePrimaryIPResponse response = api.createPrimaryIP(request);
            if (response.getAction() != null) {
                waitFor(response.getAction());
            }
            log.info("Создан и привязан новый IP {}", response.getPrimaryIP().getIp());
            return response.getPrimaryIP();
        } catch (RuntimeException e) {
            log.error("Не удалось создать новый IP, привязываю обратно старый {}", oldIp.getIp());
            try {
                AssignPrimaryIPRequest assignBack = AssignPrimaryIPRequest.builder()
                        .assigneeId(serverId)
                        .assigneeType(IPAssigneeType.server)
                        .build();
                waitFor(api.assignPrimaryIP(oldIp.getId(), assignBack).getAction());
            } catch (RuntimeException assignError) {
                e.addSuppressed(assignError);
            }
            throw e;
        }
    }

    private void deleteIp(PrimaryIP ip) {
        try {
            api.deletePrimaryIP(ip.getId());
            log.info("Старый IP {} удалён", ip.getIp());
        } catch (RuntimeException e) {
            log.error("Не удалось удалить старый IP {} — удалите его вручную в консоли Hetzner", ip.getIp(), e);
        }
    }

    private void waitFor(Action action) {
        Instant deadline = Instant.now().plus(ACTION_TIMEOUT);
        Action current = action;
        while ("running".equals(current.getStatus())) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Действие Hetzner %s не завершилось за %d мин"
                        .formatted(current.getCommand(), ACTION_TIMEOUT.toMinutes()));
            }
            sleep();
            current = api.getAction(current.getId()).getAction();
        }
        if (!"success".equals(current.getStatus())) {
            throw new IllegalStateException("Действие Hetzner %s завершилось с ошибкой: %s"
                    .formatted(current.getCommand(), current.getError()));
        }
    }

    private boolean waitUntilOff(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (!"off".equals(getServer().getStatus())) {
            if (Instant.now().isAfter(deadline)) {
                return false;
            }
            sleep();
        }
        return true;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание ответа Hetzner прервано", e);
        }
    }

}
