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

[rt/request-tracker-openapi], which is a reverse engineered spec — RT's own
API documentation is not one — and is explicitly partial: it has the paths its
author needed. What the provider covers is what that document describes, and
it grows when the document does.

`reference/` is gitignored — it is upstream, read only, and nothing here is
built from a copy of it that this repo keeps.

```bash
mkdir -p reference && cd reference
git clone https://gitlab-ext.utu.fi/rt/request-tracker-openapi.git
```

## What it covers

Every one of the document's 22 operations, accounted for. `make coverage`
proves it, and fails if a future spec adds an operation that falls through.

| Kind | create | read | update | delete |
|---|---|---|---|---|
| `Ticket` | `POST /ticket` | `GET /ticket/{id}` | `PUT /ticket/{id}` | — |
| `User` | `POST /user` | `GET /user/{idOrName}` | `PUT /user/{idOrName}` | `DELETE /user/{idOrName}` |
| `Group` | `POST /group` | `GET /group/{id}` | — | — |
| `Queue` | `POST /queue` | `GET /queue/{idOrName}` | — | `DELETE /queue/{idOrName}` |
| `Customfield` | `POST /customfield` | `GET /customfield/{id}` | — | — |
| `UserGroup` | `PUT /user/{idOrName}/groups` | — | — | — |
| `GroupMember` | `PUT /group/{id}/members` | — | — | — |

A dash is the API's, not the provider's: RT does not delete a ticket, it
closes one. Update and Delete on those resources return an error saying so
rather than reporting Synced over a request never made.

The remaining six operations are searches and lists — `GET /tickets`,
`POST /users`, `POST /groups`, `POST /customfields`, `GET /queues/all`,
`GET /rt`. A Crossplane managed resource never calls them: it reads one
resource by its external name and nothing else.

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
- A resource whose API has no delete (`Ticket`, `Group`, `Customfield`,
  the two membership Kinds) cannot be deleted by Crossplane. Deleting the
  managed resource errors and the finalizer stays, deliberately: succeeding
  would let Crossplane forget a ticket that still exists. Close it in RT and
  remove the finalizer.
- `UserGroup` and `GroupMember` have no read, so they are never reported as
  drifted — a membership changed in RT's UI will not be corrected.
- No acceptance tests against a live RT, and no `examples/`.
- The document is partial. Queues cannot be updated, groups and custom fields
  cannot be deleted, and ticket comments, replies, attachments and history are
  not described at all. Adding a path to the document adds the resource here.

[Request Tracker]: https://bestpractical.com/request-tracker
[rt/request-tracker-openapi]: https://gitlab-ext.utu.fi/rt/request-tracker-openapi
[crossplane-provider-wso2]: https://github.com/n-at-han-k/crossplane-provider-wso2
