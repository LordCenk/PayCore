package com.paycore;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.paycore.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/** The demo page is public static content: no API key needed. Its behaviour is covered by scripts/demo-e2e.js. */
class DemoPageIT extends IntegrationTest {

    @Test
    void rootServesTheDemoPage() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>PayCore demo</title>")));
    }

    @Test
    void assetsAreServed() throws Exception {
        mvc.perform(get("/demo.js")).andExpect(status().isOk()).andExpect(content().string(containsString("ensureSession")));
        mvc.perform(get("/demo.css")).andExpect(status().isOk());
    }
}
