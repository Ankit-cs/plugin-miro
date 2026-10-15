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

        // Fire for boards created in the window (pollDate - interval, pollDate].
        // Evaluate against current UTC time so every scheduled poll inspects its active
        // interval window, rather than freezing on context.getDate() from initial deploy.
        var pollDate = ZonedDateTime.now(ZoneOffset.UTC);
        var since = pollDate.minus(interval);

        var rLimit = runContext.render(limit).as(Integer.class).orElse(20);
        if (rLimit < 1 || rLimit > 50) {
            throw new IllegalArgumentException("limit must be between 1 and 50, got: " + rLimit);
        }

        // Reuse the List task instead of rebuilding the HTTP request, auth, and sort.
        // One code path means the connection/sort logic stays in a single place.
        var listOutput = io.kestra.plugin.miro.boards.List.builder()
            .id(this.id)
            .type(io.kestra.plugin.miro.boards.List.class.getName())
            .token(token)
            .teamId(teamId)
            .projectId(projectId)
            .sort(Property.ofValue(SortOrder.LAST_CREATED))
            .limit(limit)
            .options(options)
            .baseUrl(baseUrl)
            .build()
            .run(runContext);

        var newBoards = new ArrayList<Map<String, Object>>();
        if (listOutput.getBoards() != null) {
            for (var board : listOutput.getBoards()) {
                var createdAt = board.get("createdAt");
                if (createdAt != null
                    && isAfter(createdAt.toString(), since)
                    && !isAfter(createdAt.toString(), pollDate)) {
                    newBoards.add(board);
                }
            }
        }

        if (newBoards.isEmpty()) {
            logger.debug("No new Miro boards in window ({}, {}]", since, pollDate);
            return Optional.empty();
        }

        logger.info("Found {} new Miro board(s) since {}", newBoards.size(), since);

        // Emit one execution per trigger evaluation, carrying all new boards
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

    private static boolean isAfter(String iso, ZonedDateTime since) {
        try {
            return Instant.parse(iso).atZone(ZoneOffset.UTC).isAfter(since);
        } catch (Exception e) {
            return false;
        }
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
