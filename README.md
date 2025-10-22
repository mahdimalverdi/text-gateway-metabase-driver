# Text Gateway Metabase Driver (Correct packaging)

This repo builds a **Metabase plugin** as a **shaded jar** that contains your driver code and
its runtime deps (cheshire, clj-http) but **does not** include `org.clojure:clojure` nor `metabase-core`.
Metabase supplies the Clojure runtime and its own classes at runtime.

## Build
```bash
mvn -q -DskipTests package
# Artifacts:
#   target/text-gateway-0.1.1-shaded.jar
#   target/text-gateway.metabase-driver.jar  ← copy this to Metabase /plugins
```

## Install
1. Copy `target/text-gateway.metabase-driver.jar` to Metabase `/plugins`.
2. Restart Metabase.
3. Add database → **Text Gateway** → set `base_url` and optional `api_key`.
4. Create a **Native query** and type text; driver POSTs it to `{base_url}/run` with body `{"text":"..."}`.

## Why this packaging?
- No `metabase-core` dependency: avoids resolution errors and version drift.
- Shaded JAR: includes your runtime libs (cheshire, clj-http) so the plugin is self-contained.
- Leans on Metabase's Clojure runtime.

## Contract
- GET `{base_url}/health` → 2xx (for `can-connect?`).
- POST `{base_url}/run` with `{"text":"<editor text>"}` → respond
  ```json
  {"rows":[{"col1":"v1","col2":"v2"}]}
  ```
