#!/usr/bin/env python3
"""Every operation in the document is accounted for, and every one that a
managed resource should call is actually called.

The generator drops an operation on purpose in three cases, and this spells the
same three rules a second time so that the two have to agree:

  list            a GET on a collection. Crossplane reads one resource by its
                  external name and never lists.
  bulk            a PUT on a collection -- /admin/i18n/languages/{id}/
                  translations/bulk. It writes many at once; a managed resource
                  owns one.
  action          a POST on a MEMBER path, or an operation whose collection is
                  a verb rather than a resource: nothing there can be both
                  created and destroyed, and nothing can be read one at a time.
                  /admin/ldap-test-connection, /pim/csv-import,
                  /time/projects/{to}/activities/copy/{from}.
  unobservable    a read whose envelope's `data` is an ARRAY or is not
                  described at all -- three of them. status.atProvider holds
                  ONE record, and a controller that cannot parse what it read
                  is worse than one with no read: it fails for ever rather than
                  falling back to what Create recorded.

Anything else must appear in a controller. An operation that should be wired
and is not is a resource the provider silently does not cover.

    python3 hack/check-coverage.py
"""

import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / "reference/orangehrm-v2.json"
VERBS = ("get", "put", "post", "delete", "patch")


def member(path):
    return path.rstrip("/").split("/")[-1].startswith("{")


def collection(path):
    path = path.rstrip("/")
    return path.rsplit("/", 1)[0] if member(path) else path


def observable(op):
    """Whether a read answers something status.atProvider can hold one of.

    The generator's observable(): the document says a read of one employee
    directory listing, one leave balance and one candidate answers an array or
    an undescribed `data`, and none of those is a single record.
    """
    schema = (op.get("responses", {}).get("200", {})
              .get("content", {}).get("application/json", {}).get("schema", {}))
    data = schema.get("properties", {}).get("data")

    if not isinstance(data, dict) or data.get("type") == "array":
        return False

    return bool(data.get("$ref") or data.get("properties")
                or data.get("allOf") or data.get("oneOf") or data.get("anyOf"))


def describes_a_resource(paths, wanted):
    """A collection with a read, or one that can be both created and destroyed.

    The same rule as the generator's describesAResource: a member GET settles
    it, and without one a resource still needs a create AND a delete -- which
    is what tells /buzz/shares (created, updated, deleted, never read alone)
    from /pim/csv-import (posted to, and that is all).
    """
    create = delete = False

    for path, item in paths.items():
        if collection(path) != wanted:
            continue
        for verb in VERBS:
            if verb not in item:
                continue
            if member(path) and verb == "get" and observable(item[verb]):
                return True
            if not member(path) and verb == "post":
                create = True
            if verb == "delete":
                delete = True

    return create and delete


def wired():
    """(METHOD, path) of every call the generated controllers actually make."""
    calls = set()
    pattern = re.compile(r'Do(?:Create)?Request\(ctx, "([A-Z]+)", (?:fmt\.Sprintf\()?"([^"]+)"')
    for go in (ROOT / "internal/controller").rglob("*.go"):
        for method, path in pattern.findall(go.read_text()):
            calls.add((method, path))
    return calls


def main():
    if not SPEC.exists():
        sys.exit("missing {} -- run: hack/gen-spec.sh".format(SPEC))

    paths = json.loads(SPEC.read_text())["paths"]
    calls = wired()
    resourceful = {c: describes_a_resource(paths, c)
                   for c in {collection(p) for p in paths}}

    counts, missing, skipped = Counter(), [], []

    for path, item in paths.items():
        for verb in VERBS:
            if verb not in item:
                continue
            method, op = verb.upper(), item[verb]
            where = "{:6} {:64} ({})".format(method, path, op.get("operationId"))

            # Ordered so that each operation gets the name of what it actually
            # is: a list on a collection that describes no resource is still a
            # list, not an action.
            if not member(path) and method == "GET":
                counts["list"] += 1
                skipped.append("list   " + where)
            elif not member(path) and method in ("PUT", "PATCH"):
                counts["bulk"] += 1
                skipped.append("bulk   " + where)
            elif member(path) and method == "POST":
                counts["action"] += 1
                skipped.append("action " + where)
            elif member(path) and method == "GET" and not observable(op):
                counts["unobservable"] += 1
                skipped.append("unobs  " + where)
            elif not resourceful[collection(path)]:
                counts["action"] += 1
                skipped.append("action " + where)
            else:
                counts["wired"] += 1
                if (method, re.sub(r"\{[^}]*\}", "%v", path)) not in calls:
                    missing.append(where)

    for line in sorted(skipped):
        print(line)

    if missing:
        print("\nNOT COVERED -- should be wired into a controller and is not:")
        for line in missing:
            print("  " + line)
        sys.exit(1)

    total = sum(counts.values())
    print("\n{} operations: {}".format(
        total, ", ".join("{} {}".format(n, name) for name, n in sorted(counts.items()))))


if __name__ == "__main__":
    main()
