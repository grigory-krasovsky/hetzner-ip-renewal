package com.example.hetzneriprenewal;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

@Component
public class CascadeClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(1);

    private final CascadeProperties properties;

    public CascadeClient(CascadeProperties properties) {
        this.properties = properties;
    }

    /* Claude: Переключает проброс на российском VPS на новый IP и возвращает ответ скрипта.
       Ключ бота на VPS привязан к скрипту cascade-set-target (command= в authorized_keys),
       поэтому вся команда — это сам IP. */
    public String setTarget(String ip) {
        Session session = null;
        ChannelExec channel = null;
        try {
            JSch jsch = new JSch();
            jsch.addIdentity(properties.privateKey());
            jsch.setKnownHosts(properties.knownHosts());
            session = jsch.getSession(properties.user(), properties.host(), properties.sshPort());
            session.setConfig("StrictHostKeyChecking", "yes");
            session.connect((int) CONNECT_TIMEOUT.toMillis());

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(ip);
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOutputStream(stdout);
            channel.setErrStream(stderr);
            channel.connect((int) CONNECT_TIMEOUT.toMillis());
            waitForClose(channel);

            if (channel.getExitStatus() != 0) {
                throw new IllegalStateException("Скрипт каскада завершился с кодом %d: %s"
                        .formatted(channel.getExitStatus(), stderr.toString(StandardCharsets.UTF_8).strip()));
            }
            return stdout.toString(StandardCharsets.UTF_8).strip();
        } catch (JSchException e) {
            throw new IllegalStateException("Не удалось выполнить команду на VPS каскада по SSH: " + e.getMessage(), e);
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    public String host() {
        return properties.host();
    }

    public int probePort() {
        return properties.probePort();
    }

    private static void waitForClose(ChannelExec channel) {
        Instant deadline = Instant.now().plus(COMMAND_TIMEOUT);
        while (!channel.isClosed()) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Скрипт каскада не завершился за %d с".formatted(COMMAND_TIMEOUT.toSeconds()));
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Ожидание ответа VPS каскада прервано", e);
            }
        }
    }

}
