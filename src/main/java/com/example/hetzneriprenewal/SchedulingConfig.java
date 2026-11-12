package com.example.hetzneriprenewal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Claude: отключается в тестах, чтобы контекст не ходил в Hetzner и DuckDNS
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "renewal.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

}
