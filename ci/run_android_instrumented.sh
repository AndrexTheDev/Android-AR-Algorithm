#!/usr/bin/env bash
set +e
gradle --no-daemon --stacktrace :android:arkernel:connectedDebugAndroidTest \
  2>&1 | tee android-instrumented-gradle.log
status=${PIPESTATUS[0]}
if [[ "$status" -ne 0 ]]; then
  {
    echo '### Android instrumentation Gradle failure'
    echo '```text'
    tail -n 160 android-instrumented-gradle.log
    echo '```'
  } >> "$GITHUB_STEP_SUMMARY"
fi
exit "$status"
