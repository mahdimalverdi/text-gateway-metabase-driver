# Single Row Metabase Driver Plugin

This repository contains a deliberately simple Metabase plugin that registers a
custom driver returning a single static row for any query.

## Getting Started

1. Adjust the metadata in `metabase-plugin.yaml` if you want to rebrand the driver.
2. Add any required dependencies to `deps.edn` (for example, Metabase Core) before building.
3. Review the implementation in `src/metabase/driver/single_row.clj`. The driver ignores incoming
   queries and responds with one row containing a greeting message.
4. Package the plugin as a JAR and place it in the `plugins/` directory of your Metabase instance.

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

The repository includes a helper script that packages the plugin and writes the artifact to `dist/single-row-driver.jar`:

```bash
./bin/build-driver.sh
```

The script stages the driver sources under `build/plugin/` before invoking the `jar` tool, so feel free to inspect that directory if you need to debug the build. The resulting JAR can be copied into the `plugins/` directory of your Metabase deployment.

If you prefer to run the packaging steps manually, follow the commands below:

```bash
mkdir -p build/plugin dist
cp metabase-plugin.yaml build/plugin/
mkdir -p build/plugin/metabase/driver
cp src/metabase/driver/single_row.clj build/plugin/metabase/driver/
jar cf dist/single-row-driver.jar -C build/plugin .
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

To build a single driver (such as this single-row driver) run:

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
