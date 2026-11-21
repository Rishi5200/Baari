package com.baari.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baari.AbstractIntegrationTest;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Per-IP rate limits through the real filter and real Redis (PLAN §9.3), with small limits so the
 * tests are quick. Each test uses its own made-up client address, so buckets never carry over.
 */
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "baari.rate-limit.enabled=true",
            "baari.rate-limit.join-per-minute=3",
            "baari.rate-limit.token-page-per-minute=5",
            "baari.rate-limit.login-per-minute=2"
        })
class RateLimitIT extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String someAddress() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255);
    }

    @Test
    void joiningIsLimitedPerAddressWithAProblemBodyAndRetryAfter() throws Exception {
        String ip = someAddress();
        String url = "/api/public/queues/" + UUID.randomUUID() + "/tokens";
        String body = "{\"name\":\"A\",\"visitType\":\"NEW\",\"consentVersion\":\"v1\",\"idempotencyKey\":\""
                + UUID.randomUUID() + "\"}";
        for (int i = 0; i < 3; i++) {
            // an unknown queue: 404, but it still counts as an attempt
            mvc.perform(post(url).with(from(ip)).contentType("application/json").content(body))
                    .andExpect(status().is4xxClientError())
                    .andExpect(status().is(not(429)));
        }
        mvc.perform(post(url).with(from(ip)).contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(content().string(containsString("rate-limited")));

        // another patient's phone (another address) is not affected
        mvc.perform(post(url)
                        .with(from(someAddress()))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().is(not(429)));
    }

    @Test
    void theTokenPageIsLimitedPerAddress() throws Exception {
        String ip = someAddress();
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/t/doesNotExist0000000000").with(from(ip))).andExpect(status().is(not(429)));
        }
        mvc.perform(get("/t/doesNotExist0000000000").with(from(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().string(containsString("Too many requests")));
    }

    @Test
    void staffLoginIsLimitedPerAddressBeforeThePasswordIsChecked() throws Exception {
        String ip = someAddress();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/login")
                            .with(from(ip))
                            .with(csrf())
                            .param("username", "nobody")
                            .param("password", "wrong-password"))
                    .andExpect(status().is3xxRedirection());
        }
        mvc.perform(post("/login")
                        .with(from(ip))
                        .with(csrf())
                        .param("username", "nobody")
                        .param("password", "wrong-password"))
                .andExpect(status().isTooManyRequests());
    }
}
