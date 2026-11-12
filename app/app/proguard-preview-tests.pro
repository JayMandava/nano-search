# Test-only: compiler annotation signature references a desktop JDK type absent on Android.
# It is not executable code and is not needed by the instrumentation runner.
-dontwarn javax.lang.model.element.Modifier
-keep class ai.nanosearch.launcher.Os7CloudSmokeTest { *; }
