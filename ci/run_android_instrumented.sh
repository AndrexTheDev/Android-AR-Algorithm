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
  detail=$(grep -E 'FAILURE:|What went wrong|^> |Caused by:|Could not|[Ee]rror:|FAILED' \
    android-instrumented-gradle.log | tail -n 80)
  test_detail=$(find android/arkernel/build/outputs/androidTest-results/connected \
    android/arkernel/build/reports/androidTests/connected -maxdepth 10 -type f \
    \( -name '*.xml' -o -name '*.txt' -o -name '*.html' \) -print0 2>/dev/null \
    | xargs -0 -r grep -aE -A 15 '<failure|<error|Tests run:|FAILURES|AssertionError|Exception|failed to' \
    | sed -E 's/<[^>]+>/ /g; s/&quot;/"/g; s/&lt;/</g; s/&gt;/>/g' \
    | tail -n 80)
  detail="$test_detail"$'\n'"$detail"
  detail=${detail//'%'/'%25'}
  detail=${detail//$'\r'/'%0D'}
  detail=${detail//$'\n'/'%0A'}
  echo "::error title=Android instrumentation Gradle failure::$detail"
fi
exit "$status"
