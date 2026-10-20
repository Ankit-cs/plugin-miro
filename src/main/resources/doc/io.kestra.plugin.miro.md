# How to use the Miro plugin

Manage the lifecycle of [Miro](https://miro.com/) boards — list, get, create, update, copy, and delete — and react to new boards from Kestra flows.

## Authentication

All tasks and the trigger require `token` (a Miro OAuth 2.0 bearer token, required). Tokens expire after 1 hour; non-expiring tokens are also supported for service accounts. Optionally set `options` for HTTP client configuration. Store the token in [secrets](https://kestra.io/docs/concepts/secret) (e.g. `{{ secret('MIRO_TOKEN') }}`) and set connection properties on each task.

## Tasks

`boards.Create` creates a board — set `name` (required). Optionally set `boardDescription`, `teamId`, `projectId`, `sharingPolicy`, and `permissionsPolicy` (the policy maps follow the Miro board policy schema). The output includes the new board `id`.

`boards.Get` retrieves a board by `boardId` (required), returning its full details.

`boards.Update` updates a board by `boardId` (required) — set at least one of `name`, `boardDescription`, `sharingPolicy`, or `permissionsPolicy`; only the fields you provide are changed.

`boards.Copy` copies a board — set `sourceBoardId` (required). Optionally set `name` (defaults to the source board name), `boardDescription`, and `targetTeamId` (defaults to the source board's team).

`boards.Delete` permanently deletes a board by `boardId` (required). This cannot be undone, and the authenticated user must have owner or administrator rights on the board.

`boards.List` returns a paginated list of accessible boards — all properties are optional. Filter with `teamId`, `projectId`, `query` (matches board name), and `owner` (user ID or email); order with `sort` (`DEFAULT`, `LAST_MODIFIED`, `LAST_OPENED`, `LAST_OPENED_BY_ANYONE`, `LAST_CREATED`, or `ALPHABETICALLY` — Miro has no descending order). Page with `offset` (zero-based) and `limit` (1–50, default `20`); the output includes the `boards` page plus `total`, `size`, and `offset` for paging.

`boards.Trigger` polls the Miro boards list (default `PT5M`) and starts an execution when new boards are created. Set `teamId` or `projectId` (at least one is required) to enable Miro's chronological sorting by creation date (`LAST_CREATED`), and `limit` (page size, 1–50, default `20`). Detection tracks a persistent watermark (`lastCreatedAt` timestamp and tie-breaking IDs) in the namespace KV store, preventing dropped or duplicated boards during downtime, restarts, or scheduler delays. Multi-page offset pagination fetches up to 10 pages per poll; if the 10-page backlog limit is exceeded, the poll fails safely to prevent data loss — delete the watermark key in the namespace KV store to reset and resume polling from the current time.
