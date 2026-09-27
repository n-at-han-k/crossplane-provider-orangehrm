#!/usr/bin/env bash
# The OpenAPI description, generated from OrangeHRM's own source.
#
# OrangeHRM does not publish one: `build/orangehrm-v2.json` is built by its
# `generate-open-api-doc` dev command from zircote/swagger-php annotations in
# the PHP, and the release tarball ships neither devTools nor the dev
# dependency. So: a shallow clone at the tag, composer install, scan.
#
# Everything runs in the orangehrm image, which already has PHP 8.3 with the
# extensions composer resolves against. Nothing but docker is needed on the
# host.
#
#   hack/gen-spec.sh [tag]        # default: the tag below
set -euo pipefail

TAG=${1:-v5.9}
IMAGE=orangehrm/orangehrm:${TAG#v}
OUT=$(cd "$(dirname "$0")/.." && pwd)/reference/orangehrm-v2.json
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

git clone --depth 1 --branch "$TAG" https://github.com/orangehrm/orangehrm "$WORK/orangehrm"
curl -sSL -o "$WORK/composer.phar" https://getcomposer.org/download/2.8.12/composer.phar

# swagger-php is a require-dev of src/composer.json, so a plain install.
# --no-scripts would skip the proxy generation the install hooks run, which
# warns "Application not installed" and is irrelevant to a scan; leaving the
# scripts in keeps the install identical to a developer's.
cat > "$WORK/scan.php" <<'PHP'
<?php
// What devTools' generate-open-api-doc scans, minus the php-cs-fixer run it
// does first and the Config bootstrap it reads the paths from -- Config wants
// an installed application, and the paths are a glob.
require '/w/orangehrm/src/vendor/autoload.php';
$base = '/w/orangehrm/src';
$paths = ["$base/plugins/orangehrmCorePlugin/Controller/Rest/V2"];
foreach (glob("$base/plugins/*/Api") as $api) {
    $paths[] = $api;
}
file_put_contents('/w/orangehrm-v2.json', \OpenApi\Generator::scan($paths)->toJson(
    JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_INVALID_UTF8_IGNORE
));
PHP

docker run --rm -u 0 -v "$WORK:/w" -w /w --entrypoint sh "$IMAGE" -c '
  set -e
  COMPOSER_ALLOW_SUPERUSER=1 COMPOSER_MEMORY_LIMIT=-1 \
    php composer.phar install -d orangehrm/src --no-interaction --no-progress
  php scan.php
'

# swagger-php emits one line; committed pretty so a regeneration is a readable
# diff rather than one changed line 450kB wide.
python3 -c "
import json, sys
json.dump(json.load(open('$WORK/orangehrm-v2.json')), open('$OUT', 'w'), indent=2, ensure_ascii=False)
open('$OUT', 'a').write('\n')
"
echo "wrote $OUT ($TAG)"
