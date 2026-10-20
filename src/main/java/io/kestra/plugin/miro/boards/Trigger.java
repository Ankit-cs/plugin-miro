package io.kestra.plugin.miro.boards;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
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
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
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
        Either `teamId` or `projectId` must be specified to enable chronological sorting of boards by Miro.
        A watermark tracking the latest board creation timestamp and processed board IDs is persisted in the namespace KV store under key `<flowId.length>_<flowId>_<triggerId.length>_<triggerId>_watermark`, ensuring no boards are missed during downtime, restarts, or scheduler delays.
        On the initial poll, boards created within the last configured interval are evaluated and the watermark is seeded.
        Paging retrieves up to 10 pages per poll (offset pagination). If the 10-page cap is exceeded due to a large backlog, the poll fails to avoid data loss; to unblock and resume polling from the current time, delete the watermark key in the namespace KV store."""
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

    private static final int MAX_PAGES = 10;

    @Schema(
        title = "Miro OAuth 2.0 access token",
        description = "Use `{{ secret('MIRO_TOKEN') }}` to keep the token out of flow definitions."
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    private Property<String> token;

    @Schema(
        title = "Team ID",
        description = "Restrict the watch to boards in this team. At least one of `teamId` or `projectId` must be specified."
    )
    @PluginProperty(group = "processing")
    private Property<String> teamId;

    @Schema(
        title = "Project ID",
        description = "Restrict the watch to boards in this project. At least one of `teamId` or `projectId` must be specified."
    )
    @PluginProperty(group = "processing")
    private Property<String> projectId;

    @JsonIgnore
    @AssertTrue(message = "Set 'teamId' or 'projectId': Miro only sorts boards by creation date within a team or project")
    public boolean isTeamOrProjectSet() {
        return teamId != null || projectId != null;
    }

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
        description = "Number of boards to fetch per page (1-50). Default is 20."
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

        var rTeamId = runContext.render(teamId).as(String.class).orElse(null);
        var rProjectId = runContext.render(projectId).as(String.class).orElse(null);
        if ((rTeamId == null || rTeamId.isBlank()) && (rProjectId == null || rProjectId.isBlank())) {
            throw new IllegalArgumentException("Either 'teamId' or 'projectId' must be specified for the Miro board trigger.");
        }

        var rLimit = runContext.render(limit).as(Integer.class).orElse(20);
        if (rLimit < 1 || rLimit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50, got: " + rLimit);
        }

        var nowUtc = context.getDate() != null
            ? context.getDate().withZoneSameInstant(ZoneOffset.UTC)
            : ZonedDateTime.now(ZoneOffset.UTC);

        var flowId = context.getFlowId() != null ? context.getFlowId() : "default";
        var triggerId = context.getTriggerId() != null ? context.getTriggerId() : (this.getId() != null ? this.getId() : "default");
        var stateKey = flowId.length() + "_" + flowId + "_" + triggerId.length() + "_" + triggerId + "_watermark";

        var namespace = context.getNamespace() != null ? context.getNamespace() : "default";
        var kvStore = runContext.namespaceKv(namespace);

        var watermark = readWatermark(kvStore, stateKey);
        var sinceInstant = watermark != null ? watermark.lastCreatedAt() : nowUtc.minus(interval).toInstant();
        var seenIds = watermark != null ? watermark.seenIds() : Set.<String>of();

        var newBoards = fetchNewBoards(runContext, rLimit, sinceInstant, seenIds, stateKey);

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
            newestCreatedAt = nowUtc.toInstant();
            newestIds.clear();
        }

        writeWatermark(kvStore, stateKey, newestCreatedAt, newestIds);

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

    private Watermark readWatermark(KVStore kvStore, String stateKey) {
        Optional<KVValue> stateEntry;
        try {
            stateEntry = kvStore.getValue(stateKey);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read watermark state from namespace KV store for key: " + stateKey, e);
        }

        if (stateEntry.isEmpty() || stateEntry.get().value() == null) {
            return null;
        }

        try {
            Map<String, Object> watermarkMap;
            var val = stateEntry.get().value();
            if (val instanceof Map<?, ?> map) {
                watermarkMap = new HashMap<>();
                for (var entry : map.entrySet()) {
                    watermarkMap.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else {
                watermarkMap = JacksonMapper.ofJson().readValue(val.toString(), new TypeReference<Map<String, Object>>() {});
            }
            if (watermarkMap == null || !watermarkMap.containsKey("lastCreatedAt")) {
                throw new IllegalStateException("Corrupted watermark: missing 'lastCreatedAt'");
            }
            var lastCreatedAt = Instant.parse(watermarkMap.get("lastCreatedAt").toString());
            var seenIds = new HashSet<String>();
            var seenObj = watermarkMap.get("seenIds");
            if (seenObj instanceof List<?> list) {
                for (var item : list) {
                    if (item != null) {
                        seenIds.add(item.toString());
                    }
                }
            }
            return new Watermark(lastCreatedAt, seenIds);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize or validate watermark state from namespace KV store for key: " + stateKey, e);
        }
    }

    private List<Map<String, Object>> fetchNewBoards(
        RunContext runContext,
        int rLimit,
        Instant sinceInstant,
        Set<String> seenIds,
        String stateKey
    ) throws Exception {
        var logger = runContext.logger();
        var newBoards = new ArrayList<Map<String, Object>>();
        var collectedBoardIds = new HashSet<String>();
        var currentOffset = 0;
        var hasMore = true;
        var hitPageCap = false;

        for (var page = 0; page < MAX_PAGES && hasMore; page++) {
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
                        } else {
                            reachedOlder = true;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            var total = listOutput.getTotal();
            if (reachedOlder || boards.size() < rLimit || (total != null && currentOffset + boards.size() >= total)) {
                hasMore = false;
            } else {
                currentOffset += rLimit;
                if (page == MAX_PAGES - 1) {
                    hitPageCap = true;
                }
            }
        }

        if (hitPageCap) {
            logger.warn("Reached maximum page limit ({}) during Miro board polling; some boards may remain unread", MAX_PAGES);
            throw new IllegalStateException("Exceeded maximum page limit (" + MAX_PAGES + ") during Miro board polling. "
                + "To unblock and resume polling from the current time, delete the KV watermark key '" + stateKey + "' in the namespace KV store.");
        }

        return newBoards;
    }

    private void writeWatermark(KVStore kvStore, String stateKey, Instant newestCreatedAt, Set<String> newestIds) {
        try {
            var newWatermark = Map.<String, Object>of(
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
    }

    private record Watermark(Instant lastCreatedAt, Set<String> seenIds) {}

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
