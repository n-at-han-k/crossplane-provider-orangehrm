# crossplane-provider-rt

A Crossplane provider for [Request Tracker], generated from the reverse
engineered OpenAPI description of its REST2 API.

```bash
nix develop
bin/generate        # one RT document in, this repo out
```

Everything under `apis/`, `internal/` and `package/crds` is generated and
**committed**: the image compiles what is in the tree, not what a regeneration
would produce. Run `bin/generate`, read the diff, commit it.

## Where it comes from

[n-at-han-k/openapi-schema-rt], a fork of [rt/request-tracker-openapi].

Upstream is a reverse engineered spec — RT's own API documentation is not one,
and nothing generates this from RT's Perl; it was written by hand, path by
path, against a live server. It is explicitly partial: "I've been adding paths
as I need them." The fork carries upstream's full history and adds the rest of
the REST2 surface a provider needs. Additions are meant to go back upstream,
so they follow upstream's conventions and keep its file name.

The spec is a **submodule** at `reference/openapi-schema-rt`, not a vendored
copy and not a gitignored clone: the commit it is pinned to is the commit this
generated tree came from, and `git submodule status` says so without anyone
writing it down. `bin/generate` checks it out for you.

```bash
git submodule update --init            # the spec, and crossplane/build
git submodule update --remote reference/openapi-schema-rt   # take a newer spec
```

## What it covers

Twenty-one Kinds, from all 103 operations the document describes.
`make coverage` proves the accounting and fails if an operation that should
be wired into a controller is not.

| Kind | create | read | update | delete |
|---|---|---|---|---|
| `Ticket` | `POST /ticket` | ✓ | ✓ | ✓ |
| `Queue` | `POST /queue` | ✓ | ✓ | ✓ |
| `User` | `POST /user` | ✓ | ✓ | ✓ |
| `Group` | `POST /group` | ✓ | ✓ | ✓ |
| `Customfield` | `POST /customfield` | ✓ | ✓ | ✓ |
| `CustomfieldValue` | `POST /customfield/{id}/value` | ✓ | ✓ | ✓ |
| `Catalog` | `POST /catalog` | ✓ | ✓ | ✓ |
| `Class` | `POST /class` | ✓ | ✓ | ✓ |
| `Asset` | `POST /asset` | ✓ | ✓ | ✓ |
| `Article` | `POST /article` | ✓ | ✓ | ✓ |
| `Lifecycle` | `POST /lifecycles` | ✓ | ✓ | ✓ |
| `Customrole` | — | ✓ | — | — |
| `GroupMember` | `PUT /group/{id}/members` | — | — | `DELETE` same path |
| `UserGroup` | `PUT /user/{idOrName}/groups` | — | — | `DELETE` same path |
| `LifecycleMap` | `PUT /lifecycle/{name}/maps` | — | — | — |
| `QueueRight`, `GroupRight`, `ClassRight`, `CatalogRight`, `CustomfieldRight`, `GlobalRight` | `POST …/rights` | — | — | — |

A dash is RT's limit, not the provider's. `Customrole` has no create because
REST2 offers none — the web UI is the only way to make one.

The other 47 operations are not things a managed resource calls:

- **21 lists** — `GET /tickets`, `/queues/all`, `/customfield/{id}/values`,
  every `…/rights/available`. Crossplane reads one resource by its external
  name and never lists.
- **12 searches and actions** — `POST /users`, `POST /customfields`,
  every `…/rights/bulk`, `POST /lifecycle/{name}/validate`. Told apart from a
  create by what they answer: a create answers 201, these answer 200.
- **14 verb paths** — the rights revokes
  (`DELETE /queue/{id}/rights/{right}/group/{id}`) and the single-member
  removals (`DELETE /group/{id}/member/{id}`). Each looks like a member path,
  but nothing can create or read one, so there is no resource there to own.
  Revoking is therefore out of reach of `QueueRight` and friends; see the
  gaps below.

## The generator

`-g rt-crossplane` is a custom openapi-generator generator, built by
`nix build .#openapi-generator-rt` — `javac` against the packaged CLI's own
jar and an SPI entry, no Maven and no checkout of the generator. It is
[crossplane-provider-wso2]'s, with the parts RT's API shape breaks replaced.

It extends upstream's `terraform-provider` generator, which sounds odd for a
Crossplane provider and is the laziest correct choice: the hard part is not
emitting Go, it is deciding which operations are one resource and which of
them is the create, the read, the update and the delete. Both targets ask
exactly that question.

What RT needed on top of the WSO2 generator:

- **201 decides a create, not the spelling.** RT POSTs both to create and to
  search. `POST /ticket` creates, `POST /tickets` searches; but `POST
  /lifecycles` creates on a plural path and `POST /customfields` searches on
  one, so no rule about singular and plural survives the whole document. A
  create answers 201 and a search answers 200, which also drops the action
  endpoints (`/lifecycle/{name}/validate`) for free.

- **Two paths, one resource.** RT creates a lifecycle at `POST /lifecycles`
  and addresses it at `/lifecycle/{name}` ever after. Both camelise to
  `Lifecycle`, so left alone they are two groups writing one set of files —
  the second overwriting the first with half a resource. They are keyed on
  the collection that owns the member path.

- **Verbs that look like resources.** `DELETE /queue/{id}/rights/{right}/group/{id}`
  is a member path by shape, so it would become a Kind that can only be
  deleted. A collection with neither a create nor a read anywhere in the
  document is a verb, and is dropped.

- **Sets have an inverse.** `DELETE /group/{id}/members` empties what
  `PUT /group/{id}/members` filled, and takes no id of its own.

- **Singular creates.** RT creates with `POST /ticket` and searches with
  `POST /tickets`. Upstream's grouping assumes the create and the collection
  share a path, so every RT resource came out of it either empty or doubled —
  `/tickets` and `/ticket` camelise to the same Kind, and one CRD silently
  overwrites the other. The shape of the path decides instead: a member path
  is the read/update/delete, a POST on a singular path is the create, and a
  POST on a plural path is a search and is dropped.

- **Set endpoints.** `PUT /user/{idOrName}/groups` replaces that user's
  memberships and answers a message, not an identifier — and no `Location`
  header either. The resource is *that user's* groups, so the owning id is
  the external name. Without this the create refuses to record an empty one
  and the resource never reconciles at all.

- **Integer ids.** RT numbers its tickets; an external name is a string.

- **3.1 validation `anyOf`.** `EmailAddress` is `type: string` with an `anyOf`
  of `{format: email, maxLength: 0}` — an address, or empty. The generator
  emits that composition as a Go type named `AnyOf`, which does not compile.
  The declared type is still on the property and is used; a genuine union
  travels as JSON.

- **Array response bodies.** An operation that answers a list has no single
  object to project onto `status.atProvider`, and `rt.[]Thing` is not a type
  name.

- **`Group` as a Kind.** RT has one, and a package cannot hold both a type
  called `Group` and the `Group` const that carries the CRD group. Renamed
  `CRDGroup`, which is what upjet-generated providers call it for the same
  reason.

Two bugs in the WSO2 generator were fixed here rather than carried over: a
nested create interpolated nothing (`fmt.Sprintf("/a/{id}/b", id)` compiles,
and sends a literal `{id}`), and a resource with no read reported
`ResourceExists` on its very first Observe — crossplane-runtime's default
initializer is `NewNameAsExternalName`, so the external name is already set
before Observe ever runs, and Create was never called. `go vet` catches the
first; the second is why those controllers read existence off the condition
they set at the end of Create.

The scaffold — `cmd/provider`, the `ProviderConfig`, `Makefile`,
`package/crossplane.yaml` — is generated too, from
`generators/rt/resources/crossplane-provider/`.

### What the generator cannot do

`controller-gen` and `angryjet` need to see the Go **types**, not the API
description, so `make generate` runs them over the output: DeepCopy, the
crossplane-runtime methodsets, and `package/crds`. `bin/generate` does this
for you.

## Credentials

A `ProviderConfig`'s secret is one JSON document: RT needs to be told where it
is as well as who you are.

```yaml
stringData:
  credentials: |
    {
      "endpoint": "https://rt.example.com/REST/2.0",
      "token": "1-14-0123456789abcdef"
    }
```

The token goes out as `Authorization: token <token>`, which is RT's own
scheme. `username`/`password` are accepted instead and `apiKey` is sent as the
whole header verbatim, for a scheme this client does not spell.

## What it does not do yet

- The CRD spells its fields the way RT does — `spec.forProvider.Queue`, not
  `queue`. The wire name is the field name, so nothing needs a translation
  layer and nothing can drift out of one; it does read oddly next to the rest
  of a Kubernetes manifest.
- Nested objects and arrays become a `string` holding JSON. `upToDate`
  compares those by value rather than by text, so a re-ordered object coming
  back is not a permanent diff.
- A right cannot be revoked. Granting is `POST …/rights`; revoking needs
  `DELETE …/rights/{right}/group/{id}`, whose path the generator cannot
  derive from the grant it was given. Deleting a `QueueRight` errors rather
  than silently leaving the right in place.
- A resource whose API has no delete (`Customrole`, `LifecycleMap`, the six
  rights Kinds) cannot be deleted by Crossplane. Deleting the managed
  resource errors and the finalizer stays, deliberately: succeeding would let
  Crossplane forget something that still exists.
- The Kinds with no read — the memberships, `LifecycleMap`, the rights — are
  never reported as drifted. A membership changed in RT's UI is not
  corrected.
- `Customfield` and `Customrole` read as they do because RT spells them as
  one word; a friendlier Kind name would be a rename the document does not
  make.
- No acceptance tests against a live RT, and no `examples/`.
- The document is partial. Queues cannot be updated, groups and custom fields
  cannot be deleted, and ticket comments, replies, attachments and history are
  not described at all. Adding a path to the document adds the resource here.

[Request Tracker]: https://bestpractical.com/request-tracker
[rt/request-tracker-openapi]: https://gitlab-ext.utu.fi/rt/request-tracker-openapi
[n-at-han-k/openapi-schema-rt]: https://github.com/n-at-han-k/openapi-schema-rt
[crossplane-provider-wso2]: https://github.com/n-at-han-k/crossplane-provider-wso2
