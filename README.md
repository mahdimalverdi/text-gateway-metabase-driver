# HTTP Echo Metabase Driver Plugin

This repository contains a deliberately simple Metabase plugin that forwards the SQL editor text to an HTTP endpoint and
returns the JSON payload as a single row in Metabase.

## Getting Started

1. Adjust the metadata in `metabase-plugin.yaml` if you want to rebrand the driver.
2. Review `deps.edn` for the HTTP/JSON libraries that power the API request.
3. Inspect the implementation in `src/metabase/driver/http_echo.clj`. The driver takes the native query text, sends it
   to an HTTP endpoint as the `q` query parameter, and formats the JSON response into Metabase columns.
4. Package the plugin as a JAR and place it in the `plugins/` directory of your Metabase instance.
5. When adding the database in Metabase, supply the API URL in the connection form (the `endpoint` field). You can also
   set a global default with the `HTTP_ECHO_API_ENDPOINT` environment variable (fallback is `http://localhost:8080/api`).

### Mock API Server

A small Bash script spins up a local JSON echo service so you can exercise the driver end-to-end:

```bash
./scripts/http-echo-mock-api.sh 8080
```

Requests to `http://localhost:8080/api?q=hello` return a JSON body containing the echoed text and its length. Leave the
script running while you run queries from Metabase.

### Kubernetes Pod Template

To deploy the mock API in Kubernetes, apply `k8s/http-echo-mock-pod.yaml`. The manifest creates a pod in the `data-infra`
namespace that exposes the same JSON echo service on port 8080.

### Working with the Driver Locally

If you are iterating from a REPL, launch Metabase’s development environment so the driver, Metabase core, and companion
drivers are all on the classpath together:

```bash
clojure -A:dev:drivers:drivers-dev
```

Metabase only sees changes after you rebuild the plugin and restart with the fresh artifact, so keep the following loop
in mind while developing:

1. Rebuild the driver JAR (see below).
2. Copy the new JAR into your Metabase `plugins/` directory.
3. Restart Metabase to pick up the updated driver implementation.

### Building the JAR

The repository includes a helper script that packages the plugin and writes the artifact to `dist/http-echo-driver.jar`:

```bash
./bin/build-http-echo.sh
```

The script stages the driver sources under `build/plugin/` before invoking the `jar` tool, so feel free to inspect that directory if you need to debug the build. The resulting JAR can be copied into the `plugins/` directory of your Metabase deployment.

If you prefer to run the packaging steps manually, follow the commands below:

```bash
mkdir -p build/plugin dist
cp metabase-plugin.yaml build/plugin/
mkdir -p build/plugin/metabase/driver
cp src/metabase/driver/http_echo.clj build/plugin/metabase/driver/
jar cf dist/http-echo-driver.jar -C build/plugin .
```

### Using Metabase's build-drivers scripts

Metabase’s own build tooling can also package driver plugins when you work inside their monorepo. After installing the Clojure CLI tools, you can use any of the following entry points:

```bash
clojure -X:build:drivers:build/drivers

# or

clojure -X:build:drivers:build/drivers :edition :ee

# or

./bin/build-drivers.sh
```

To build a single driver (such as this HTTP Echo driver) run:

```bash
clojure -X:build:drivers:build/driver :driver :sqlserver

# or

clojure -X:build:drivers:build/driver :driver :sqlserver :edition :oss

# or

./bin/build-driver.sh redshift
```

And to validate the resulting artifact:

```bash
clojure -X:build:build/verify-driver :driver :mongo
```

Refer to Metabase’s driver development docs for the authoritative overview on these commands.

Refer to the [Metabase driver documentation](https://www.metabase.com/docs/latest/developers-guide/drivers/overview) for guidance on building out the plugin.
