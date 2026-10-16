package io.kestra.plugin.miro.boards;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.TestsUtils;
import jakarta.inject.Inject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class BoardsTest {

    @Inject
    private RunContextFactory runContextFactory;

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson(false);

    private WireMockServer wireMock;
    private String baseUrl;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        baseUrl = wireMock.baseUrl();
        configureFor("localhost", wireMock.port());
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    // --- List ---

    @Test
    void list_happyPath_returnsBoards() throws Exception {
        var responseBody = Map.of(
            "data", java.util.List.of(
                boardJson("board-1", "Sprint Retro"),
                boardJson("board-2", "Design Review")
            ),
            "total", 2,
            "size", 2
        );

        wireMock.stubFor(get(urlPathEqualTo("/boards"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(responseBody))
                .withStatus(200)));

        var task = io.kestra.plugin.miro.boards.List.builder()
            .token(Property.ofValue("test-token"))
            .limit(Property.ofValue(20))
            .baseUrl(baseUrl)
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getBoards(), hasSize(2));
        assertThat(output.getBoards().getFirst().get("id"), Matchers.equalTo("board-1"));
        assertThat(output.getSize(), Matchers.equalTo(2));
    }

    @Test
    void list_withFilters_passesQueryParams() throws Exception {
        var responseBody = Map.of(
            "data", java.util.List.of(boardJson("board-3", "Team Board")),
            "total", 1,
            "size", 1
        );

        wireMock.stubFor(get(urlPathEqualTo("/boards"))
            .withQueryParam("team_id", com.github.tomakehurst.wiremock.client.WireMock.equalTo("team-abc"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(responseBody))
                .withStatus(200)));

        var task = io.kestra.plugin.miro.boards.List.builder()
            .token(Property.ofValue("test-token"))
            .teamId(Property.ofValue("team-abc"))
            .baseUrl(baseUrl)
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getBoards(), hasSize(1));
        assertThat(output.getBoards().getFirst().get("name"), Matchers.equalTo("Team Board"));
    }

    @Test
    void list_withOffset_passesParam() throws Exception {
        var responseBody = Map.of(
            "data", java.util.List.of(),
            "total", 20,
            "size", 0,
            "offset", 10
        );

        wireMock.stubFor(get(urlPathEqualTo("/boards"))
            .withQueryParam("offset", com.github.tomakehurst.wiremock.client.WireMock.equalTo("10"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(responseBody))
                .withStatus(200)));

        var task = io.kestra.plugin.miro.boards.List.builder()
            .token(Property.ofValue("test-token"))
            .offset(Property.ofValue(10))
            .baseUrl(baseUrl)
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getBoards(), empty());
        assertThat(output.getOffset(), Matchers.equalTo(10));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/boards"))
            .withQueryParam("offset", com.github.tomakehurst.wiremock.client.WireMock.equalTo("10")));
    }

    // --- Get ---

    @Test
    void get_happyPath_returnsBoard() throws Exception {
        wireMock.stubFor(get(urlEqualTo("/boards/board-xyz"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(boardJson("board-xyz", "My Board")))
                .withStatus(200)));

        var task = Get.builder()
            .token(Property.ofValue("test-token"))
            .boardId(Property.ofValue("board-xyz"))
            .baseUrl(baseUrl)
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getId(), Matchers.equalTo("board-xyz"));
        assertThat(output.getName(), Matchers.equalTo("My Board"));
        assertThat(output.getViewLink(), notNullValue());
    }

    @Test
    void get_missingBoardId_throws() {
        var task = Get.builder()
            .token(Property.ofValue("test-token"))
            .baseUrl(baseUrl)
            .build();

        assertThrows(Exception.class, () -> task.run(runContextFactory.of(Map.of())));
    }

    // --- Create ---

    @Test
    void create_happyPath_returnsNewBoard() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/boards"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(boardJson("new-board", "Sprint 42")))
                .withStatus(201)));

        var task = Create.builder()
            .token(Property.ofValue("test-token"))
            .name(Property.ofValue("Sprint 42"))
            .boardDescription(Property.ofValue("Provisioned by Kestra"))
            .teamId(Property.ofValue("team-abc"))
            .baseUrl(baseUrl)
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getId(), Matchers.equalTo("new-board"));
        assertThat(output.getName(), Matchers.equalTo("Sprint 42"));
        assertThat(output.getViewLink(), notNullValue());
    }

    @Test
    void create_missingName_throws() {
        var task = Create.builder()
            .token(Property.ofValue("test-token"))
            .baseUrl(baseUrl)
            .build();

        assertThrows(Exception.class, () -> task.run(runContextFactory.of(Map.of())));
    }

    // --- Update ---

    @Test
    void update_happyPath_returnsUpdatedBoard() throws Exception {
        wireMock.stubFor(patch(urlEqualTo("/boards/board-xyz"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(boardJson("board-xyz", "Renamed Board")))
                .withStatus(200)));

        var task = Update.builder()
            .token(Property.ofValue("test-token"))
            .boardId(Property.ofValue("board-xyz"))
            .name(Property.ofValue("Renamed Board"))
            .baseUrl(baseUrl)
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getId(), Matchers.equalTo("board-xyz"));
        assertThat(output.getName(), Matchers.equalTo("Renamed Board"));
    }

    @Test
    void update_noFields_throws() {
        var task = Update.builder()
            .token(Property.ofValue("test-token"))
            .boardId(Property.ofValue("board-xyz"))
            .baseUrl(baseUrl)
            .build();

        assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of(Map.of())));
    }

    // --- Delete ---

    @Test
    void delete_happyPath_succeeds() throws Exception {
        wireMock.stubFor(delete(urlEqualTo("/boards/board-to-delete"))
            .willReturn(aResponse()
                .withStatus(204)));

        var task = Delete.builder()
            .token(Property.ofValue("test-token"))
            .boardId(Property.ofValue("board-to-delete"))
            .baseUrl(baseUrl)
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output, nullValue());
        wireMock.verify(1, deleteRequestedFor(urlEqualTo("/boards/board-to-delete")));
    }

    @Test
    void delete_missingBoardId_throws() {
        var task = Delete.builder()
            .token(Property.ofValue("test-token"))
            .baseUrl(baseUrl)
            .build();

        assertThrows(Exception.class, () -> task.run(runContextFactory.of(Map.of())));
    }

    // --- Copy ---

    @Test
    void copy_happyPath_returnsCopiedBoard() throws Exception {
        wireMock.stubFor(put(urlPathEqualTo("/boards"))
            .withQueryParam("copy_from", com.github.tomakehurst.wiremock.client.WireMock.equalTo("source-board"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(boardJson("copied-board", "Sprint 42 (copy)")))
                .withStatus(201)));

        var task = Copy.builder()
            .token(Property.ofValue("test-token"))
            .sourceBoardId(Property.ofValue("source-board"))
            .name(Property.ofValue("Sprint 42 (copy)"))
            .baseUrl(baseUrl)
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getId(), Matchers.equalTo("copied-board"));
        assertThat(output.getName(), Matchers.equalTo("Sprint 42 (copy)"));
    }

    @Test
    void copy_withTargetTeamId_sendsTeamIdInBody() throws Exception {
        wireMock.stubFor(put(urlPathEqualTo("/boards"))
            .withQueryParam("copy_from", com.github.tomakehurst.wiremock.client.WireMock.equalTo("source-board"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(boardJson("copied-board", "Sprint 42 (copy)")))
                .withStatus(201)));

        var task = Copy.builder()
            .token(Property.ofValue("test-token"))
            .sourceBoardId(Property.ofValue("source-board"))
            .targetTeamId(Property.ofValue("team-xyz"))
            .baseUrl(baseUrl)
            .build();

        task.run(runContextFactory.of(Map.of()));

        wireMock.verify(putRequestedFor(urlPathEqualTo("/boards"))
            .withRequestBody(matchingJsonPath("$.teamId", com.github.tomakehurst.wiremock.client.WireMock.equalTo("team-xyz"))));
    }

    @Test
    void copy_missingSourceBoardId_throws() {
        var task = Copy.builder()
            .token(Property.ofValue("test-token"))
            .baseUrl(baseUrl)
            .build();

        assertThrows(Exception.class, () -> task.run(runContextFactory.of(Map.of())));
    }

    // --- Create policy nesting ---

    @Test
    void create_withPolicies_nestedUnderPolicyObject() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/boards"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(boardJson("policy-board", "Policy Board")))
                .withStatus(201)));

        var task = Create.builder()
            .token(Property.ofValue("test-token"))
            .name(Property.ofValue("Policy Board"))
            .sharingPolicy(Property.ofValue(Map.of("access", "private")))
            .permissionsPolicy(Property.ofValue(Map.of("collaborationToolsStartAccess", "all_editors")))
            .baseUrl(baseUrl)
            .build();

        task.run(runContextFactory.of(Map.of()));

        wireMock.verify(postRequestedFor(urlEqualTo("/boards"))
            .withRequestBody(matchingJsonPath("$.policy.sharingPolicy"))
            .withRequestBody(matchingJsonPath("$.policy.permissionsPolicy")));
    }

    // --- List sort param ---

    @Test
    void list_withSort_passesParam() throws Exception {
        var responseBody = Map.of(
            "data", java.util.List.of(boardJson("sorted-board", "Sorted Board")),
            "total", 1,
            "size", 1
        );

        wireMock.stubFor(get(urlPathEqualTo("/boards"))
            .withQueryParam("sort", com.github.tomakehurst.wiremock.client.WireMock.equalTo("last_created"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(responseBody))
                .withStatus(200)));

        var task = io.kestra.plugin.miro.boards.List.builder()
            .token(Property.ofValue("test-token"))
            .sort(Property.ofValue(SortOrder.LAST_CREATED))
            .baseUrl(baseUrl)
            .build();

        var output = task.run(runContextFactory.of(Map.of()));

        assertThat(output.getBoards(), hasSize(1));
        wireMock.verify(getRequestedFor(urlPathEqualTo("/boards"))
            .withQueryParam("sort", com.github.tomakehurst.wiremock.client.WireMock.equalTo("last_created")));
    }

    // --- Error handling ---

    @Test
    void get_404_throwsWithStatusInMessage() {
        wireMock.stubFor(get(urlEqualTo("/boards/no-such-board"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"Board not found\"}")
                .withStatus(404)));

        var task = Get.builder()
            .token(Property.ofValue("test-token"))
            .boardId(Property.ofValue("no-such-board"))
            .baseUrl(baseUrl)
            .build();

        var ex = assertThrows(RuntimeException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(ex.getMessage(), containsString("404"));
    }

    @Test
    void get_401_throwsWithAuthHintInMessage() {
        wireMock.stubFor(get(urlEqualTo("/boards/board-401"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"Unauthorized\"}")
                .withStatus(401)));

        var task = Get.builder()
            .token(Property.ofValue("expired-token"))
            .boardId(Property.ofValue("board-401"))
            .baseUrl(baseUrl)
            .build();

        var ex = assertThrows(RuntimeException.class, () -> task.run(runContextFactory.of(Map.of())));
        assertThat(ex.getMessage(), containsString("401"));
        assertThat(ex.getMessage(), containsStringIgnoringCase("token"));
    }

    // --- Trigger window filtering ---

    @Test
    void trigger_withInWindowBoard_returnsExecution() throws Exception {
        var now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
        // One board created 2 minutes ago (inside a 5-minute window) and one created 10 minutes ago (outside).
        var responseBody = Map.of(
            "data", java.util.List.of(
                boardJsonWithCreatedAt("board-new", "New Board", now.minus(Duration.ofMinutes(2)).toInstant().toString()),
                boardJsonWithCreatedAt("board-old", "Old Board", now.minus(Duration.ofMinutes(10)).toInstant().toString())
            ),
            "total", 2,
            "size", 2
        );

        wireMock.stubFor(get(urlPathEqualTo("/boards"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(responseBody))
                .withStatus(200)));

        var trigger = Trigger.builder()
            .id("test-trigger-" + System.nanoTime())
            .type(Trigger.class.getName())
            .token(Property.ofValue("test-token"))
            .interval(Duration.ofMinutes(5))
            .baseUrl(baseUrl)
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        // Override the date so the window is (now-5m, now].
        var triggerContext = io.kestra.core.models.triggers.Trigger.builder()
            .triggerId(trigger.getId())
            .flowId(context.getValue().getFlowId())
            .namespace(context.getValue().getNamespace())
            .date(now)
            .build();

        Optional<Execution> result = trigger.evaluate(context.getKey(), triggerContext);

        assertThat(result.isPresent(), is(true));
        assertThat(result.get().getTrigger().getVariables().get("count"), is(1));
    }

    @Test
    void trigger_allBoardsOutsideWindow_returnsEmpty() throws Exception {
        var now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
        var responseBody = Map.of(
            "data", java.util.List.of(
                boardJsonWithCreatedAt("board-old", "Old Board", now.minus(Duration.ofMinutes(10)).toInstant().toString())
            ),
            "total", 1,
            "size", 1
        );

        wireMock.stubFor(get(urlPathEqualTo("/boards"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(responseBody))
                .withStatus(200)));

        var trigger = Trigger.builder()
            .id("test-trigger-" + System.nanoTime())
            .type(Trigger.class.getName())
            .token(Property.ofValue("test-token"))
            .interval(Duration.ofMinutes(5))
            .baseUrl(baseUrl)
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        var triggerContext = io.kestra.core.models.triggers.Trigger.builder()
            .triggerId(trigger.getId())
            .flowId(context.getValue().getFlowId())
            .namespace(context.getValue().getNamespace())
            .date(now)
            .build();

        Optional<Execution> result = trigger.evaluate(context.getKey(), triggerContext);

        assertThat(result.isPresent(), is(false));
    }

    @Test
    void trigger_consecutivePolls_detectsNewBoardsOnSecondPoll() throws Exception {
        var now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
        var trigger = Trigger.builder()
            .id("test-trigger-consecutive-" + System.nanoTime())
            .type(Trigger.class.getName())
            .token(Property.ofValue("test-token"))
            .interval(Duration.ofMinutes(5))
            .baseUrl(baseUrl)
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        // 1st poll at slot 'now'
        var triggerContext1 = io.kestra.core.models.triggers.Trigger.builder()
            .triggerId(trigger.getId())
            .flowId(context.getValue().getFlowId())
            .namespace(context.getValue().getNamespace())
            .date(now)
            .build();

        // 1st poll returns board-1
        var firstPollBody = Map.of(
            "data", java.util.List.of(
                boardJsonWithCreatedAt("board-1", "First Board", now.minus(Duration.ofMinutes(1)).toInstant().toString())
            ),
            "total", 1,
            "size", 1
        );
        wireMock.stubFor(get(urlPathEqualTo("/boards"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(firstPollBody))
                .withStatus(200)));

        Optional<Execution> firstResult = trigger.evaluate(context.getKey(), triggerContext1);
        assertThat(firstResult.isPresent(), is(true));
        assertThat(firstResult.get().getTrigger().getVariables().get("id"), Matchers.equalTo("board-1"));

        // 2nd poll: scheduled slot advances realistically by interval (now + 5 minutes)
        var triggerContext2 = io.kestra.core.models.triggers.Trigger.builder()
            .triggerId(trigger.getId())
            .flowId(context.getValue().getFlowId())
            .namespace(context.getValue().getNamespace())
            .date(now.plus(Duration.ofMinutes(5)))
            .build();

        // Miro API returns both board-2 and already-seen board-1
        var secondPollBody = Map.of(
            "data", java.util.List.of(
                boardJsonWithCreatedAt("board-2", "Second Board", now.plus(Duration.ofMinutes(2)).toInstant().toString()),
                boardJsonWithCreatedAt("board-1", "First Board", now.minus(Duration.ofMinutes(1)).toInstant().toString())
            ),
            "total", 2,
            "size", 2
        );
        wireMock.stubFor(get(urlPathEqualTo("/boards"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(secondPollBody))
                .withStatus(200)));

        Optional<Execution> secondResult = trigger.evaluate(context.getKey(), triggerContext2);
        assertThat(secondResult.isPresent(), is(true));
        assertThat(secondResult.get().getTrigger().getVariables().get("id"), Matchers.equalTo("board-2"));
        // Assert board-1 is not delivered twice
        assertThat(secondResult.get().getTrigger().getVariables().get("count"), Matchers.equalTo(1));
    }

    @Test
    void trigger_afterDelayedPollOrRestart_resumesFromWatermark() throws Exception {
        var now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
        var trigger = Trigger.builder()
            .id("test-trigger-delayed-" + System.nanoTime())
            .type(Trigger.class.getName())
            .token(Property.ofValue("test-token"))
            .interval(Duration.ofMinutes(5))
            .baseUrl(baseUrl)
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        var initialContext = io.kestra.core.models.triggers.Trigger.builder()
            .triggerId(trigger.getId())
            .flowId(context.getValue().getFlowId())
            .namespace(context.getValue().getNamespace())
            .date(now)
            .build();

        // 1st poll seeds watermark with board-1
        var firstBody = Map.of(
            "data", java.util.List.of(
                boardJsonWithCreatedAt("board-1", "First Board", now.minus(Duration.ofMinutes(1)).toInstant().toString())
            ),
            "total", 1,
            "size", 1
        );
        wireMock.stubFor(get(urlPathEqualTo("/boards"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(firstBody))
                .withStatus(200)));

        var firstRes = trigger.evaluate(context.getKey(), initialContext);
        assertThat(firstRes.isPresent(), is(true));

        // Delayed poll (e.g. restart after 30 minutes downtime)
        var delayedContext = io.kestra.core.models.triggers.Trigger.builder()
            .triggerId(trigger.getId())
            .flowId(context.getValue().getFlowId())
            .namespace(context.getValue().getNamespace())
            .date(now.plus(Duration.ofMinutes(30)))
            .build();

        var delayedBody = Map.of(
            "data", java.util.List.of(
                boardJsonWithCreatedAt("board-3", "Third Board", now.plus(Duration.ofMinutes(10)).toInstant().toString()),
                boardJsonWithCreatedAt("board-1", "First Board", now.minus(Duration.ofMinutes(1)).toInstant().toString())
            ),
            "total", 2,
            "size", 2
        );
        wireMock.stubFor(get(urlPathEqualTo("/boards"))
            .willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(MAPPER.writeValueAsString(delayedBody))
                .withStatus(200)));

        var delayedRes = trigger.evaluate(context.getKey(), delayedContext);
        assertThat(delayedRes.isPresent(), is(true));
        assertThat(delayedRes.get().getTrigger().getVariables().get("id"), Matchers.equalTo("board-3"));
        assertThat(delayedRes.get().getTrigger().getVariables().get("count"), Matchers.equalTo(1));
    }

    // --- Integration tests (require a real Miro token) ---

    @Test
    @Disabled("Requires MIRO_TOKEN and MIRO_TEAM_ID env vars, run manually")
    void integration_createGetDeleteBoard_roundtrip() throws Exception {
        var token = System.getenv("MIRO_TOKEN");
        var teamId = System.getenv("MIRO_TEAM_ID");
        var runContext = runContextFactory.of(Map.of());

        // Create
        var create = Create.builder()
            .token(Property.ofValue(token))
            .name(Property.ofValue("Kestra IT " + System.currentTimeMillis()))
            .teamId(Property.ofValue(teamId))
            .build();
        var created = create.run(runContext);
        assertThat(created.getId(), notNullValue());

        // Get
        var getTask = Get.builder()
            .token(Property.ofValue(token))
            .boardId(Property.ofValue(created.getId()))
            .build();
        var fetched = getTask.run(runContext);
        assertThat(fetched.getId(), Matchers.equalTo(created.getId()));

        // Delete (cleanup)
        var delete = Delete.builder()
            .token(Property.ofValue(token))
            .boardId(Property.ofValue(created.getId()))
            .build();
        delete.run(runContext);
    }

    // --- Helpers ---

    private static Map<String, Object> boardJson(String id, String name) {
        return boardJsonWithCreatedAt(id, name, "2024-01-01T10:00:00Z");
    }

    private static Map<String, Object> boardJsonWithCreatedAt(String id, String name, String createdAt) {
        return Map.of(
            "id", id,
            "name", name,
            "description", "Test board",
            "type", "board",
            "viewLink", "https://miro.com/app/board/" + id + "/",
            "createdAt", createdAt,
            "modifiedAt", "2024-01-02T10:00:00Z"
        );
    }
}
