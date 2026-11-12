#!/usr/bin/env bash
# Falla si el APK no es exclusivamente armeabi-v7a o si le falta el runtime de MediaPipe.
set -euo pipefail

APK=${1:?Indica la ruta del APK}
if [[ ! -f "$APK" ]]; then
  echo "No existe el APK: $APK" >&2
  exit 1
fi

tmpdir=$(mktemp -d)
trap 'rm -rf "$tmpdir"' EXIT
unzip -q "$APK" -d "$tmpdir"

echo "---- lib/ ----"
if [[ ! -d "$tmpdir/lib" ]]; then
  echo "ERROR: el APK no contiene lib/" >&2
  find "$tmpdir" -maxdepth 2 -type d | sort >&2
  exit 1
fi
find "$tmpdir/lib" -print | sort

if [[ ! -d "$tmpdir/lib/armeabi-v7a" ]]; then
  echo "ERROR: falta lib/armeabi-v7a" >&2
  exit 1
fi

shopt -s nullglob
extra=("$tmpdir"/lib/*)
for dir in "${extra[@]}"; do
  name=$(basename "$dir")
  if [[ "$name" != "armeabi-v7a" ]]; then
    echo "ERROR: ABI no permitida: lib/$name" >&2
    exit 1
  fi
done

found_mediapipe=0
found_so=0
while IFS= read -r so; do
  found_so=1
  info=$(file "$so")
  echo "$info"
  if ! grep -q "ELF 32-bit" <<<"$info"; then
    echo "ERROR: $so no es ELF de 32 bits" >&2
    exit 1
  fi
  if ! grep -q "ARM" <<<"$info"; then
    echo "ERROR: $so no es ARM" >&2
    exit 1
  fi
  if [[ $(basename "$so") == "libmediapipe_tasks_jni.so" ]]; then
    found_mediapipe=1
  fi
done < <(find "$tmpdir/lib" -type f -name "*.so" | sort)

if [[ "$found_so" -ne 1 ]]; then
  echo "ERROR: no hay bibliotecas nativas" >&2
  exit 1
fi
if [[ "$found_mediapipe" -ne 1 ]]; then
  echo "ERROR: falta libmediapipe_tasks_jni.so" >&2
  exit 1
fi

listing=$(unzip -l "$APK")
for needle in \
  "assets/hand_landmarker_lite.task" \
  "assets/hand_landmarker_full.task"
do
  if ! grep -q "$needle" <<<"$listing"; then
    echo "ERROR: el APK no incluye $needle" >&2
    exit 1
  fi
done

if [[ -n "${ANDROID_HOME:-}" ]]; then
  aapt=$(find "$ANDROID_HOME/build-tools" -type f -name aapt -print | sort | tail -n 1 || true)
  if [[ -n "$aapt" ]]; then
    echo "---- badging ----"
    badging=$("$aapt" dump badging "$APK")
    echo "$badging" | head -n 20
    native=$(echo "$badging" | grep "native-code:" || true)
    echo "$native"
    if [[ "$native" != *"armeabi-v7a"* ]]; then
      echo "ERROR: badging no declara armeabi-v7a" >&2
      exit 1
    fi
    if echo "$native" | grep -Eq "arm64-v8a|x86_64|'x86'"; then
      echo "ERROR: badging declara otra ABI" >&2
      exit 1
    fi
    if echo "$badging" | grep -q "testOnly"; then
      echo "ERROR: el APK está marcado testOnly; adb install -r pediría -t" >&2
      exit 1
    fi
  fi
fi

echo "ABI OK: solo armeabi-v7a, con MediaPipe de 32 bits"
