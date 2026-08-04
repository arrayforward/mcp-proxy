package com.mcpproxy.validator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TokenController.class)
class TokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void issueThenValidate() throws Exception {
        MvcResult issued = mockMvc.perform(post("/api/token/issue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uid\":\"user-10001\",\"instanceId\":\"Ab3xYz9p\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresIn", is(10)))
                .andReturn();
        String body = issued.getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/validate/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(true)))
                .andExpect(jsonPath("$.uid", is("user-10001")))
                .andExpect(jsonPath("$.instanceId", is("Ab3xYz9p")));
    }

    @Test
    void malformedTokenRejected() throws Exception {
        mockMvc.perform(post("/api/validate/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"garbage\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(false)))
                .andExpect(jsonPath("$.reason", is("malformed")));
    }

    @Test
    void expiredTokenRejected() throws Exception {
        String old = "tmp.user-10001.Ab3xYz9p." + (System.currentTimeMillis() - 60_000L);
        mockMvc.perform(post("/api/validate/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + old + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid", is(false)))
                .andExpect(jsonPath("$.reason", is("expired")));
    }
}
