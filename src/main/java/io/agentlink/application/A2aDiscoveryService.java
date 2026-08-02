package io.agentlink.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentlink.domain.AgentCard;
import io.agentlink.domain.AgentSkill;
import io.agentlink.infrastructure.OutboundUrlPolicy;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class A2aDiscoveryService {
    private final AgentDirectory directory;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final OutboundUrlPolicy outboundUrlPolicy;

    public A2aDiscoveryService(
            AgentDirectory directory,
            ObjectMapper objectMapper,
            OutboundUrlPolicy outboundUrlPolicy
    ) {
        this.directory = directory;
        this.objectMapper = objectMapper;
        this.outboundUrlPolicy = outboundUrlPolicy;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public AgentCard discover(String cardUrl, String requestedId) {
        try {
            HttpRequest request = HttpRequest.newBuilder(outboundUrlPolicy.validate(cardUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("Agent Card endpoint returned HTTP " + response.statusCode());
            }
            AgentCard card = mapCard(objectMapper.readTree(response.body()), requestedId);
            directory.register(card);
            return card;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent Card discovery interrupted", failure);
        } catch (Exception failure) {
            throw new IllegalArgumentException("failed to discover Agent Card: " + failure.getMessage(), failure);
        }
    }

    private AgentCard mapCard(JsonNode document, String requestedId) {
        String name = requiredText(document, "name");
        JsonNode selectedInterface = document.path("supportedInterfaces").isArray()
                ? document.path("supportedInterfaces").path(0)
                : document;
        String endpoint = selectedInterface.path("url").asText(document.path("url").asText(""));
        if (endpoint.isBlank()) {
            throw new IllegalArgumentException("Agent Card has no supported interface URL");
        }
        String version = selectedInterface.path("protocolVersion").asText("1.0");
        List<AgentSkill> skills = new ArrayList<>();
        document.path("skills").forEach(skill -> skills.add(new AgentSkill(
                skill.path("id").asText(skill.path("name").asText()),
                skill.path("description").asText("")
        )));
        if (skills.isEmpty()) {
            throw new IllegalArgumentException("Agent Card declares no skills");
        }
        String id = requestedId == null || requestedId.isBlank() ? slug(name) : requestedId;
        return new AgentCard(id, name, endpoint, version, skills, true, 50);
    }

    private String requiredText(JsonNode document, String field) {
        String value = document.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Agent Card field is required: " + field);
        }
        return value;
    }

    private String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
