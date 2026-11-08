#!/bin/bash
# Mirror a release APK to Nest for the in-app updater: autocorrect-<v>.apk, the autocorrect-latest.apk symlink and
# latest.json in /var/www/autocorrect-android (served at https://autocorrect.mostlime.hackclub.app/autocorrect-android/).
# Usage: scripts/publish_apk.sh v0.1.47     (run after every release)
set -euo pipefail
TAG="${1:?usage: $0 <tag>}"
V="${TAG#v}"
CODE="${V##*.}"
REPO=Mostlime12195/autocorrect-android
HOST=mostlime@hackclub.app
DIR=/var/www/autocorrect-android
BASE=https://autocorrect.mostlime.hackclub.app/autocorrect-android
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

gh release download "$TAG" -R "$REPO" -p "autocorrect-$V.apk" -D "$TMP"
APK="$TMP/autocorrect-$V.apk"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
COMMIT=$(gh release view "$TAG" -R "$REPO" --json targetCommitish --jq .targetCommitish)
NOTES=$(gh api "repos/$REPO/commits/$COMMIT" --jq .commit.message | head -1)
python3 -I -c 'import json,sys; v,c,u,s,n=sys.argv[1:]; print(json.dumps({"version":v,"code":int(c),"url":u,"sha256":s,"notes":n}))' \
  "$V" "$CODE" "$BASE/autocorrect-$V.apk" "$SHA" "$NOTES" > "$TMP/latest.json"

# Nest ssh sometimes drops right after auth and scp fails: pipe over ssh, check the hash, retry
ok=0
for i in 1 2 3 4 5; do
  if ssh -o ConnectTimeout=20 "$HOST" 'cat > /tmp/ac.apk' < "$APK" &&
     [ "$(ssh -o ConnectTimeout=20 "$HOST" 'sha256sum /tmp/ac.apk' | cut -d' ' -f1)" = "$SHA" ]; then ok=1; break; fi
  echo "upload attempt $i failed, retrying"; sleep 5
done
[ "$ok" = 1 ] || { echo "upload failed"; exit 1; }
for i in 1 2 3 4 5; do
  if ssh -o ConnectTimeout=20 "$HOST" "sudo install -m644 /tmp/ac.apk $DIR/autocorrect-$V.apk && sudo ln -sfn autocorrect-$V.apk $DIR/autocorrect-latest.apk && sudo tee $DIR/latest.json >/dev/null && rm -f /tmp/ac.apk" < "$TMP/latest.json"; then ok=2; break; fi
  echo "install attempt $i failed, retrying"; sleep 5
done
[ "$ok" = 2 ] || { echo "install failed"; exit 1; }

curl -fsS "$BASE/latest.json"; echo
curl -fsSI "$BASE/autocorrect-$V.apk" | grep -i -E "^HTTP|content-length"
