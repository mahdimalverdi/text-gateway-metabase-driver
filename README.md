# Single Row Metabase Driver Plugin

This repository contains a deliberately simple Metabase plugin that registers a
custom driver returning a single static row for any query.

## Getting Started

1. Adjust the metadata in `metabase-plugin.yaml` if you want to rebrand the driver.
2. Add any required dependencies to `deps.edn` (for example, Metabase Core) before building.
3. Review the implementation in `src/metabase/driver/single_row.clj`. The driver ignores incoming
   queries and responds with one row containing a greeting message.
4. Package the plugin as a JAR and place it in the `plugins/` directory of your Metabase instance.

### Building the JAR

The repository includes a prebuilt artifact at `dist/single-row-driver.jar`.  
If you make changes and want to recreate it, run:

```bash
mkdir -p build/plugin dist
cp metabase-plugin.yaml build/plugin/
mkdir -p build/plugin/metabase/driver
cp src/metabase/driver/single_row.clj build/plugin/metabase/driver/
jar cf dist/single-row-driver.jar -C build/plugin .
```

The resulting JAR can then be copied into the `plugins/` directory of your Metabase deployment.

Refer to the [Metabase driver documentation](https://www.metabase.com/docs/latest/developers-guide/drivers/overview) for guidance on building out the plugin.
