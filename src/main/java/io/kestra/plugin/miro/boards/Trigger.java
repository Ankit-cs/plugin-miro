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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger a flow when new Miro boards are created",
    description = """
        Polls the Miro boards list and fires an execution when new boards are created.
        A watermark tracking the latest board creation timestamp and processed board IDs is persisted in the namespace KV store, ensuring no boards are missed during downtime, restarts, or scheduler delays.
        On the initial poll, boards created within the last configured interval are evaluated and the watermark is seeded.
        Paging retrieves up to 10 pages per poll. When neither `teamId` nor `projectId` is provided, Miro does not support sorting by creation date, so all pages are inspected."""
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

        var flowId = context.getFlowId() != null ? context.getFlowId() : "default";
        var triggerId = context.getTriggerId() != null ? context.getTriggerId() : (this.getId() != null ? this.getId() : "default");
        var stateKey = flowId.length() + "_" + flowId + "_" + triggerId.length() + "_" + triggerId + "_watermark";

        var namespace = context.getNamespace() != null ? context.getNamespace() : "default";
        var kvStore = runContext.namespaceKv(namespace);

        Optional<io.kestra.core.storages.kv.KVValue> stateEntry;
        try {
            stateEntry = kvStore.getValue(stateKey);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read watermark state from namespace KV store for key: " + stateKey, e);
        }

        Map<String, Object> watermark = null;
        if (stateEntry.isPresent() && stateEntry.get().value() != null) {
            try {
                var val = stateEntry.get().value();
                if (val instanceof Map<?, ?> map) {
                    watermark = new HashMap<>();
                    for (var entry : map.entrySet()) {
                        watermark.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                } else {
                    watermark = JacksonMapper.ofJson().readValue(val.toString(), new TypeReference<Map<String, Object>>() {});
                }
                if (watermark == null || !watermark.containsKey("lastCreatedAt")) {
                    throw new IllegalStateException("Corrupted watermark: missing 'lastCreatedAt'");
                }
                Instant.parse(watermark.get("lastCreatedAt").toString());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize or validate watermark state from namespace KV store for key: " + stateKey, e);
            }
        }

        Instant sinceInstant;
        var seenIds = new HashSet<String>();

        if (watermark != null) {
            sinceInstant = Instant.parse(watermark.get("lastCreatedAt").toString());
            var seenObj = watermark.get("seenIds");
            if (seenObj instanceof List<?> list) {
                for (var item : list) {
                    if (item != null) {
                        seenIds.add(item.toString());
                    }
                }
            }
        } else {
            var nowUtc = context.getDate() != null
                ? context.getDate().withZoneSameInstant(ZoneOffset.UTC)
                : ZonedDateTime.now(ZoneOffset.UTC);
            sinceInstant = nowUtc.minus(interval).toInstant();
        }

        var rTeamId = runContext.render(teamId).as(String.class).orElse(null);
        var rProjectId = runContext.render(projectId).as(String.class).orElse(null);
        var isSorted = (rTeamId != null && !rTeamId.isBlank()) || (rProjectId != null && !rProjectId.isBlank());

        var newBoards = new ArrayList<Map<String, Object>>();
        var collectedBoardIds = new HashSet<String>();
        var currentOffset = 0;
        var maxPages = 10;
        var hasMore = true;
        var hitPageCap = false;

        for (var page = 0; page < maxPages && hasMore; page++) {
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

            var reachedOlder = false;
            for (var board : boards) {
                var createdAtStr = board.get("createdAt");
                var boardId = String.valueOf(board.getOrDefault("id", ""));

                if (createdAtStr != null && !boardId.isBlank()) {
                    try {
                        var boardInstant = Instant.parse(createdAtStr.toString());
                        var isNew = boardInstant.isAfter(sinceInstant)
                            || (boardInstant.equals(sinceInstant) && !seenIds.contains(boardId));

                        if (isNew) {
                            if (collectedBoardIds.add(boardId)) {
                                newBoards.add(board);
                            }
                        } else if (isSorted) {
                            reachedOlder = true;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            if ((isSorted && reachedOlder) || boards.size() < rLimit) {
                hasMore = false;
            } else {
                currentOffset += rLimit;
                if (page == maxPages - 1) {
                    hitPageCap = true;
                }
            }
        }

        if (hitPageCap) {
            logger.warn("Reached maximum page limit ({}) during Miro board polling; some boards may remain unread", maxPages);
            throw new IllegalStateException("Exceeded maximum page limit (" + maxPages + ") during Miro board polling. Increase limit or narrow query with teamId/projectId to prevent data loss.");
        }

        var newestCreatedAt = sinceInstant;
        var newestIds = new LinkedHashSet<String>(seenIds);

        if (!newBoards.isEmpty()) {
            for (var b : newBoards) {
                var cStr = b.get("createdAt");
                var bId = String.valueOf(b.getOrDefault("id", ""));
                if (cStr != null && !bId.isBlank()) {
                    try {
                        var bInstant = Instant.parse(cStr.toString());
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

        try {
            Map<String, Object> newWatermark = Map.of(
                "lastCreatedAt", newestCreatedAt.toString(),
                "seenIds", new ArrayList<>(newestIds)
            );
            kvStore.put(stateKey, new KVValueAndMetadata(
                null,
                JacksonMapper.ofJson().writeValueAsString(newWatermark)
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist watermark state in namespace KV store for key: " + stateKey, e);
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
