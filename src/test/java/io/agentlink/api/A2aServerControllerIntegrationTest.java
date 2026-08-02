package io.agentlink.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class A2aServerControllerIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void publishesAgentCardAndAcceptsA2aMessage() throws Exception {
        mockMvc.perform(get("/.well-known/agent-card.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("SA-AgentLink Coordinator"))
                .andExpect(jsonPath("$.supportedInterfaces[0].protocolVersion").value("1.0"));

        mockMvc.perform(post("/a2a/message:send")
                        .contentType("application/a2a+json")
                        .content("""
                                {
                                  "message": {
                                    "messageId": "message-1",
                                    "role": "ROLE_USER",
                                    "parts": [{"text": "analyze this request"}]
                                  },
                                  "metadata": {"skill": "analysis"},
                                  "configuration": {"returnImmediately": false}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.state").value("TASK_STATE_COMPLETED"))
                .andExpect(jsonPath("$.artifacts[0].parts[0].text").isNotEmpty());
    }
}
