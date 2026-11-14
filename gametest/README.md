# In-game tests

End-to-end scenarios for Fabric's client game test API: they open a world, open Xaero's map and drive the tool with real input.

They are not part of the normal build. To run them:

```sh
./gradlew -PgameTest runClientGameTest
```

A real client opens for about seven minutes and runs the four scenarios one after another, so each starts with what the one before it left in the settings. Each check prints an `E2E: ok` or `E2E: FAIL` line and each scenario ends with `E2E: RESULT passed=… failed=…`; screenshots go to `build/run/clientGameTest/screenshots/`. Gradle reports success even when checks fail, so read the `RESULT` lines.
