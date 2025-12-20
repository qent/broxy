# bro-cloud integration (private backend module)

## Overview

`bro-cloud` contains all paid backend integration: OAuth flow, token storage, and WebSocket transport to
`broxy.run`. It is a separate Kotlin/JVM Gradle build hosted in a private repository and does not depend
on the main broxy modules.

This public repo integrates it in two ways:

1) Local source (private access) - for internal development.
2) Obfuscated jar (public access) - for OSS contributors without repo access.

## Build flags

The integration is controlled by Gradle properties (see `gradle.properties`):

- `broCloudEnabled` (default `true`) - enable/disable backend integration.
- `broCloudUseLocal` (default `false`) - use local `bro-cloud/` sources via composite build.

When `broCloudEnabled=false`, the UI hides remote actions and runtime uses a no-op connector.

## Local source mode

Clone the private repo into `bro-cloud/` and build with:

- `-PbroCloudUseLocal=true`

This enables composite build substitution and lets you develop `bro-cloud` and `broxy` together.

## Obfuscated jar mode

If you do not have access to the private repo, use the prebuilt jar:

- `bro-cloud/libs/bro-cloud-obfuscated.jar`

The build loads this jar when `broCloudEnabled=true` and `broCloudUseLocal=false`.

## Obfuscation output

`bro-cloud` exposes a task to build the obfuscated jar:

```bash
./gradlew -p bro-cloud obfuscateJar
```

The obfuscated output is written to `bro-cloud/libs/bro-cloud-obfuscated.jar`.

## Runtime entrypoint

`ui-adapter` wraps `bro-cloud` using `BroCloudRemoteConnectorAdapter` and the entrypoint factory:

- `io.qent.broxy.cloud.BroCloudRemoteConnectorFactory`

Public API types live in `io.qent.broxy.cloud.api.*` and are kept during obfuscation.
