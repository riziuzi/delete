#!/usr/bin/env bash
# Prints available versions of build tools (for upgrading Kotlin to read the ML Kit speech library).
set -u
out=${1:-apidump}
mkdir -p "$out"
v() { curl -sfL "$1" | grep -oE '<version>[^<]+' | cut -d'>' -f2 | tail -n "${2:-25}" | tr '\n' ' '; echo; }
{
  echo "kotlin-gradle-plugin: $(v https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml 30)"
  echo "compose-compiler-gradle-plugin: $(v https://repo1.maven.org/maven2/org/jetbrains/kotlin/compose-compiler-gradle-plugin/maven-metadata.xml 15)"
  echo "agp: $(v https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml 40)"
  echo "kotlinx-coroutines-android: $(v https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/maven-metadata.xml 10)"
  echo "compose-bom: $(v https://dl.google.com/android/maven2/androidx/compose/compose-bom/maven-metadata.xml 12)"
  echo "activity-compose: $(v https://dl.google.com/android/maven2/androidx/activity/activity-compose/maven-metadata.xml 12)"
  echo "core-ktx: $(v https://dl.google.com/android/maven2/androidx/core/core-ktx/maven-metadata.xml 12)"
  echo "genai-speech: $(v https://dl.google.com/android/maven2/com/google/mlkit/genai-speech-recognition/maven-metadata.xml 10)"
  echo "gradle: $(curl -sfL https://services.gradle.org/versions/current | grep -o '"version" : "[^"]*"')"
} | tee "$out/versions.txt"
