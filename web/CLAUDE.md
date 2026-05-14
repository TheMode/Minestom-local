# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

This module (`:web`) is slated to move out of the Minestom monorepo. Treat it as an independent project — avoid coupling new code to anything in `../src` or `../demo` beyond the existing `api(rootProject)` boundary. The exported surface is the `net.minestom.web` package only (see `module-info.java`); `net.minestom.web.internal.*` is implementation detail and `net.minestom.web.cli.*` is the standalone CLI runner — neither is exported, both are free to change.

## Build and run

Gradle is invoked from the repo root via `./gradlew :web:<task>`. Java 25 toolchain.

```
./gradlew :web:build                  # compile + frontend bundle + tests
./gradlew :web:run --args="…"         # launch net.minestom.web.cli.Main with CLI flags (see cli/Main.java USAGE)
./gradlew :web:test                   # JUnit 5
./gradlew :web:test --tests SessionRegistryTest          # single class
./gradlew :web:test --tests SessionRegistryTest.someName # single method
./gradlew :web:buildFrontend          # esbuild → src/main/resources/web/app.js
```

Frontend can be iterated standalone from `web/frontend/`:

```
npm ci          # one-time
npm run watch   # esbuild --watch, writes app.js into resources
npm run check   # tsc --noEmit
```

The Gradle `processResources` step depends on `buildFrontend`, which depends on `installFrontend` (`npm ci`). The frontend bundle lands at `src/main/resources/web/app.js` and is served alongside the static `index.html`/`style.css`. Component-specific styling lives in each component's `<style>` block (esbuild-svelte injects it into `app.js`); the static `style.css` holds only design tokens, base/layout, and the shared primitives reused across many components.

`downloadVanillaAssets` fetches the official Mojang client.jar at build time (verified against `launchermeta.mojang.com` SHA-1), extracts texture/lang/model assets into `build/generated-assets/web/assets/`, and bundles them into resources. Nothing Mojang-owned is committed. Offline builds degrade gracefully — the dashboard falls back to letter-based item icons.

## Architecture

Transparent Minecraft-protocol proxy + in-memory state engine + HTTP/WebSocket dashboard. The same `ProxyServer` supports two modes:

- **Live mode** — owns a TCP proxy on `--bind`, forwards to `--upstream`, decodes packets into `PlayerState`, optionally writes a per-run SQLite archive, and serves one default "live" `DashboardScope`.
- **Replay mode** (`--replay-mode`) — no proxy, no upstream. Each browser tab uploads a `sessions.sqlite` via `POST /api/replay`; the server spins up an isolated `DashboardScope` for that scope id (private registry, private subscribers) and the `ReplaySource` drives it from the file.

### The three layers

```
TCP proxy  ──packets──▶  SessionRegistry  ──events──▶  DashboardServer (HTTP/WS)
   ▲                          │
   │                          ▼
PersistentHistory ◀──writes── (Session = actor per connection)
   │
   ▼
ReplaySource ──re-emits packets──▶ SessionRegistry  (replay mode)
```

- `internal/proxy/` — `TcpAcceptor` accepts connections; each one gets a virtual-thread `ConnectionWorker` that owns its `SocketChannel`s, `Selector`, ciphers, and the `Session`'s mutation thread. Decoded packets apply to state on the same iteration they were read.
- `internal/codec/` — `PacketDecoder` parses raw Minecraft frames (decompress, decrypt, dispatch by id+state); blocking login-handshake I/O is split out into `internal/proxy/LoginIo`. The dashboard wire is split three ways: `WebCodecs` holds the `StructCodec` registry, `WebPayloads` the wire-DTO records, and `WebJsonBuilders` the procedural JSON builders (`playerStateJson`, …).
- `internal/session/` — `Session` is a transport-agnostic actor: one owner thread, a `stateTasks` mailbox, a `PlayerState`, a packet ring buffer (`PacketTimeline`), a `LifecycleHistory`. External threads must enqueue work, never mutate directly. `SessionRegistry` holds live sessions + retained snapshots of disconnected players + the routine/action catalogue. `ActionRunner` (executes registered actions — inject packet, send chat, set custom field, … — against a `Session`) lives here.
- `internal/state/` — packet-class → updater dispatch (`StateApplier` + `*Updaters`). Updaters mutate `PlayerState` and call `markDirty` on changed paths; `Session` drains dirty paths into JSON patches on a cadence tick.
- `internal/scope/` — `DashboardScope` is the unit of isolation: one `SessionRegistry`, one `ControlBridge`, one set of WS subscribers, one `QueryEngine`/`ExpressionEngine`. Live mode has exactly one (`live`); replay mode has one per uploaded file.
- `internal/http/` — `DashboardServer` is the Javalin app. Every REST request and WS connection resolves to a scope: `X-Replay-Id` header (REST) or `?replay=` query (WS); missing → default = `live`. `Topics.java` is the central catalog of WS topic names and **must stay in sync with `web/frontend/src/lib/topics.ts`** — adding one without the other is a bug.
- `internal/expression/` — MQL: a small expression language (`player.health > 10`, `distance(pos, (0,64,0))`, etc.) used by Query and Routine actions. The boolean/comparison host parser (`QueryEngine`) sits here alongside the value grammar (`ValueParser`/`Lexer`/`Expr`). Builtins live in `Builtins.java`; player fields enumerated in `ExpressionEngine.FIELDS`.
- `internal/persist/` — `PersistentHistory` is the SQLite writer: append-only, raw I/O frames are the source of truth, batched per 100ms or 256 rows. Files are stamped with `MinecraftServer.PROTOCOL_VERSION`; readers refuse files with a mismatched version (a v770 capture is undecodable by a v775 codec).
- `internal/replay/` — `ReplaySource` reads the SQLite archive and re-emits packets through a `SessionRegistry`, optionally respecting original timestamps.
- `internal/renderer/` — server-side icon rasterizers (items, blocks, minimap, entity heads). Pulls `java.awt`; `ProxyServer` static-initializes `java.awt.headless=true` before any AWT class loads.

### Frontend (`web/frontend/`)

Svelte 5 (`$state`, runes mode) + TypeScript, bundled by esbuild into one ESM `app.js`. No router framework — `state/route.svelte.ts` is a tiny hash router; views live in `src/views/`, shared reactive stores in `src/state/*.svelte.ts`, and components are grouped under `src/components/` into `ui/` (primitives), `profile/` (player-detail panels), `overlay/` (singleton `*Host` overlays), `mctext/` (chat/component text), `packets/` (packet data-viz), `editors/`, and `packet-trace/`.

- `lib/api.ts` is the HTTP/WS singleton. Auth token goes in `X-Auth-Token`; scope id goes in `X-Replay-Id` (REST) / `?replay=` (WS). Both are read from `sessionStorage` (not local) so each tab can hold its own replay scope.
- `state/mode.svelte.ts` resolves mode + scope on boot; in replay mode without an attached scope, the landing view is a drop zone and the bus stays disconnected until `uploadReplay()` flips `mode.scope`.
- Topic names: `lib/topics.ts` — keep aligned with backend `Topics.java`.

## Conventions

- **Doc style.** JEP 467 markdown comments (`///`) only — never legacy `/** … */`. See `net/minestom/web/package-info.java`.
- **Module boundary.** `module-info.java` exports only `net.minestom.web`. New public types belong in that package; helpers go under `internal.*`. Don't widen exports without a real consumer need.
- **Threading.** `Session` state is owned by one thread. Anything outside that thread must go through the mailbox (`runOnOwner`, `submitState`, etc.) or accept that it's reading `volatile` fields only. Don't mutate `PlayerState` from HTTP handlers.
- **No env vars / no config files.** `Main` reads CLI flags only — keep it that way so behavior is reproducible from the command line.
- **SLF4J logging.** Don't add a binding to `implementation` — only `runtimeOnly` for `:web:run`. Library consumers bring their own binding.

## Tests

JUnit 5, `useJUnitPlatform()`. Test sources in `src/test/java/net/minestom/web/`. Tests run with `-Dminestom.viewable-packet=false` and `-Dminestom.inside-test=true` (configured by the shared `minestom.java-library` convention plugin in `../build-src/`). `testImplementation(project(":testing"))` gives access to Minestom's `:testing` helpers.
