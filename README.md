# Where Did This `<div>` Come From?

A bidirectional source inspector for server-rendered Hiccup, shown at **Clojure/conj 2026**.

The demo is a small recipe site with no JavaScript framework, build step, or network assets.

- **Element → code**: press `Alt+Shift+I`, hover an element, then click to open its exact `file:line:col` in your editor.
- **Code → element**: place your cursor on a view function and the matching browser element is highlighted, including specific call sites.

The inspector is about 800 lines, roughly half browser/editor integration. The core Clojure implementation is only a few dozen lines and uses `tools.reader` to preserve source positions on Hiccup literals.

## Run

```clojure
clojure -M:dev:nrepl
# or:
clojure -M:dev -m demo.main

(start!)   # http://localhost:8080
           # also: stop!, restart!
```

Use another port with:

```sh
PORT=8090 clojure -M:dev -m demo.main
```

The socket origin allowlist follows `$PORT`.

`src/` contains the application and runs without any dev tooling:

```sh
clojure -M -m demo.main
```

`dev/` is available only through the `:dev` alias:

- `dev.socket` — WebSocket hub
- `dev.watcher` — file watcher
- `dev.editor` — editor relay
- `dev.core` — middleware and dev routes
- `dev.inspector` — inspector
- `user.clj` — REPL helpers

The app exposes one hook: `demo.views/*render-boundary*`, which defaults to `identity`.

In development, `dev.core/wrap-dev` binds it to the source tagger and adds `/dev/*` routes. In production, the dev namespace is not on the classpath.

For **click → editor**, either have the `code` CLI on your `PATH` or use VS Code with [Joyride](https://github.com/BetterThanTomorrow/joyride).

The Joyride agent in `.joyride/scripts/workspace_activate.cljs` also provides **cursor → browser** highlighting.

## Talk steps

| branch | adds |
| --- | --- |
| `step-0` | basic Hiccup webserver and tag filtering |
| `step-1` | live reload and dev middleware |
| `step-2` | `tr-load!` with source metadata from `tools.reader` |
| `step-3` | `data-src` tagging via `tag-tree` |
| `step-4` | hover overlay and click-to-editor |
| `step-5` | view-function instrumentation |
| `step-6` | cursor-to-browser highlighting |
| `step-7` | call-site tagging |

Jump to any step with:

```sh
git switch step-N
```

The branches form a linear stack ending at `main`.

To rewrite earlier steps while moving later branch refs automatically:

```sh
git config rebase.updateRefs true
git rebase -i --root
```

## Beyond the demo

The production-oriented version adds morphing reloads, reconnect backoff, stale-page handling, and transform fallbacks.

See *Parens to Production*:

- https://mapidentity.github.io/parens-to-production/
- https://github.com/mapidentity/parens-to-production

## Known limitations

- Call-site detection skips quoted and threaded forms and does not catch higher-order uses such as `(map recipe-card rs)`.
- View names used as `case` test constants can be wrapped incorrectly.
- Only `defn` and `defn-` views are indexed and instrumented.
- Syntax-quoted macro output loses source-position metadata.
- The dev WebSocket validates `Origin`; proxy setups may need changes to `origin-ok?`.
- The Joyride path handling needs normalization on Windows.

## Important

Load views through `tr-load!`.

Using `load-file` or editor eval-on-save reloads them with the default reader and removes the source metadata the inspector depends on.

## License

MIT — see [LICENSE](LICENSE).
