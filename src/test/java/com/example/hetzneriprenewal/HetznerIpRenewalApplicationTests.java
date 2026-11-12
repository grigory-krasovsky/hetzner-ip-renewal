package com.example.hetzneriprenewal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "hetzner.token=test",
        "hetzner.server-id=1",
        "duckdns.token=test",
        "duckdns.domains=test",
        "cascade.host=test",
        "cascade.private-key=test",
        "cascade.known-hosts=test",
        "ntfy.url=http://localhost",
        "ntfy.token=test",
        "ntfy.command-token=test",
        "renewal.scheduling-enabled=false"
})
class HetznerIpRenewalApplicationTests {

    @Test
    void contextLoads() {
    }

}
