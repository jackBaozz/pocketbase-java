# Dart/Flutter SDK Smoke Test

## Overview
The repository contains an executable smoke test against the official Dart SDK. It starts a temporary Java server and runs `src/test/resources/dart-sdk-smoke/smoke.dart` through `DartSdkSmokeTest`.

## Prerequisites
- Dart SDK >= 3.0.0
- Network access for the initial `dart pub get`

## Covered Workflows

1. Authentication and token refresh through the official SDK.
2. Collection creation and record CRUD.
3. Multipart file upload, generated file URL download, and file token creation.
4. Official batch request/response handling, including the top-level response array.
5. Realtime SSE subscription and create event delivery.

## Execution

Run only the Dart smoke test:

```shell
mvn -gs settings.xml -s settings.xml -Dtest=DartSdkSmokeTest test
```

If `dart` is not available on `PATH`, JUnit skips this test instead of failing the JVM suite. Release/SDK compatibility environments should install Dart so the smoke test is executed rather than skipped.
