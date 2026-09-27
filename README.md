# crossplane-provider-orangehrm

A Crossplane provider for [OrangeHRM], generated from the OpenAPI description
of its REST v2 API.

```bash
nix develop
hack/gen-spec.sh    # OrangeHRM's source in, one OpenAPI document out
bin/generate        # that document in, this repo out
```

Everything under `apis/`, `internal/` and `package/crds` is generated and
**committed**: the image compiles what is in the tree, not what a regeneration
would produce. Run `bin/generate`, read the diff, commit it.

## Where the API description comes from

OrangeHRM does not publish one. `build/orangehrm-v2.json` is a build artefact of
its own repository: a dev command compiles it out of the
[zircote/swagger-php] annotations in the PHP, and the release tarball ships
neither the command nor the dev dependency that runs it.

So `hack/gen-spec.sh` builds it — a shallow clone at the tag, `composer
install`, and the same scan the dev command does, all inside the
`orangehrm/orangehrm` image because that already has the PHP and the extensions
composer resolves against. Nothing but docker is needed on the host.

The result is committed at `reference/orangehrm-v2.json`, pretty-printed so that
a regeneration for a new OrangeHRM release is a readable diff. It is not a
submodule, unlike the sibling RT provider's document: there is no upstream
repository of this file to pin, only the tag it was generated from.

```bash
hack/gen-spec.sh          # the tag in the script, currently v5.9
hack/gen-spec.sh v5.10    # a newer OrangeHRM
```

Two consequences of a document nobody hand-writes, both visible below: it
describes the API very evenly, and it carries the annotations' own mistakes.

## What it covers

Seventy-four Kinds, from 256 of the 490 operations the document describes.
`make coverage` proves the accounting and fails if an operation that should be
wired into a controller is not.

Every resource is one collection path and its member path, and OrangeHRM spells
those the same way everywhere:

```
POST   /api/v2/admin/educations        the create, answering 200
GET    /api/v2/admin/educations/{id}   the read
PUT    /api/v2/admin/educations/{id}   the update
DELETE /api/v2/admin/educations        the delete, with a body of {"ids": [7]}
```

| Kind | create | read | update | delete |
|---|---|---|---|---|
| `AdminEducation` | `POST /admin/educations` | ✓ | ✓ | ✓ |
| `AdminEmailSubscriptionSubscriber` | `POST /admin/email-subscriptions/%v/subscribers` | ✓ | ✓ | ✓ |
| `AdminEmploymentStatus` | `POST /admin/employment-statuses` | ✓ | ✓ | ✓ |
| `AdminI18nLanguage` | — | ✓ | ✓ | ✓ |
| `AdminJobCategory` | `POST /admin/job-categories` | ✓ | ✓ | ✓ |
| `AdminJobTitle` | `POST /admin/job-titles` | ✓ | ✓ | ✓ |
| `AdminLanguage` | `POST /admin/languages` | ✓ | ✓ | ✓ |
| `AdminLicense` | `POST /admin/licenses` | ✓ | ✓ | ✓ |
| `AdminLocation` | `POST /admin/locations` | ✓ | ✓ | ✓ |
| `AdminMembership` | `POST /admin/memberships` | ✓ | ✓ | ✓ |
| `AdminNationality` | `POST /admin/nationalities` | ✓ | ✓ | ✓ |
| `AdminOauthClient` | `POST /admin/oauth-clients` | ✓ | ✓ | ✓ |
| `AdminPayGrade` | `POST /admin/pay-grades` | ✓ | ✓ | ✓ |
| `AdminPayGradeCurrency` | `POST /admin/pay-grades/%v/currencies` | ✓ | ✓ | ✓ |
| `AdminSkill` | `POST /admin/skills` | ✓ | ✓ | ✓ |
| `AdminSubunit` | `POST /admin/subunits` | ✓ | ✓ | ✓ |
| `AdminUser` | `POST /admin/users` | ✓ | ✓ | ✓ |
| `AdminWorkShift` | `POST /admin/work-shifts` | ✓ | ✓ | ✓ |
| `AdminWorkspaceNotificationRegistration` | `POST /admin/workspace-notification/registrations` | ✓ | ✓ | ✓ |
| `AttendanceEmployeeRecord` | `POST /attendance/employees/%v/records` | — | — | ✓ |
| `AttendanceRecord` | — | ✓ | ✓ | — |
| `AuthOpenidProvider` | `POST /auth/openid-providers` | ✓ | ✓ | ✓ |
| `BuzzCommentLike` | `POST /buzz/comments/%v/likes` | — | — | ✓ |
| `BuzzPost` | `POST /buzz/posts` | ✓ | ✓ | — |
| `BuzzShare` | `POST /buzz/shares` | — | ✓ | ✓ |
| `BuzzShareComment` | `POST /buzz/shares/%v/comments` | ✓ | ✓ | ✓ |
| `BuzzShareLike` | `POST /buzz/shares/%v/likes` | — | — | ✓ |
| `ClaimEmployeeRequest` | `POST /claim/employees/%v/requests` | ✓ | — | — |
| `ClaimEvent` | `POST /claim/events` | ✓ | ✓ | ✓ |
| `ClaimExpensesType` | `POST /claim/expenses/types` | ✓ | ✓ | ✓ |
| `ClaimRequest` | `POST /claim/requests` | ✓ | — | — |
| `ClaimRequestAttachment` | `POST /claim/requests/%v/attachments` | ✓ | ✓ | ✓ |
| `ClaimRequestExpense` | `POST /claim/requests/%v/expenses` | ✓ | ✓ | ✓ |
| `LeaveEmployeesLeaveRequest` | `POST /leave/employees/leave-requests` | ✓ | ✓ | — |
| `LeaveHoliday` | `POST /leave/holidays` | ✓ | ✓ | ✓ |
| `LeaveLeaveEntitlement` | `POST /leave/leave-entitlements` | ✓ | ✓ | ✓ |
| `LeaveLeaveRequest` | `POST /leave/leave-requests` | ✓ | ✓ | — |
| `LeaveLeaveType` | `POST /leave/leave-types` | ✓ | ✓ | ✓ |
| `PerformanceConfigTracker` | `POST /performance/config/trackers` | ✓ | ✓ | ✓ |
| `PerformanceEmployeesTracker` | — | ✓ | — | — |
| `PerformanceKpi` | `POST /performance/kpis` | ✓ | ✓ | ✓ |
| `PerformanceManageReview` | `POST /performance/manage/reviews` | ✓ | ✓ | ✓ |
| `PerformanceTrackerLog` | `POST /performance/trackers/%v/logs` | ✓ | ✓ | ✓ |
| `PimCustomField` | `POST /pim/custom-fields` | ✓ | ✓ | ✓ |
| `PimEmployee` | `POST /pim/employees` | ✓ | — | ✓ |
| `PimEmployeeDependent` | `POST /pim/employees/%v/dependents` | ✓ | ✓ | ✓ |
| `PimEmployeeEducation` | `POST /pim/employees/%v/educations` | ✓ | ✓ | ✓ |
| `PimEmployeeEmergencyContact` | `POST /pim/employees/%v/emergency-contacts` | ✓ | ✓ | ✓ |
| `PimEmployeeImmigration` | `POST /pim/employees/%v/immigrations` | ✓ | ✓ | ✓ |
| `PimEmployeeLanguage` | `POST /pim/employees/%v/languages` | — | — | ✓ |
| `PimEmployeeLanguageFluency` | — | ✓ | ✓ | — |
| `PimEmployeeLicense` | `POST /pim/employees/%v/licenses` | ✓ | ✓ | ✓ |
| `PimEmployeeMembership` | `POST /pim/employees/%v/memberships` | ✓ | ✓ | ✓ |
| `PimEmployeeSalaryComponent` | `POST /pim/employees/%v/salary-components` | ✓ | ✓ | ✓ |
| `PimEmployeeScreenAttachment` | `POST /pim/employees/%v/screen/%v/attachments` | ✓ | ✓ | ✓ |
| `PimEmployeeSkill` | `POST /pim/employees/%v/skills` | ✓ | ✓ | ✓ |
| `PimEmployeeSubordinate` | `POST /pim/employees/%v/subordinates` | ✓ | ✓ | ✓ |
| `PimEmployeeSupervisor` | `POST /pim/employees/%v/supervisors` | ✓ | ✓ | ✓ |
| `PimEmployeeTermination` | `POST /pim/employees/%v/terminations` | ✓ | ✓ | ✓ |
| `PimEmployeeWorkExperience` | `POST /pim/employees/%v/work-experiences` | ✓ | ✓ | ✓ |
| `PimReportingMethod` | `POST /pim/reporting-methods` | ✓ | ✓ | ✓ |
| `PimReportsDefined` | `POST /pim/reports/defined` | — | ✓ | ✓ |
| `PimTerminationReason` | `POST /pim/termination-reasons` | ✓ | ✓ | ✓ |
| `RecruitmentCandidate` | `POST /recruitment/candidates` | — | ✓ | ✓ |
| `RecruitmentCandidateHistory` | — | ✓ | ✓ | — |
| `RecruitmentCandidateInterview` | — | ✓ | — | — |
| `RecruitmentInterviewAttachment` | `POST /recruitment/interviews/%v/attachments` | ✓ | ✓ | ✓ |
| `RecruitmentVacancy` | `POST /recruitment/vacancies` | ✓ | ✓ | ✓ |
| `RecruitmentVacancyAttachment` | `POST /recruitment/vacancy/attachments` | — | — | ✓ |
| `TimeCustomer` | `POST /time/customers` | ✓ | ✓ | ✓ |
| `TimeProject` | `POST /time/projects` | ✓ | ✓ | ✓ |
| `TimeProjectActivitiesCopy` | — | ✓ | — | — |
| `TimeProjectActivity` | `POST /time/project/%v/activities` | ✓ | ✓ | ✓ |
| `TimeValidationActivityName` | — | ✓ | — | — |

A dash is OrangeHRM's limit, not the provider's. `AdminI18nLanguage` has no
create because a language is added by importing a translation file;
`AttendanceEmployeeRecord` has no read because the API reads a day of them at a
time, never one.

The other 234 operations are not things a managed resource calls:

- **170 lists** — `GET /pim/employees`, `/admin/job-titles`,
  `/leave/leave-requests`. Crossplane reads one resource by its external name
  and never lists. This is the bulk of the API: nearly every collection has a
  read of the whole collection, with sorting and paging.
- **40 bulk writes** — a PUT on a collection:
  `/admin/i18n/languages/{id}/translations/bulk`,
  `/attendance/employees/{empNumber}/records`. They write many records in one
  call; a managed resource owns one.
- **21 actions** — `POST /admin/ldap-test-connection`, `/pim/csv-import`,
  `/admin/theme/preview`, `/recruitment/candidates/{id}/shedule-interview`
  (the document's spelling). A verb: nothing there can be read or deleted, so
  there is nothing for Crossplane to own.
- **3 unobservable reads** — `GET /directory/employees/{empNumber}`,
  `/leave/leave-balance/leave-type/{leaveTypeId}` and
  `/recruitment/candidates/{id}`. Each reads ONE record and each describes its
  answer as an array, or — for the candidate — as nothing at all. A controller
  that cannot parse what it read is worse than one that admits it has no read:
  it fails for ever instead of falling back to what Create recorded. So those
  three Kinds have no read; `RecruitmentCandidate` is otherwise complete.

## The generator

`-g orangehrm-crossplane` is a custom openapi-generator generator, built by
`nix build .#orangehrm-codegen` — `javac` against the packaged CLI's own jar and
an SPI entry, no Maven and no checkout of the generator.

It extends upstream's `terraform-provider` generator, which sounds odd for a
Crossplane provider and is the laziest correct choice: the hard part is not
emitting Go, it is deciding which operations are one resource and which of them
is the create, the read, the update and the delete. Both targets ask exactly
that question.

It is [crossplane-provider-rt]'s generator with the RT-shaped rules replaced.
What OrangeHRM needed:

- **A create is a POST on the COLLECTION.** The inverse of RT, where a POST on
  a plural path was a search and a 201 marked the create. Nothing in this
  document answers 201 — every operation answers 200 — so the response code
  cannot tell a create from anything else, and the shape of the path decides:
  a POST on a collection creates, a GET on one lists, a PUT on one is bulk.

- **Every response is an envelope.** `{"data": …, "meta": …}`, so the model the
  document describes for a read is a WRAPPER. Projected onto
  `status.atProvider` as it stands, every Kind observes two fields called
  `Data` and `Meta`. The resource is the `data`: the controller unmarshals the
  envelope and observes what is inside it. By the time a generator runs, that
  envelope has been extracted into `components` and the response holds a `$ref`
  to it, so finding the `data` means following the ref.

- **A delete takes a BODY.** There is no `DELETE /admin/educations/{id}`; there
  is `DELETE /admin/educations` with `{"ids": [7]}`, which is how the API
  deletes one and how it deletes fifty. The external name therefore goes in the
  body, as a NUMBER — the schema says `integer` and a quoted `"7"` is refused.

- **A Kind is named after the whole path, less `/api/v2`.** Left in, it is two
  segments of every Kind's name: `ApiV2AdminEducation`. The leaf alone will not
  do either — languages, attachments, comments, likes and reports each hang off
  several owners — and the tag cannot do it because OrangeHRM tags by screen
  (`PIM/Employee Language`), which is prose. So `AdminEducation`,
  `PimEmployeeLanguage`.

- **Plurals that are not a trailing `s`.** `employment-statuses` is an
  employment status, `nationalities` a nationality, `licenses` a license. The
  `-ses` case is two rules wearing one spelling, decided by the sibilant in
  front of it. Four suffix rules, checked against every plural this document
  spells; see the note in `singular()`.

- **A resource with no read.** `/buzz/shares` is created, updated and deleted
  and never read one at a time, and so are the likes. A collection with a
  create AND a delete is a resource even with nothing to observe; one with
  neither, and no read, is an action.

- **Types the document does not describe.** swagger-php writes out whatever the
  annotation said, and three annotations are wrong: `type: "description"`,
  `type: "boleean"`, and a `maxLength` that is a PHP constant expression, which
  openapi-generator turns into a type called
  `StringMaxLengthOrangeHrmAdminApiSkillApiParamRuleDescriptionMaxLength`. Each
  is a Go type that does not exist and a package that does not compile, so each
  is read back to what the annotation was trying to say.

- **A property with an empty name.** The body of
  `DELETE /pim/employees/{empNumber}/languages` is `{"": [ … ]}` — an
  annotation that lost its key. As a struct field it is a Go file that does not
  parse at all.

- **The struct's field name, not the wire name camelised.** A user is created
  with a `username` and read back with a `userName`, and the immigration body
  has a property actually called `additionalProperties`, which
  openapi-generator renames to `AdditionalPropertiesField` to keep it from
  colliding. Both make `camelize(baseName)` a field that is not there.

- **Floats in a CRD.** A KPI's min and max rating, a salary amount, years of
  experience. `controller-gen` refuses a `float64` by default, because JSON
  numbers round differently across languages — a real ceiling, not a
  formality. `allowDangerousTypes` rather than carrying them as strings, which
  would move the rounding rather than remove it.

Two bugs in the generator this one came from were fixed there rather than
carried over: a nested create interpolated nothing (`fmt.Sprintf("/a/{id}/b",
id)` compiles, and sends a literal `{id}`), and a resource with no read
reported `ResourceExists` on its very first Observe.

The scaffold — `cmd/provider`, the `ProviderConfig`, `Makefile`,
`package/crossplane.yaml` — is generated too, from
`generators/orangehrm/resources/crossplane-provider/`.

### What the generator cannot do

`controller-gen` and `angryjet` need to see the Go **types**, not the API
description, so `make generate` runs them over the output: DeepCopy, the
crossplane-runtime methodsets, and `package/crds`. `bin/generate` does this for
you.

## Credentials

A bearer token, and the endpoint on the ProviderConfig beside it:

```yaml
apiVersion: orangehrm.crossplane.io/v1alpha1
kind: ClusterProviderConfig
metadata:
  name: default
spec:
  endpoint: https://hr.example.com/web/index.php
  credentials:
    source: Secret
    secretRef:
      namespace: orangehrm
      name: orangehrm-api-token
      key: token
```

**A token rather than a client to mint one with**, because OrangeHRM has no
grant a controller can run. `OAuthServer.php` enables exactly two:

```php
$this->oauthServer->enableGrantType($grant, $this->accessTokenTTL);          // AuthCodeGrant
$this->oauthServer->enableGrantType($refreshTokenGrant, $this->accessTokenTTL);
```

The first needs a browser and the second needs the first to have happened, so
nothing here can obtain a token unattended -- `password` and
`client_credentials` are both answered `unsupported_grant_type`. Registering an
OAuth client under **Admin > Configuration > Register OAuth Client** gets you a
client, not a token. A long-lived access token issued to an API user is what
this takes; rotating a refresh token is the upgrade path if those ever expire
on a schedule someone has to chase.

**The endpoint is on the spec, not in the secret**, because it is not one --
and because the token OrangeHRM issues is a bare string. A Secret something
else already made can therefore be pointed at as it is, rather than copied into
a JSON document. A credentials blob that IS a document may carry its own
`endpoint` and `token`; `spec.endpoint` wins where both are set.

```yaml
stringData:
  credentials: |
    {
      "endpoint": "https://hr.example.com/web/index.php",
      "token": "..."
    }
```

Redirects are not followed: an unrecognised token is answered with a 302 to the
login page, which then answers 200 with HTML, so a followed redirect turns a bad
token into "cannot parse the response" somewhere far from the cause.

## What it does not do yet

- The CRD spells its fields the way the API does — `spec.forProvider.empNumber`,
  `spec.forProvider.countryCode`. The wire name is the field name, so nothing
  needs a translation layer and nothing can drift out of one.
- Nested objects and arrays become a `string` holding JSON. `upToDate` compares
  those by value rather than by text, so a re-ordered object coming back is not
  a permanent diff.
- The update sends the CREATE's body. The document describes the two
  separately and they agree everywhere it matters, but a field a PUT requires
  and a POST does not is a field this provider cannot send.
- A create's answer is parsed with the READ's model. For the handful of
  resources where the document gives the two different models — an education
  record is created answering an `Admin-SkillModel`, which is the annotation's
  copy-paste — the fields that overlap are read and the rest ignored.
- A resource whose API has no delete cannot be deleted by Crossplane. Deleting
  the managed resource errors and the finalizer stays, deliberately: succeeding
  would let Crossplane forget something that still exists. Same for a Kind with
  no create, which errors rather than pretending.
- The Kinds with no read are never reported as drifted, and existence is read
  off the Ready condition Create set rather than from the API.
- A few Kinds are a verb the path shape cannot tell from a resource:
  `TimeValidationActivityName` and `TimeProjectActivitiesCopy` read something
  that is a check and a preview rather than a record.
- No acceptance tests against a live OrangeHRM, and no `examples/`. The client's
  own half — the token exchange, the delete body, what counts as gone — is
  covered by `internal/clients/orangehrm/client_test.go`.

[OrangeHRM]: https://www.orangehrm.com/
[zircote/swagger-php]: https://github.com/zircote/swagger-php
[crossplane-provider-rt]: https://github.com/n-at-han-k/crossplane-provider-rt
