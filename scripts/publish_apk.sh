#!/bin/bash
# Publish a release APK for the in-app updater (run after every release):
#  - the APK goes to the PUBLIC models repo as release app-v<v> (never "latest", the models release keeps that):
#    https://github.com/Mostlime12195/autocorrect-models/releases/download/app-v<v>/autocorrect-<v>.apk
#    (Nest's proxy streams big files chunked without Content-Length, and downloads stall on phones)
#  - latest.json (small) goes to Nest: https://autocorrect.mostlime.hackclub.app/autocorrect-android/latest.json
# Usage: scripts/publish_apk.sh v0.1.49
set -euo pipefail
TAG="${1:?usage: $0 <tag>}"
V="${TAG#v}"
CODE="${V##*.}"
REPO=Mostlime12195/autocorrect-android
PUB=Mostlime12195/autocorrect-models
HOST=mostlime@hackclub.app
DIR=/var/www/autocorrect-android
BASE=https://autocorrect.mostlime.hackclub.app/autocorrect-android
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

gh release download "$TAG" -R "$REPO" -p "autocorrect-$V.apk" -D "$TMP"
APK="$TMP/autocorrect-$V.apk"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")
COMMIT=$(gh release view "$TAG" -R "$REPO" --json targetCommitish --jq .targetCommitish)
NOTES=$(gh api "repos/$REPO/commits/$COMMIT" --jq .commit.message | head -1)

if gh release view "app-v$V" -R "$PUB" >/dev/null 2>&1; then
  gh release upload "app-v$V" "$APK" -R "$PUB" --clobber
else
  gh release create "app-v$V" "$APK" -R "$PUB" --latest=false --title "Autocorrect keyboard $V" \
    --notes "App APK for the in-app updater (the keyboard repo is private). $NOTES"
fi
URL="https://github.com/$PUB/releases/download/app-v$V/autocorrect-$V.apk"
# the public URL must serve these bytes: ranged GETs of the first and last KiB (the CDN can cache a 404 for a while,
# and a whole-file download from this host breaks off), plus GitHub's digest when it reports one
ok=0
for i in 1 2 3 4 5 6 7 8 9 10; do
  head_ok=0; tail_ok=0
  code=$(curl -sL --http1.1 -r 0-1023 -o "$TMP/head.bin" -w '%{http_code}' "$URL?check=$i" || true)
  [ "$code" = 206 ] && cmp -s "$TMP/head.bin" <(head -c 1024 "$APK") && head_ok=1
  code=$(curl -sL --http1.1 -r "$((SIZE - 1024))-$((SIZE - 1))" -o "$TMP/tail.bin" -w '%{http_code}' "$URL?check=$i" || true)
  [ "$code" = 206 ] && cmp -s "$TMP/tail.bin" <(tail -c 1024 "$APK") && tail_ok=1
  code=$(curl -sL --http1.1 -r 0-15 -o /dev/null -w '%{http_code}' "$URL" || true)   # the plain URL apps will use
  if [ "$head_ok" = 1 ] && [ "$tail_ok" = 1 ] && [ "$code" = 206 ]; then ok=1; break; fi
  echo "asset not served correctly yet (try $i), waiting"; sleep 30
done
[ "$ok" = 1 ] || { echo "the public asset isn't served correctly: not writing latest.json"; exit 1; }
DIGEST=$(gh api "repos/$PUB/releases/tags/app-v$V" --jq ".assets[] | select(.name == \"autocorrect-$V.apk\") | .digest // empty")
[ -z "$DIGEST" ] || [ "$DIGEST" = "sha256:$SHA" ] || { echo "GitHub asset digest mismatch: $DIGEST"; exit 1; }

python3 -I -c 'import json,sys; v,c,u,s,z,n=sys.argv[1:]; print(json.dumps({"version":v,"code":int(c),"url":u,"sha256":s,"size":int(z),"notes":n}))' \
  "$V" "$CODE" "$URL" "$SHA" "$SIZE" "$NOTES" > "$TMP/latest.json"

# Nest ssh sometimes drops right after auth: idempotent write, confirmed over https, retried
ok=0
for i in 1 2 3 4 5; do
  ssh -o ConnectTimeout=20 "$HOST" "sudo tee $DIR/latest.json.tmp >/dev/null && sudo mv $DIR/latest.json.tmp $DIR/latest.json" < "$TMP/latest.json" || true
  if curl -fsS "$BASE/latest.json?$(date +%s)" | grep -q "$SHA"; then ok=1; break; fi
  echo "latest.json attempt $i not confirmed, retrying"; sleep 5
done
[ "$ok" = 1 ] || { echo "latest.json update failed"; exit 1; }
curl -fsS "$BASE/latest.json"; echo
