package io.kestra.plugin.miro.boards;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import com.fasterxml.jackson.core.type.TypeReference;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow when new Miro boards are created",
    description = """
        Polls the Miro boards list at the configured interval and fires an execution when one or more boards
        have a `createdAt` timestamp in the half-open window (pollDate - interval, pollDate].
        Detection is window-based: no persistent state is kept between polls.
        Boards created during downtime or skipped polls fall outside the active window and will be missed.
        Only the most recent `limit` boards (up to 50) are inspected per poll; creation bursts that exceed
        `limit` in a single interval may also go undetected.
        Filter by `teamId` or `projectId` to scope the watch to a specific workspace."""
)
@Plugin(
    examples = {
        @Example(
            title = "React when a new board is created in a team",
            full = true,
            code = """
                id: on_new_miro_board
                namespace: company.team

                triggers:
                  - id: new_board
                    type: io.kestra.plugin.miro.boards.Trigger
                    token: "{{ secret('MIRO_TOKEN') }}"
                    teamId: "{{ secret('MIRO_TEAM_ID') }}"
                    interval: PT5M

                tasks:
                  - id: handle_board
                    type: io.kestra.plugin.core.log.Log
                    message: "New board detected: {{ trigger.name }} (id: {{ trigger.id }})"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {

    @Schema(
        title = "Miro OAuth 2.0 access token",
        description = "Use `{{ secret('MIRO_TOKEN') }}` to keep the token out of flow definitions."
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    private Property<String> token;

    @Schema(
        title = "Team ID",
        description = "Restrict the watch to boards in this team."
    )
    @PluginProperty(group = "processing")
    private Property<String> teamId;

    @Schema(
        title = "Project ID",
        description = "Restrict the watch to boards in this project."
    )
    @PluginProperty(group = "processing")
    private Property<String> projectId;

    @Builder.Default
    @Schema(title = "Polling interval", description = "How often to poll the Miro boards list.")
    @PluginProperty(group = "execution")
    private Duration interval = Duration.ofMinutes(5);

    @Schema(title = "HTTP client configuration")
    @PluginProperty(group = "connection")
    private HttpConfiguration options;

    @Builder.Default
    @Schema(
        title = "Page size",
        description = "Number of boards to fetch per poll (1-50). Keep low if the team creates boards frequently."
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> limit = Property.ofValue(20);

    @Builder.Default
    @Schema(hidden = true)
    @JsonIgnore
    private String baseUrl = AbstractMiroConnection.MIRO_API_BASE_URL;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var logger = runContext.logger();

        var rLimit = runContext.render(limit).as(Integer.class).orElse(20);
        if (rLimit < 1 || rLimit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50, got: " + rLimit);
        }

        // Store watermark in namespace KV store with a length-prefixed key
        String flowId = context.getFlowId() != null ? context.getFlowId() : "default";
        String triggerId = context.getTriggerId() != null ? context.getTriggerId() : (this.getId() != null ? this.getId() : "default");
        String stateKey = flowId.length() + "_" + flowId + "_" + triggerId.length() + "_" + triggerId + "_watermark";

        String namespace = context.getNamespace() != null ? context.getNamespace() : "default";
        var kvStore = runContext.namespaceKv(namespace);

        Map<String, Object> watermark = null;
        try {
            var stateEntry = kvStore.getValue(stateKey);
            if (stateEntry.isPresent() && stateEntry.get().getValue() != null) {
                Object val = stateEntry.get().getValue();
                if (val instanceof Map) {
                    watermark = (Map<String, Object>) val;
                } else {
                    watermark = JacksonMapper.ofJson().readValue(val.toString(), new TypeReference<Map<String, Object>>() {});
                }
            }
        } catch (Exception e) {
            logger.debug("No prior watermark found or failed to read: {}", e.getMessage());
        }

        Instant sinceInstant;
        List<String> seenIds = List.of();

        if (watermark != null && watermark.containsKey("lastCreatedAt")) {
            sinceInstant = Instant.parse(watermark.get("lastCreatedAt").toString());
            Object seenObj = watermark.get("seenIds");
            if (seenObj instanceof List<?> list) {
                seenIds = list.stream().map(Object::toString).toList();
            }
        } else {
            // Seed on the first poll: check boards created in the initial interval window
            var nowUtc = context.getDate() != null
                ? context.getDate().withZoneSameInstant(ZoneOffset.UTC)
                : ZonedDateTime.now(ZoneOffset.UTC);
            sinceInstant = nowUtc.minus(interval).toInstant();
        }

        var newBoards = new ArrayList<Map<String, Object>>();
        int currentOffset = 0;
        int maxPages = 10;
        boolean hasMore = true;

        for (int page = 0; page < maxPages && hasMore; page++) {
            var listOutput = io.kestra.plugin.miro.boards.List.builder()
                .id(this.id)
                .type(io.kestra.plugin.miro.boards.List.class.getName())
                .token(token)
                .teamId(teamId)
                .projectId(projectId)
                .sort(Property.ofValue(SortOrder.LAST_CREATED))
                .limit(Property.ofValue(rLimit))
                .offset(Property.ofValue(currentOffset))
                .options(options)
                .baseUrl(baseUrl)
                .build()
                .run(runContext);

            var boards = listOutput.getBoards();
            if (boards == null || boards.isEmpty()) {
                break;
            }

            boolean reachedOlder = false;
            for (var board : boards) {
                var createdAtStr = board.get("createdAt");
                if (createdAtStr != null) {
                    try {
                        var boardInstant = Instant.parse(createdAtStr.toString());
                        var boardId = String.valueOf(board.getOrDefault("id", ""));

                        if (boardInstant.isAfter(sinceInstant)) {
                            newBoards.add(board);
                        } else if (boardInstant.equals(sinceInstant) && !seenIds.contains(boardId)) {
                            newBoards.add(board);
                        } else {
                            reachedOlder = true;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            if (reachedOlder || boards.size() < rLimit) {
                hasMore = false;
            } else {
                currentOffset += rLimit;
            }
        }

        // Advance watermark to the newest board's createdAt and collect IDs sharing that timestamp
        Instant newestCreatedAt = sinceInstant;
        List<String> newestIds = new ArrayList<>(seenIds);

        if (!newBoards.isEmpty()) {
            newestIds.clear();
            for (var b : newBoards) {
                var cStr = b.get("createdAt");
                if (cStr != null) {
                    try {
                        var bInstant = Instant.parse(cStr.toString());
                        var bId = String.valueOf(b.getOrDefault("id", ""));
                        if (bInstant.isAfter(newestCreatedAt)) {
                            newestCreatedAt = bInstant;
                            newestIds.clear();
                            newestIds.add(bId);
                        } else if (bInstant.equals(newestCreatedAt)) {
                            newestIds.add(bId);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } else if (watermark == null) {
            var nowUtc = context.getDate() != null
                ? context.getDate().withZoneSameInstant(ZoneOffset.UTC)
                : ZonedDateTime.now(ZoneOffset.UTC);
            newestCreatedAt = nowUtc.toInstant();
            newestIds.clear();
        }

        // Persist advanced watermark
        try {
            Map<String, Object> newWatermark = Map.of(
                "lastCreatedAt", newestCreatedAt.toString(),
                "seenIds", newestIds
            );
            kvStore.put(stateKey, new KVValueAndMetadata(
                null,
                JacksonMapper.ofJson().writeValueAsString(newWatermark)
            ));
        } catch (Exception e) {
            logger.warn("Failed to persist watermark state in namespace KV: {}", e.getMessage());
        }

        if (newBoards.isEmpty()) {
            logger.debug("No new Miro boards found after watermark {}", sinceInstant);
            return Optional.empty();
        }

        logger.info("Found {} new Miro board(s) since watermark {}", newBoards.size(), sinceInstant);

        var first = newBoards.getFirst();
        var output = Output.builder()
            .id(first.getOrDefault("id", "").toString())
            .name(first.getOrDefault("name", "").toString())
            .createdAt(first.getOrDefault("createdAt", "").toString())
            .boards(newBoards)
            .count(newBoards.size())
            .build();

        return Optional.of(TriggerService.generateExecution(this, conditionContext, context, output));
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(title = "Board ID", description = "ID of the first new board detected.")
        private String id;

        @Schema(title = "Board name", description = "Name of the first new board detected.")
        private String name;

        @Schema(title = "Created at", description = "ISO-8601 creation timestamp of the first new board.")
        private String createdAt;

        @Schema(title = "New boards", description = "All new boards detected in this evaluation cycle.")
        private List<Map<String, Object>> boards;

        @Schema(title = "Count", description = "Number of new boards detected.")
        private Integer count;
    }
}
