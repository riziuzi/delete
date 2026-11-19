#!/usr/bin/env bash
# Prints the public API of the ML Kit GenAI speech-recognition libraries (exact signatures for the app code),
# and whether they touch any Android audio/sound classes.
set -u
M=https://dl.google.com/android/maven2/com/google/mlkit
out=${1:-apidump}
mkdir -p "$out" work
for a in genai-speech-recognition genai-common; do
  curl -sfL "$M/$a/maven-metadata.xml" > "$out/$a-metadata.xml" || echo "no metadata $a"
done
V=$(grep -o '<latest>[^<]*' "$out/genai-speech-recognition-metadata.xml" | cut -d'>' -f2)
echo "speech latest: $V" | tee "$out/versions.txt"
for v in 1.0.0-alpha2 "$V"; do
  curl -sfL "$M/genai-speech-recognition/$v/genai-speech-recognition-$v.pom" > "$out/speech-$v.pom"
done
C=$(grep -A2 'genai-common' "$out/speech-$V.pom" | grep -o '<version>[^<]*' | head -1 | cut -d'>' -f2)
echo "common used: $C" | tee -a "$out/versions.txt"
fetch() { # artifact version
  curl -sfL "$M/$1/$2/$1-$2.aar" -o "work/$1.aar" && (cd work && rm -rf "$1" && mkdir "$1" && cd "$1" && unzip -q ../"$1.aar" && unzip -q -o classes.jar -d classes)
  curl -sfL "$M/$1/$2/$1-$2.pom" > "$out/$1-$2.pom"
}
fetch genai-speech-recognition "$V"
fetch genai-common "$C"
for a in genai-speech-recognition genai-common; do
  [ -f "work/$a/AndroidManifest.xml" ] && cp "work/$a/AndroidManifest.xml" "$out/$a-AndroidManifest.xml"
  find "work/$a/classes" -name '*.class' | sed "s#work/$a/classes/##; s#\.class\$##; s#/#.#g" | sort > "$out/$a-classes.txt"
  grep -E '^com\.google\.mlkit\.genai\.(speechrecognition|common)' "$out/$a-classes.txt" | grep -v '\$\$' > work/pub.txt
  (cd "work/$a/classes" && javap -public $(cat ../../pub.txt) ) > "$out/$a-javap.txt" 2>&1
  # Any audio playback / capture classes referenced anywhere in the library?
  (cd "work/$a/classes" && for f in $(find . -name '*.class'); do javap -c -p "$f" 2>/dev/null | grep -oE 'android/media/[A-Za-z]+' ; done | sort | uniq -c) > "$out/$a-media-refs.txt"
done
ls -la "$out"
