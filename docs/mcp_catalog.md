# MCP catalog and schema-driven install

## Catalog scope

Document MCP catalog source/sync behavior and schema-driven installation flow.

## When to read

- When changing catalog fetch/cache behavior.
- When changing install planner profile selection or generated forms.
- When changing catalog-driven install/uninstall UX behavior.

## Source-of-truth files

- `server-registry/src/commonMain/kotlin/io/qent/broxy/registry/catalog/CatalogInstallPlanner.kt`
- `server-registry/src/jvmMain/kotlin/io/qent/broxy/registry/data/GithubCatalogRepository.kt`
- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/catalog/CatalogInstallPlanner.kt`
- `ui/src/commonMain/kotlin/io/qent/broxy/ui/screens/CatalogScreen.kt`

## Behavior contract

Catalog install supports one-click or schema-driven input collection depending on required missing values.

## Purpose

Broxy ships a public MCP server catalog and installs entries using the draft `server.schema.json` shape
(`packages` / `remotes`) instead of a custom install block.

Catalog install is separate from manual server editing:

- manual add/edit keeps the existing `ServerEditor` flow;
- catalog install uses conditional install modes (one-click or schema-driven form).

## Data source and bundling

Source registry:

- GitHub repo: `qent/broxy-registry`
- files: `index.json` + `servers/*.json`

Build-time bundling:

- task: `:server-registry:generateBundledCatalog`
- file: `server-registry/build.gradle.kts`
- output resource: `catalog/catalog_bundle.json`
- if GitHub fetch fails, the task falls back to `server-registry/catalog-seed/catalog_bundle.json`

Seed catalog currently includes a core icon/install subset used by rule-based server icon resolution:

- `io.qent.broxy/brave`
- `io.qent.broxy/context7`
- `io.qent.broxy/exa`
- `io.qent.broxy/github`
- `io.qent.broxy/intellij-idea-ce`
- `io.qent.broxy/notion`
- `io.qent.broxy/time`

## Runtime sync and cache

JVM catalog repository:

- file: `server-registry/src/jvmMain/kotlin/io/qent/broxy/registry/data/GithubCatalogRepository.kt`
- implementation: `GithubCatalogRepository`
- wiring into UI runtime:
  - `ui-adapter/src/jvmMain/kotlin/io/qent/broxy/ui/adapter/data/RepositoriesJvm.kt`
  - `provideCatalogRepository()` delegates to `GithubCatalogRepository(cacheDir = .../catalog)`

Behavior:

- loads from local cache when available;
- falls back to bundled resource when cache is missing;
- on startup, `AppStore.start()` triggers async refresh check;
- startup refresh first sends `HEAD` to `index.json` and reads `Last-Modified`;
- `index.json` + `servers/*.json` are downloaded only when `Last-Modified` changed (or local cache is missing);
- cache update is atomic (`*.tmp` + move);
- cached bundle stores the last seen remote `Last-Modified` value in `updatedAtEpochMillis`.

Cache location:

- `${AppCacheDir}/catalog/catalog_bundle.json`

## Supported install profiles

Parsed models are in:

- `server-registry/src/commonMain/kotlin/io/qent/broxy/registry/catalog/CatalogSchema.kt`
- `server-registry/src/commonMain/kotlin/io/qent/broxy/registry/catalog/CatalogInstallPlanner.kt`
- `server-registry/src/commonMain/kotlin/io/qent/broxy/registry/catalog/CatalogUiModels.kt`

UI compatibility facade remains in:

- `ui-adapter/src/commonMain/kotlin/io/qent/broxy/ui/adapter/catalog/`
- this package keeps stable imports for `ui` and delegates planner calls to `server-registry`

Supported install profiles:

- `remotes` with type `streamable-http`
- `remotes` with type `sse`
- `packages` with `transport.type=stdio` and non-empty `runtimeHint`

Auto-selection priority for Install:

1. `streamable-http`
2. `sse`
3. `stdio` package

## Install modes

Catalog install UI:

- screen: `ui/src/commonMain/kotlin/io/qent/broxy/ui/screens/CatalogScreen.kt`

- `Install` runs in two modes:
  - one-click install when all required fields are already satisfied by defaults/spec values and
    `_meta.install_steps` is empty/absent;
  - install form when required user input is missing, or when `_meta.install_steps` is non-empty.
- when install completes (one-click or form submit), the UI redirects to the Servers list.
- a newly installed catalog server is inserted at the top of the Servers list (persisted order in `mcp.json`).
- after redirect, the Servers list clears the local search query, scrolls immediately to the installed
  server card, and consumes the one-shot focus signal to avoid repeated auto-scrolls.
- the same top-insert + focus-scroll behavior is reused for other newly created servers saved from the
  regular Server Editor flow.
- one-click install starts the normal capabilities/auth flow in the background (including OAuth popup for
  remote HTTP/SSE/Streamable servers).

### Agentic install via `__preset_management__`

When Preset Management Agentic Mode (`🤘`) is enabled, MCP clients can trigger catalog one-click install
through preset-management tools:

- `list_catalog_server_names` - catalog IDs/names.
- `install_catalog_server(server_id)` - asks for explicit user approval in Broxy UI popup
  (reuse of the authorization popup pattern, with icon/name/description and `Allow` / `Deny`).
  When this popup appears, desktop UI automatically switches to the Servers screen and
  renders the popup above it; after `Allow`/`Deny`, UI stays on Servers.
- `get_catalog_server_install_status(server_id)` - status by catalog `server_id` (no `installation_id`).
- `set_server_enabled(server_id, enabled)` - enable/disable installed server without removing it.

Status mapping is derived from runtime/config state by catalog `server_id`:

- server missing in `mcp.json` -> `not_installed`;
- server present in `mcp.json` but no capabilities yet -> `installing`;
- server has capabilities (live/cached) -> `installed`.

## Install steps metadata

Catalog entries may provide optional setup instructions in `_meta.install_steps`:

```json
{
  "_meta": {
    "install_steps": [
      "Open **GitHub** [Developer Settings](https://github.com/settings/apps).",
      "Use created token below [Authorization]"
    ]
  }
}
```

Contract:

- `_meta.install_steps?: string[]` (top-level key inside `_meta`);
- empty/absent list keeps legacy install flow;
- non-empty list enables step-driven install UI.

Step markdown subset:

- `**bold**`;
- `*italic*`;
- external links `[label](https://...)` (opened in browser; `http/https` only);
- field references `[FieldName]` (resolved to generated install fields by normalized `label`/`id` tokens).
- bare field-name lines are also supported (when a step text normalizes to a field label/token).

Field embedding rules:

- in step mode, Broxy renders only fields referenced in steps (bracket form or bare field-name line);
- matched field references are removed from rendered step markdown (the input card is shown instead);
- if a required field is not referenced by any step, Broxy appends an automatic fallback step at the end
  (`Provide **<field>**.`) and renders that field there;
- schema form controls and validation are unchanged (`isRequired`, `isSecret`, defaults, etc.).

## Schema-driven install form

Form behavior:

- fields are generated from server JSON (`variables`, `headers`, `oauth`, `environmentVariables`,
  `runtimeArguments`, `packageArguments`, including nested variables);
- remote OAuth fields are template-resolved via `remotes[].variables` as well (including `callbackPort`,
  which is validated as integer after template resolution);
- registry-provided remote OAuth overrides (`authServerMetadataUrl`, `authorizationServer`) are propagated
  to installed server config and used by Broxy OAuth discovery;
- only fields that can be provided by the user are shown;
- fixed spec values are not rendered as inputs (for example, transport type and fixed server ID);
- control types follow schema hints (`format`, `choices`, `isSecret`, `isRequired`, `default`, `placeholder`);
- form header uses `Connect to <icon> <server title>` for the selected catalog entry;
- the form header adds a website icon after the server title when catalog metadata provides
  `websiteUrl` or `repository.url` (`websiteUrl` has priority): remote profiles (`HTTP`/`SSE`) use
  a globe icon, while `STDIO` profiles use the external-link icon;
- hovering the form-header website icon shows a bottom overlay bar with semi-transparent
  background and the target URL text;
- form body renders the catalog server description as plain text between the header and generated inputs (no card);
- when `_meta.install_steps` is non-empty, the form body first renders numbered setup steps with markdown;
- server description and numbered steps are left-aligned with field cards, with extra vertical spacing around the
  description and between steps;
- description and step text use larger body typography for readability;
- external links in steps are not underlined by default and become underlined on hover;
- step instruction text is selectable/copyable directly in the install form;
- input field cards reuse Settings-style layout (`SettingsLikeItem`) with a wider single-line control (`280dp`);
- card title uses the schema/input name (with `*` suffix for required fields), while card description uses
  only schema `description`;
- input controls include a paste-from-clipboard icon on the right with standart cursor on hover;
  `isSecret` fields stay masked;
- no generic raw multiline `env`/`headers` inputs;
- in step mode, plain full-field listing is hidden (only step-embedded + required fallback fields are shown).

Submit builds `UiServerDraft` (including optional OAuth auth draft for remote profiles) through
`ui-adapter` planner facade (`CatalogInstallPlanner.buildInstallResult(...)`) that delegates into
`server-registry`, using form values, installs the server, and navigates to the Servers list with
that new server shown first.
Required fields block submit.

## Catalog UI

Navigation adds a dedicated `Registry` section (backed by the Catalog screen).
The `Registry` item is the first navigation entry, while app startup still opens `MCP` by default.

Catalog list behavior:

- card click is no-op;
- cards are rendered in two columns with equal-width tiles;
- each card shows icon, title, and description (the fixed server ID is not shown in card content);
- cards show a website icon next to the title when `websiteUrl` or `repository.url` is available
  (`websiteUrl` first, then `repository.url`): remote cards (`HTTP`/`SSE`) use a globe icon, while
  `STDIO` cards use the external-link icon;
- hovering catalog-card/header website icons shows the same bottom overlay bar with the target URL text;
- description is always rendered with fixed height of 3 lines (short text keeps reserved space);
- install action is a compact top-right control:
  - icon-only `+` control (without filled background); on hover it shows a thin outline;
  - `✓` indicator when installed;
  - on hover over `✓`, show an icon-only `-` control (without filled background) to uninstall; the `-` control keeps the same thin outline style;
  - action control is nudged slightly right/up to align visually with the icon/title header line;
  - for `STDIO` package cards, Broxy checks runtime binary availability by normalized binary name
    (shared cache across cards that use the same binary, for example `uvx`).
  - check state is optimistic by default: before the check completes (or if the check fails), the card is treated as available.
  - when the runtime binary is unavailable, card content is rendered at `alpha=0.5`.
  - when unavailable + not installed, the `+` control is replaced with badge
    `Install <binary_name>`, where `<binary_name>` is bold.
  - the missing-binary badge text switches to `primary` on hover.
  - if `binary -> install URL` mapping exists, the badge is clickable, opens that URL in browser,
    and hovering it shows the same bottom overlay bar with the target URL text.
  - if the mapping is missing, the badge stays non-clickable and only keeps the hover color change.
  - when unavailable + installed, the installed `✓`/hover `-` uninstall control is preserved.
- actions:
  - `Install` when `server.name` is not present in current `mcp.json` server IDs;
  - `Uninstall` when it is present.
- search field is pinned to the bottom (floating above content), matching other list screens, and is hidden
  when the catalog source list is empty;
- search matches `title`, `server ID`, and `description`.

Catalog binary install URL mapping resource:

- `ui-adapter/src/commonMain/resources/catalog_binary_install_urls.json`
- keys are normalized binary names (lowercase).

`Uninstall` first shows the same delete confirmation dialog used in the Servers list, then removes the server
by exact fixed ID (`server.name`) via the normal server removal flow.

## Registry icons for installed servers

Catalog icon URLs (`icons[].src`) are also used for server auto-icon detection in the Servers list/editor:

- icon matching is rule-driven via `server_icons.json`, where each rule maps to a registry server ID;
- resolved icons are rendered as `UiServerIcon.Remote`;
- matching uses normalized registry IDs (for example, `io.qent.broxy/context7` -> `context7`);
- registry cards and server auto-icons share the same disk cache at `${AppCacheDir}/server-icons/`.
