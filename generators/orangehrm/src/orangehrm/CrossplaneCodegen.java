package orangehrm;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.openapitools.codegen.CliOption;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.SupportingFile;
import org.openapitools.codegen.languages.TerraformProviderCodegen;
import org.openapitools.codegen.model.ModelMap;
import org.openapitools.codegen.model.ModelsMap;
import org.openapitools.codegen.model.OperationMap;
import org.openapitools.codegen.model.OperationsMap;
import org.openapitools.codegen.utils.CamelizeOption;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.openapitools.codegen.utils.StringUtils.camelize;
import static org.openapitools.codegen.utils.StringUtils.underscore;

/**
 * A Crossplane provider generator, grouped by RESOURCE instead of by tag.
 *
 * It extends the Terraform generator rather than a Go one because the hard
 * part is not emitting Go -- it is deciding which operations are one resource
 * and which of them is the create, the read, the update and the delete.
 * Upstream's {@code terraform-provider} generator already answers that, and a
 * Crossplane managed resource asks the SAME question: one collection path plus
 * its member path is one Kind, the create body is what a person may write
 * (spec.forProvider), and the read response is what the server answers
 * (status.atProvider).
 *
 * What is replaced is the whole template set and the classification of each
 * attribute, because the two targets disagree about one thing: Terraform has
 * Optional+Computed for a value a person may write and the server may answer,
 * and Crossplane splits that value across spec and status instead.
 *
 * The grouping hook is the same one as
 * github.com/n-at-han-k/crossplane-provider-wso2. Upstream keys its operation
 * map on the TAG, and OrangeHRM tags by screen -- {@code Admin/Education},
 * {@code PIM/Employee Language} -- so a tag is neither one resource nor a
 * stable name. The collection path is both, so it is the key.
 *
 * OrangeHRM's REST v2 is regular in a way RT is not, and two of its
 * regularities are the whole reason this generator differs from that one:
 *
 * <ul>
 *   <li><b>Every response is an envelope.</b> {@code {"data": …, "meta": …}},
 *       so the model the document describes for a read is a wrapper and the
 *       resource is its {@code data}.</li>
 *   <li><b>A delete takes a body.</b> There is no
 *       {@code DELETE /admin/educations/{id}} -- there is
 *       {@code DELETE /admin/educations} with {@code {"ids": [7]}}, which is
 *       also how the API deletes several at once.</li>
 * </ul>
 */
public class CrossplaneCodegen extends TerraformProviderCodegen {

    public static final String RESOURCE_PATHS = "resourcePaths";
    public static final String PROVIDER_NAME = "providerName";
    public static final String GROUP_NAME = "groupName";
    public static final String API_VERSION = "apiVersion";
    public static final String PATH_PREFIX = "pathPrefix";

    /**
     * Not a comma: --additional-properties is itself comma-separated, so a
     * comma here ends the property rather than separating two paths.
     */
    private static final String SEPARATOR = "[;|\\s]+";

    /** Collection paths to generate; empty means every path in the document. */
    private final Set<String> wanted = new LinkedHashSet<>();

    private String providerName = "orangehrm";
    private String groupName = "orangehrm.crossplane.io";
    private String apiVersion = "v1alpha1";
    /**
     * Stripped before a path is turned into a Kind. Every OrangeHRM path
     * begins {@code /api/v2}, which would otherwise be two segments of every
     * single Kind name -- {@code ApiV2AdminEducation}.
     */
    private String pathPrefix = "/api/v2";

    public CrossplaneCodegen() {
        super();
        cliOptions.add(new CliOption(RESOURCE_PATHS,
                "Collection paths to generate as managed resources, separated by ';' (default: all)"));
        cliOptions.add(new CliOption(GROUP_NAME, "The CRD API group (default: orangehrm.crossplane.io)"));
        cliOptions.add(new CliOption(API_VERSION, "The CRD API version (default: v1alpha1)"));
        cliOptions.add(new CliOption(PATH_PREFIX,
                "A path prefix to strip when naming Kinds (default: /api/v2)"));
    }

    @Override
    public String getName() {
        return "orangehrm-crossplane";
    }

    @Override
    public String getHelp() {
        return "Generates a Crossplane provider, one managed resource per collection path.";
    }

    @Override
    public void processOpts() {
        super.processOpts();

        // Upstream's templates emit a Terraform provider; none of them apply.
        // The directory is ours, so nothing resolves out of the CLI's jar.
        templateDir = "crossplane-provider";
        embeddedTemplateDir = "crossplane-provider";
        apiTemplateFiles.clear();
        modelTemplateFiles.clear();
        supportingFiles.clear();

        if (additionalProperties.containsKey(PROVIDER_NAME)) {
            providerName = additionalProperties.get(PROVIDER_NAME).toString();
        }
        if (additionalProperties.containsKey(GROUP_NAME)) {
            groupName = additionalProperties.get(GROUP_NAME).toString();
        }
        if (additionalProperties.containsKey(API_VERSION)) {
            apiVersion = additionalProperties.get(API_VERSION).toString();
        }
        if (additionalProperties.containsKey(PATH_PREFIX)) {
            pathPrefix = additionalProperties.get(PATH_PREFIX).toString();
        }
        additionalProperties.put(PROVIDER_NAME, providerName);
        additionalProperties.put(GROUP_NAME, groupName);
        additionalProperties.put(API_VERSION, apiVersion);
        // How the provider is spelled in prose. camelize() of the package name
        // is the fallback and it is often wrong -- `rt` camelises to `Rt`,
        // an abbreviation no document spells that way -- so it can be given.
        if (!additionalProperties.containsKey("providerTitle")) {
            additionalProperties.put("providerTitle", camelize(providerName));
        }

        // Three files per resource, in three different trees. Routed by
        // apiFilename below, since apiTemplateFiles carries only a suffix.
        apiTemplateFiles.put("types.mustache", "_types.go");
        apiTemplateFiles.put("groupversion.mustache", "groupversion_info.go");
        apiTemplateFiles.put("controller.mustache", ".go");

        // The request and response bodies, as Go structs. This is the one
        // thing upstream's Terraform generator emits that a Crossplane
        // provider wants unchanged: internal/clients/<provider>/model_*.go.
        modelTemplateFiles.put("model.mustache", ".go");

        supportingFiles.add(new SupportingFile("gomod.mustache", "", "go.mod"));
        supportingFiles.add(new SupportingFile("main.mustache",
                "cmd" + File.separator + "provider", "main.go"));
        supportingFiles.add(new SupportingFile("client.mustache",
                clientFolder(), "client.go"));
        supportingFiles.add(new SupportingFile("scheme.mustache",
                "apis", providerName + ".go"));
        supportingFiles.add(new SupportingFile("generate_go.mustache", "apis", "generate.go"));
        supportingFiles.add(new SupportingFile("controllers.mustache",
                "internal" + File.separator + "controller", providerName + ".go"));

        // The ProviderConfig: not derived from the document, but its group and
        // its categories are the provider's, so it is generated rather than
        // committed by hand.
        String pcFolder = "apis" + File.separator + apiVersion;
        supportingFiles.add(new SupportingFile("providerconfig_doc.mustache", pcFolder, "doc.go"));
        supportingFiles.add(new SupportingFile("providerconfig_types.mustache", pcFolder, "types.go"));
        supportingFiles.add(new SupportingFile("providerconfig_register.mustache", pcFolder, "register.go"));
        supportingFiles.add(new SupportingFile("config.mustache",
                "internal" + File.separator + "controller" + File.separator + "config", "config.go"));
        supportingFiles.add(new SupportingFile("version.mustache",
                "internal" + File.separator + "version", "version.go"));

        supportingFiles.add(new SupportingFile("Makefile.mustache", "", "Makefile"));

        // The image the xpkg wraps. build/makelib/imagelight.mk expects every
        // image at cluster/images/<name>/, and without it `make build` fails
        // after a clean compile with "No such file" -- a provider that cannot
        // be packaged is not finished, so the scaffold generates this too.
        String imageFolder = "cluster" + File.separator + "images"
                + File.separator + "provider-" + providerName;
        supportingFiles.add(new SupportingFile("image_dockerfile.mustache", imageFolder, "Dockerfile"));
        supportingFiles.add(new SupportingFile("image_makefile.mustache", imageFolder, "Makefile"));
        supportingFiles.add(new SupportingFile("crossplane_yaml.mustache", "package", "crossplane.yaml"));
        supportingFiles.add(new SupportingFile("boilerplate.mustache", "hack", "boilerplate.go.txt"));
        supportingFiles.add(new SupportingFile("gitmodules.mustache", "", ".gitmodules"));

        Object paths = additionalProperties.get(RESOURCE_PATHS);
        if (paths != null && !paths.toString().isEmpty()) {
            Arrays.stream(paths.toString().split(SEPARATOR))
                    .map(String::trim)
                    .filter(path -> !path.isEmpty())
                    .forEach(wanted::add);
        }
    }

    private String clientFolder() {
        return "internal" + File.separator + "clients" + File.separator + providerName;
    }

    /**
     * Each of the three per-resource templates lands in its own tree:
     *
     *   types, groupversion -> apis/<kind>/<version>/
     *   controller          -> internal/controller/<kind>/
     *
     * {@code apiTemplateFiles} carries only a suffix and {@code apiFileFolder()}
     * is one directory for all of them, so the routing happens here.
     */
    @Override
    public String apiFilename(String templateName, String tag) {
        String kind = underscore(toApiName(tag)).toLowerCase(Locale.ROOT);
        String suffix = apiTemplateFiles.get(templateName);

        if ("groupversion.mustache".equals(templateName)) {
            return outputFolder + File.separator + "apis" + File.separator + kind
                    + File.separator + apiVersion + File.separator + suffix;
        }
        if ("types.mustache".equals(templateName)) {
            return outputFolder + File.separator + "apis" + File.separator + kind
                    + File.separator + apiVersion + File.separator + kind + suffix;
        }
        return outputFolder + File.separator + "internal" + File.separator + "controller"
                + File.separator + kind + File.separator + kind + suffix;
    }

    @Override
    public String modelFileFolder() {
        return outputFolder + File.separator + clientFolder();
    }

    @Override
    public String toModelFilename(String name) {
        return "model_" + underscore(name);
    }

    /**
     * The group key is the collection path, so the member operations land with
     * the collection's own. {@code co.baseName} is the collection segment,
     * because that is what {@code pathWithoutBaseName()} strips.
     */
    @Override
    public void addOperationToGroup(String tag, String resourcePath, Operation operation,
                                    CodegenOperation co, Map<String, List<CodegenOperation>> operations) {
        String collection = collectionOf(resourcePath);

        // OrangeHRM spells one resource as a collection path and its member
        // path, and the four operations are always in the same places:
        //
        //   POST   /admin/educations        the create
        //   GET    /admin/educations/{id}   the read
        //   PUT    /admin/educations/{id}   the update
        //   DELETE /admin/educations        the delete, with a body of ids
        //
        // which is the INVERSE of RT, where a POST on a plural path was a
        // search. Nothing in this document answers 201 -- every operation
        // answers 200 -- so a response code cannot tell a create from a
        // search here and the shape of the path has to.
        //
        // What is dropped is a GET on a collection (a list, which a managed
        // resource never calls -- Crossplane reads one resource by its
        // external name) and a PUT on one, which is a bulk endpoint:
        // /admin/i18n/languages/{languageId}/translations/bulk.
        String method = co.httpMethod.toUpperCase(Locale.ROOT);
        boolean member = isMember(collection, resourcePath);
        boolean keep = member
                ? ("GET".equals(method) && observable(operation))
                        || "PUT".equals(method) || "DELETE".equals(method)
                : "POST".equals(method) || "DELETE".equals(method);

        if (!keep) {
            return;
        }

        if (!describesAResource(collection)) {
            return;
        }

        List<CodegenOperation> group =
                operations.computeIfAbsent(canonicalCollection(collection), key -> new ArrayList<>());

        // An operation carrying two tags is offered once per tag; here both
        // offers name the same group, so the second one is a duplicate.
        if (group.stream().anyMatch(existing -> existing.operationId.equals(co.operationId))) {
            return;
        }

        group.add(co);
        co.baseName = lastSegment(collection);
    }

    /**
     * Whether a read answers something status.atProvider can hold ONE of.
     *
     * The document is sloppy about this in three places, and each would
     * generate a controller that cannot parse what it just read: a read of one
     * employee directory listing says it answers an ARRAY, so does a read of
     * one leave balance, and a read of one candidate describes its {@code
     * data} as nothing at all. A read that cannot be projected onto a single
     * resource's status is worse than no read -- Observe would fail for ever
     * rather than fall back to what Create recorded -- so it is dropped here,
     * and hack/check-coverage.py spells the same rule so the two must agree.
     */
    private boolean observable(Operation operation) {
        Schema<?> data = dataSchema(operation);

        if (data == null || "array".equals(data.getType())) {
            return false;
        }

        return data.get$ref() != null
                || (data.getProperties() != null && !data.getProperties().isEmpty())
                || data.getAllOf() != null || data.getOneOf() != null || data.getAnyOf() != null;
    }

    /** The {@code data} of a 200's envelope, as the document describes it. */
    private Schema<?> dataSchema(Operation operation) {
        if (operation == null || operation.getResponses() == null) {
            return null;
        }

        ApiResponse ok = operation.getResponses().get("200");
        if (ok == null || ok.getContent() == null) {
            return null;
        }

        MediaType json = ok.getContent().get("application/json");
        if (json == null) {
            return null;
        }

        // Through resolve(), because by the time a generator runs, the inline
        // envelope has been EXTRACTED into components and the response holds a
        // $ref to it -- `GetACustomer200Response`. Read straight off, every
        // envelope looks like a schema with no properties at all, and every
        // read in the document looks unobservable.
        Schema<?> envelope = resolve(json.getSchema());
        if (envelope == null || envelope.getProperties() == null) {
            return null;
        }

        Object data = envelope.getProperties().get("data");

        return data instanceof Schema ? (Schema<?>) data : null;
    }

    /** A schema, following a {@code $ref} into the document's components. */
    private Schema<?> resolve(Schema<?> schema) {
        for (int hops = 0; schema != null && schema.get$ref() != null && hops < 8; hops++) {
            if (openAPI == null || openAPI.getComponents() == null
                    || openAPI.getComponents().getSchemas() == null) {
                return null;
            }

            String ref = schema.get$ref();
            schema = openAPI.getComponents().getSchemas().get(ref.substring(ref.lastIndexOf('/') + 1));
        }

        return schema;
    }

    /**
     * Whether a collection describes a RESOURCE, or a verb spelled as a path.
     *
     * A read settles it: a member GET means there is something to observe. A
     * collection with no read is still a resource when it can be both created
     * and destroyed -- {@code /buzz/shares} is created, updated and deleted
     * and never read one at a time, and the controller's Observe copes with
     * that (see the no-read branch there).
     *
     * What this drops is the POST-only endpoints, which are actions rather
     * than resources: {@code /admin/ldap-test-connection},
     * {@code /pim/csv-import}, {@code /admin/theme/preview},
     * {@code /recruitment/candidates/{id}/shedule-interview}. Nothing can
     * read or delete one, so there is nothing for Crossplane to own.
     *
     * <p>It is also what keeps {@link #canonicalCollection} from keying a
     * Kind on a path that describes nothing.
     */
    private boolean describesAResource(String collection) {
        if (openAPI == null || openAPI.getPaths() == null) {
            return true;
        }

        boolean create = false;
        boolean delete = false;

        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            String path = entry.getKey();

            if (!collectionOf(path).equals(collection)) {
                continue;
            }

            boolean member = isMember(collection, path);

            for (Map.Entry<PathItem.HttpMethod, Operation> described
                    : entry.getValue().readOperationsMap().entrySet()) {
                String method = described.getKey().name();

                if (member && "GET".equals(method) && observable(described.getValue())) {
                    return true;
                }
                if (!member && "POST".equals(method)) {
                    create = true;
                }
                if ("DELETE".equals(method)) {
                    delete = true;
                }
            }
        }

        return create && delete;
    }

    /** The collection every group of this Kind is keyed on. */
    private String canonicalCollection(String collection) {
        if (openAPI == null || openAPI.getPaths() == null) {
            return collection;
        }

        String kind = toApiName(collection);
        String canonical = collection;

        for (String path : openAPI.getPaths().keySet()) {
            String other = collectionOf(path);

            if (other.equals(canonical) || !toApiName(other).equals(kind)
                    || !describesAResource(other)) {
                continue;
            }

            // The member path is what the resource is addressed by, so its
            // collection is the one to key on; failing that, take the same
            // one every time rather than whichever was seen first.
            boolean canonicalAddresses = addressedByAMember(canonical);
            boolean otherAddresses = addressedByAMember(other);

            if (otherAddresses && !canonicalAddresses) {
                canonical = other;
            } else if (otherAddresses == canonicalAddresses && other.compareTo(canonical) < 0) {
                canonical = other;
            }
        }

        return canonical;
    }

    private boolean addressedByAMember(String collection) {
        return openAPI.getPaths().keySet().stream()
                .anyMatch(path -> collectionOf(path).equals(collection) && isMember(collection, path));
    }

    /**
     * The collection a group is about: the one its member path hangs off,
     * because that is what the resource is addressed by. With no member path
     * -- a set, like /group/{id}/members -- the shortest path will do, since
     * they are all the same one.
     */
    private String collectionOf(List<CodegenOperation> group) {
        String shortest = null;

        for (CodegenOperation op : group) {
            String collection = collectionOf(op.path);

            if (!collection.equals(op.path)) {
                return collection;
            }
            if (shortest == null || collection.length() < shortest.length()) {
                shortest = collection;
            }
        }

        return shortest == null ? "" : shortest;
    }

    /**
     * Which operation is the create, the read, the update, the delete.
     *
     * Upstream asks {@code CodegenOperation.isRestfulCreate()} and friends,
     * and those cannot answer for a NESTED resource: {@code isMemberPath()}
     * opens with {@code if (pathParams.size() != 1) return false}, so
     * a nested member path with two path params looks like nothing at all,
     * and the whole resource comes out empty.
     *
     * The shape of the path already says it. This group IS a collection path
     * and its member path, so an operation on the collection is the create or
     * the list, and one on the member is the read, the update or the delete.
     * Marked as vendor extensions, which is upstream's own first-pass hook.
     *
     * The update is the member PUT, and its body is the create's -- the
     * document describes the two separately and they agree everywhere it
     * matters. A field a PUT requires and a POST does not would be a field
     * this provider cannot send; see the README.
     */
    @Override
    public OperationsMap postProcessOperationsWithModels(OperationsMap objs, List<ModelMap> allModels) {
        List<CodegenOperation> group = objs.getOperations().getOperation();
        String collection = collectionOf(group);

        CodegenOperation update = null;

        // A member DELETE where the document has one -- three paths do
        // (/buzz/shares/{id}) -- and the collection DELETE otherwise, which is
        // how OrangeHRM deletes everything else and takes the id in a BODY
        // rather than in the path.
        boolean memberDelete = group.stream().anyMatch(op -> isMember(collection, op.path)
                && "DELETE".equals(op.httpMethod.toUpperCase(Locale.ROOT)));
        boolean deleteByIDs = false;

        for (CodegenOperation op : group) {
            boolean member = isMember(collection, op.path);
            String method = op.httpMethod.toUpperCase(Locale.ROOT);

            if (member) {
                if ("GET".equals(method)) {
                    op.vendorExtensions.put("x-terraform-is-read", true);
                } else if ("DELETE".equals(method)) {
                    op.vendorExtensions.put("x-terraform-is-delete", true);
                } else if ("PUT".equals(method)) {
                    update = op;
                }
            } else if ("POST".equals(method)) {
                op.vendorExtensions.put("x-terraform-is-create", true);
            } else if ("DELETE".equals(method) && !memberDelete) {
                op.vendorExtensions.put("x-terraform-is-delete", true);
                deleteByIDs = true;
            }
        }

        if (update != null) {
            update.vendorExtensions.put("x-terraform-is-update", true);
        }

        OperationsMap processed = super.postProcessOperationsWithModels(objs, allModels);
        OperationMap operations = processed.getOperations();

        // Upstream leaves the CREATE path's parameters spelled `{applicationId}`
        // while converting the read, update and delete paths to `%v` -- it has
        // never had to interpolate a create, because the only create it can
        // recognise is on a top-level collection that takes no parameters. A
        // nested create then goes out to a URL with a literal `{idOrName}` in
        // it, which `fmt.Sprintf` COMPILES (with a trailing %!(EXTRA)) and the
        // server answers 404 for. Every path gets the same treatment here.
        for (String key : new String[] {"createPath", "readPath", "updatePath", "deletePath"}) {
            Object path = operations.get(key);
            if (path != null) {
                operations.put(key, path.toString().replaceAll("\\{[^}]*\\}", "%v"));
            }
        }

        split(operations, allModels, collection);

        // The delete takes {"ids": [...]} on the COLLECTION rather than an id
        // in the path, so the template sends a body and interpolates only the
        // owning ids into the URL.
        operations.put("deleteByIDs", deleteByIDs);

        // From the COLLECTION PATH, not from resourceClassName: upstream
        // derives that one from the response model's name, which does not
        // always match the file the resource is written to, and a package
        // that does not exist is a build that does not run.
        String kind = toApiName(collection);
        operations.put("kind", kind);
        operations.put("kindLower", kind.toLowerCase(Locale.ROOT));
        operations.put("kindPackage", underscore(kind).toLowerCase(Locale.ROOT));
        operations.put("kindCamel", camelize(kind, CamelizeOption.LOWERCASE_FIRST_LETTER));

        // fmt is imported only to interpolate an id into a member path, so a
        // resource whose paths take no parameters must not import it.
        operations.put("needsFmt", truthy(operations.get("readHasPathParams"))
                || truthy(operations.get("updateHasPathParams"))
                || truthy(operations.get("deleteHasPathParams"))
                // A nested collection interpolates its owning ids into the
                // CREATE path too.
                || truthy(operations.get("hasParents")));

        return processed;
    }

    /**
     * Terraform has ONE schema and marks a value Optional+Computed when a
     * person may write it and the server may also answer it. Crossplane has
     * two: spec.forProvider is what a person writes and status.atProvider is
     * what the server answers, and the same field appearing in both is normal
     * rather than a conflict.
     *
     * So the split is simply: the create request body is
     * {@code <Kind>Parameters}, and the read response is
     * {@code <Kind>Observation}. Nothing has to be guessed about which of them
     * a field belongs to, which is the one thing the Terraform generator could
     * not do -- it had to infer Computed from "the response has it and the
     * request does not", and got `id` and `employeeId` asked for in
     * configuration when the document never says readOnly, which this one
     * never does.
     *
     * A parameter the create body requires is Required; everything else is
     * optional and carries omitempty. Anything that is not a scalar becomes a
     * JSON string for now, as in the Terraform provider -- see the README.
     */
    private void split(OperationMap operations, List<ModelMap> allModels, String collection) {
        // EVERY OrangeHRM response is an envelope -- {"data": …, "meta": …} --
        // so the model the document describes for a read is a WRAPPER, and
        // projecting it onto status.atProvider would give every Kind two
        // fields called Data and Meta. What the resource IS is the `data`: the
        // envelope is what the controller unmarshals and the data model is
        // what it observes.
        CodegenModel envelope = modelNamed(allModels, String.valueOf(operations.get("responseModel")));
        CodegenModel response = dataOf(envelope, allModels);

        if (response != null) {
            operations.put("envelopeModel", envelope.classname);
            operations.put("responseModel", response.classname);
        }

        CodegenModel request = modelNamed(allModels, (String) operations.get("requestModel"));

        List<Map<String, Object>> parameters = new ArrayList<>();
        List<Map<String, Object>> observations = new ArrayList<>();

        // What the READ answers, by name, so a parameter can be told whether
        // there is anything to diff it against.
        Map<String, CodegenProperty> answered = new LinkedHashMap<>();
        if (response != null) {
            for (CodegenProperty property : response.vars) {
                answered.put(property.baseName.toLowerCase(Locale.ROOT), property);
            }
        }

        if (request != null) {
            for (CodegenProperty property : request.vars) {
                Map<String, Object> field = field(property.baseName, property.dataType,
                        property.description, property.required);
                // The STRUCT's field name, not camelize() of the wire name.
                // openapi-generator renames a field that would collide with
                // something Go or the generator itself needs: the immigration
                // body has a property actually called `additionalProperties`,
                // and the struct spells it AdditionalPropertiesField.
                field.put("goName", property.name);
                field.put("isSensitive", property.isWriteOnly
                        || property.baseName.toLowerCase(Locale.ROOT).contains("password")
                        || property.baseName.toLowerCase(Locale.ROOT).contains("secret"));

                // Comparable only where the server answers the field under the
                // same name AND the same shape. An employee goes out with a
                // `middleName` and comes back with an `employeeId` the server
                // assigned, and a create body carries plenty the read never
                // echoes -- diffing those would report drift for ever.
                CodegenProperty answer = answered.get(property.baseName.toLowerCase(Locale.ROOT));
                boolean sameShape = answer != null && answer.dataType.equals(property.dataType);
                // A secret the server never echoes cannot be diffed either.
                field.put("comparable", sameShape && !Boolean.TRUE.equals(field.get("isSensitive")));
                // What the RESPONSE calls it, which is not always what the
                // request does: a user is created with a `username` and read
                // back with a `userName`.
                field.put("observedName", answer == null ? property.name : answer.name);

                parameters.add(field);
            }
        }

        // Everything the server answers is observable, including the fields a
        // person also writes: status.atProvider is what IS, not what was asked
        // for, and the controller diffs the two.
        for (CodegenProperty property : answered.values()) {
            Map<String, Object> field = field(property.baseName, property.dataType,
                    property.description, false);
            field.put("goName", property.name);
            observations.add(field);
        }

        // A document can describe an operation with no body at all -- a POST
        // that takes nothing, a GET whose 200 has no schema. Upstream still
        // reports hasCreate and hasRead for those, and the templates would
        // then spell `orangehrm.` where a type name belongs. So what the
        // templates are told is not "is there an operation" but "is there a
        // TYPE".
        String requestModel = String.valueOf(operations.get("requestModel"));
        boolean hasRequestModel = request != null && !requestModel.isEmpty()
                && !"null".equals(requestModel) && !isNotAStruct(requestModel);
        boolean hasResponseModel = response != null;

        operations.put("hasRequestModel", hasRequestModel);
        operations.put("hasResponseModel", hasResponseModel);

        // Nothing to unmarshal a read into is the same as having no read: the
        // controller cannot observe anything either way, and saying so here
        // keeps the branch out of every template.
        if (!hasResponseModel) {
            operations.put("hasRead", false);
        }

        // The identifier has to exist ON THE DATA MODEL, not merely be named:
        // a model that carries no id makes `created.Id` a line that does not
        // compile. OrangeHRM spells it `id` and numbers it, everywhere -- but
        // not everything it answers has one (a config object, a balance).
        String idField = "";
        if (response != null) {
            for (CodegenProperty property : response.vars) {
                if ("id".equalsIgnoreCase(property.baseName)) {
                    idField = camelize(property.baseName);
                    break;
                }
            }
        }
        operations.put("idFieldExported", idField);
        operations.put("hasID", hasResponseModel && !idField.isEmpty());

        operations.put("comparables",
                parameters.stream().filter(f -> Boolean.TRUE.equals(f.get("comparable"))).toList());

        // The owning ids come FIRST in the struct and in every path, because
        // that is the order the path spells them.
        List<Map<String, Object>> parents = parents(collection);
        Set<String> named = new HashSet<>();
        for (Map<String, Object> parent : parents) {
            named.add(String.valueOf(parent.get("goName")));
        }
        // A request body that already carries the owning id wins: it is the
        // document's own spelling, and two struct fields cannot share a name.
        parameters.removeIf(f -> named.contains(String.valueOf(f.get("goName"))));
        parameters.addAll(0, parents);

        operations.put("parents", parents);
        operations.put("hasParents", !parents.isEmpty());
        operations.put("parameters", parameters);
        operations.put("observations", observations);
        operations.put("hasParameters", !parameters.isEmpty());
        operations.put("hasObservations", !observations.isEmpty());
        operations.put("anyJson", parameters.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isJson")))
                || observations.stream().anyMatch(f -> Boolean.TRUE.equals(f.get("isJson"))));
    }

    /**
     * The path parameters of the COLLECTION path, in order: everything that
     * has to be known before this resource can even be addressed.
     *
     * {@code /user/{idOrName}/groups} is owned by a user, so a UserGroup
     * carries a required, immutable {@code idOrName} -- there is nowhere else
     * for the controller to get it, and changing it would address a different
     * user's memberships rather than modify these.
     */
    private List<Map<String, Object>> parents(String collection) {
        List<Map<String, Object>> parents = new ArrayList<>();

        for (String segment : collection.split("/")) {
            if (!segment.startsWith("{") || !segment.endsWith("}")) {
                continue;
            }

            String param = segment.substring(1, segment.length() - 1).replace('-', '_');
            Map<String, Object> field = field(camelize(param, CamelizeOption.LOWERCASE_FIRST_LETTER),
                    "string", "The identifier of the owning resource, from the path.", true);
            field.put("isParent", true);
            field.put("comparable", false);
            parents.add(field);
        }

        return parents;
    }

    /** One field of a Parameters or an Observation struct. */
    private Map<String, Object> field(String baseName, String dataType, String description, boolean required) {
        Map<String, Object> field = new HashMap<>();

        boolean scalar = isScalar(dataType);

        field.put("name", baseName);
        // The JSON tag is the wire name VERBATIM, so the CRD field, the Go
        // struct and the request body all spell it the same way and nothing
        // needs a translation layer.
        field.put("jsonName", baseName);
        field.put("goName", camelize(baseName));
        String goType = scalar ? goType(dataType) : "string";

        field.put("goType", goType);
        // The client struct keeps the document's own width; a CRD does not,
        // because Kubernetes has no int32. So the two disagree for exactly the
        // narrow numbers, and the controller converts rather than the schema
        // lying about what the API takes.
        field.put("clientType", scalar ? dataType : "");
        field.put("needsCast", scalar && !goType.equals(dataType));
        // What "the person did not set this" looks like for this type, so
        // upToDate can tell an unset optional field from a difference. The
        // server fills in plenty nobody asked for -- a job title's note, an
        // employee's status -- and without this every such field is permanent
        // drift and the controller updates on every single reconcile.
        //
        // A bool has no spare value to mean unset, so it is always compared.
        switch (goType) {
            case "string":  field.put("zeroCheck", "!= \"\""); break;
            case "int64":
            case "float64": field.put("zeroCheck", "!= 0"); break;
            default:        break;
        }

        field.put("description", description == null || "null".equals(description) ? "" : description);
        field.put("isRequired", required);
        field.put("isJson", !scalar);
        field.put("isSensitive", false);

        return field;
    }

    private boolean truthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
    }

    private boolean isScalar(String dataType) {
        switch (dataType == null ? "" : dataType) {
            case "string": case "bool":
            case "int": case "int32": case "int64":
            case "float32": case "float64":
                return true;
            default:
                return false;
        }
    }

    private String goType(String dataType) {
        switch (dataType == null ? "" : dataType) {
            case "int": case "int32": case "int64": return "int64";
            case "float32": case "float64": return "float64";
            case "bool": return "bool";
            default: return dataType;
        }
    }

    /**
     * The model inside a response envelope: the type of its {@code data}.
     *
     * {@code null} where there is no single object to observe -- no envelope
     * at all, or a {@code data} that is an ARRAY (a list, or the handful of
     * creates that answer several records at once). {@code []AdminSkillModel}
     * is not something status.atProvider can hold one of.
     */
    private CodegenModel dataOf(CodegenModel envelope, List<ModelMap> allModels) {
        if (envelope == null) {
            return null;
        }

        for (CodegenProperty property : envelope.vars) {
            if (!"data".equals(property.baseName)) {
                continue;
            }
            if (property.dataType == null || isNotAStruct(property.dataType)) {
                return null;
            }
            return modelNamed(allModels, property.dataType);
        }

        return null;
    }

    private CodegenModel modelNamed(List<ModelMap> allModels, String classname) {
        if (classname == null) {
            return null;
        }
        for (ModelMap map : allModels) {
            if (classname.equals(map.getModel().classname)) {
                return map.getModel();
            }
        }
        return null;
    }

    /**
     * Every field omitempty: a Crossplane parameter that is optional and unset
     * is the Go zero value, and without omitempty that goes out as
     * {@code "status": ""} -- a field the create endpoint never asked for and
     * can refuse over.
     */
    @Override
    public ModelsMap postProcessModels(ModelsMap objs) {
        ModelsMap processed = super.postProcessModels(objs);

        for (ModelMap map : processed.getModels()) {
            CodegenModel model = map.getModel();

            // A property with an EMPTY NAME. The document has one: the body of
            // DELETE /pim/employees/{empNumber}/languages is `{"": [ … ]}`, a
            // swagger-php annotation that lost its key. Rendered as a struct
            // field it is a Go file that does not parse at all -- and there is
            // nothing to send under a name that does not exist.
            model.vars.removeIf(property -> property.baseName == null || property.baseName.isEmpty());
            if (model.allVars != null) {
                model.allVars.removeIf(property -> property.baseName == null
                        || property.baseName.isEmpty());
            }

            // A schema with no fields of its own OR inherited is not an
            // object this client can hold: `ticketLink` is `anyOf: [integer,
            // array]` and the parameter schemas are `oneOf: [integer,
            // string]`. Rendered as an empty struct, unmarshalling a number
            // into it fails outright -- which is the same failure that
            // duplicated seven assets, one level down.
            model.vendorExtensions.put("x-opaque",
                    !model.isEnum && !model.isAlias
                            && (model.allVars == null || model.allVars.isEmpty()));

            // vars AND allVars: the inherited half of an `allOf` is a
            // SEPARATE CodegenProperty instance, and the template renders
            // allVars. Rewriting only vars leaves those without a json tag
            // and with the types this fixes up below.
            List<CodegenProperty> properties = new ArrayList<>(model.vars);
            if (model.allVars != null) {
                properties.addAll(model.allVars);
            }

            for (CodegenProperty property : properties) {
                // OpenAPI 3.1 lets a schema carry a type AND an `anyOf`
                // that only narrows it -- a string that is also an email, a
                // number that is also a date. openapi-generator names that
                // composition `AnyOf` and emits it as the Go type, which is
                // not a type and does not compile. The declared type is still
                // on the property, so it is used; a genuine union -- one that
                // declares no type of its own -- has nothing better than
                // `interface{}` and travels as JSON.
                if (property.dataType != null
                        && (property.dataType.startsWith("AnyOf") || property.dataType.startsWith("OneOf"))) {
                    property.dataType = unionType(property);
                } else if (isGenuineUnion(property)) {
                    // A composition with BRANCHES, as opposed to the
                    // validation-only kind above. openapi-generator collapses
                    // one to whichever branch it saw last, and the client then
                    // fails to parse whatever the API sends down the other.
                    property.dataType = "interface{}";
                }

                property.vendorExtensions.put("x-go-datatag",
                        " `json:\"" + property.baseName + ",omitempty\"`");
            }
        }

        return processed;
    }

    /** A composition with branches that disagree, not one that only narrows. */
    private boolean isGenuineUnion(CodegenProperty property) {
        if (property.getComposedSchemas() == null) {
            return false;
        }

        List<CodegenProperty> anyOf = property.getComposedSchemas().getAnyOf();
        List<CodegenProperty> oneOf = property.getComposedSchemas().getOneOf();

        return (anyOf != null && anyOf.size() > 1) || (oneOf != null && oneOf.size() > 1);
    }

    /** The declared type behind a composition, or {@code interface{}}. */
    private String unionType(CodegenProperty property) {
        if (property.isString) {
            return "string";
        }
        if (property.isInteger || property.isLong) {
            return "int64";
        }
        if (property.isNumber || property.isFloat || property.isDouble) {
            return "float64";
        }
        if (property.isBoolean) {
            return "bool";
        }
        return "interface{}";
    }

    /**
     * What a type the document never describes was trying to say, read off the
     * name the annotation gave it: {@code "string, maxLength=…"} is a string,
     * {@code "boleean"} is a bool. Anything else travels as JSON rather than
     * as a guess -- {@code type: "description"} says nothing about a shape.
     */
    private String undescribedType(CodegenProperty property) {
        String declared = property.openApiType == null
                ? "" : property.openApiType.toLowerCase(Locale.ROOT);

        if (declared.startsWith("string")) {
            return "string";
        }
        if (declared.startsWith("bool") || declared.startsWith("bolee")) {
            return "bool";
        }
        if (declared.startsWith("int")) {
            return "int64";
        }
        if (declared.startsWith("number") || declared.startsWith("float")
                || declared.startsWith("double")) {
            return "float64";
        }

        return "interface{}";
    }

    /** A model's own fields and the ones an {@code allOf} left in the parent. */
    private List<CodegenProperty> propertiesOf(CodegenModel model) {
        List<CodegenProperty> properties = new ArrayList<>(model.vars);

        if (model.allVars != null) {
            properties.addAll(model.allVars);
        }

        return properties;
    }

    /**
     * A field whose type disagrees across a union's branches holds neither.
     *
     * openapi-generator flattens an inline {@code anyOf} into ONE struct and
     * gives each field the type of whichever branch it saw last. A field the
     * branches disagree about then holds neither: unmarshalling fails on
     * whichever half of the responses the losing branch described, and a
     * create whose RESPONSE will not parse is a resource that exists with
     * nothing here recording it -- so the next reconcile creates another one.
     *
     * This runs over ALL models, because the branches are models of their own
     * and a single model cannot see them.
     */
    @Override
    public Map<String, ModelsMap> postProcessAllModels(Map<String, ModelsMap> models) {
        Map<String, ModelsMap> processed = super.postProcessAllModels(models);

        Map<String, CodegenModel> byName = new LinkedHashMap<>();
        for (ModelsMap entry : processed.values()) {
            for (ModelMap map : entry.getModels()) {
                byName.put(map.getModel().classname, map.getModel());
            }
        }

        // A property whose type NAMES SOMETHING THE DOCUMENT NEVER DESCRIBES.
        // swagger-php writes out whatever the annotation said, and three
        // annotations in this document are wrong: `type: "description"`,
        // `type: "boleean"`, and a `maxLength` that is a PHP constant
        // expression, which openapi-generator dutifully turns into a type
        // called StringMaxLengthOrangeHrmAdminApiSkillApiParamRule…. Each is a
        // Go type that does not exist and a package that does not compile, so
        // each is read back to what the annotation was trying to say.
        for (CodegenModel model : byName.values()) {
            for (CodegenProperty property : propertiesOf(model)) {
                String element = property.dataType == null ? "" : property.dataType;

                while (element.startsWith("[]")) {
                    element = element.substring(2);
                }
                if (element.startsWith("map[string]")) {
                    element = element.substring("map[string]".length());
                }

                if (element.isEmpty() || isScalar(element) || element.contains(".")
                        || element.startsWith("interface{") || byName.containsKey(element)) {
                    continue;
                }

                property.dataType = element.equals(property.dataType)
                        ? undescribedType(property)
                        : "interface{}";
            }
        }

        for (CodegenModel model : byName.values()) {
            Set<String> branches = new LinkedHashSet<>();
            if (model.anyOf != null) {
                branches.addAll(model.anyOf);
            }
            if (model.oneOf != null) {
                branches.addAll(model.oneOf);
            }
            if (branches.isEmpty()) {
                continue;
            }

            for (CodegenProperty property : propertiesOf(model)) {
                Set<String> types = new LinkedHashSet<>();

                for (String branch : branches) {
                    CodegenModel source = byName.get(branch);
                    if (source == null) {
                        continue;
                    }
                    source.vars.stream()
                            .filter(candidate -> candidate.baseName.equals(property.baseName))
                            .forEach(candidate -> types.add(candidate.dataType));
                }

                if (types.size() > 1) {
                    property.dataType = "interface{}";
                }
            }
        }

        return processed;
    }

    /**
     * The Kind, from EVERY literal segment of the collection path, less the
     * {@code /api/v2} every path in this document begins with:
     *
     *   /api/v2/admin/educations                       -> AdminEducation
     *   /api/v2/pim/employees/{empNumber}/languages    -> PimEmployeeLanguage
     *
     * The leaf alone would do for a top-level path, and does not here:
     * languages, attachments, comments, likes and reports each hang off
     * several owners, and two Kinds named Language would be one package and
     * one CRD silently overwriting the other. The screen name the document
     * tags with cannot do it either -- {@code PIM/Employee Language} is prose.
     */
    @Override
    public String toApiName(String name) {
        String path = name;

        if (!pathPrefix.isEmpty() && path.startsWith(pathPrefix)) {
            path = path.substring(pathPrefix.length());
        }

        String[] segments = path.split("/");
        StringBuilder kind = new StringBuilder();

        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];

            if (segment.isEmpty() || segment.startsWith("{")) {
                continue;
            }

            // Singular where this segment names ONE of something -- the last
            // segment, which is the resource itself, and any segment followed
            // by its identifier. Plural otherwise, which is what keeps an
            // endpoint OF a collection apart from the same-named endpoint of
            // one member of it -- without that they are one Kind, and one CRD
            // silently overwrites the other.
            boolean one = i == segments.length - 1
                    || (i + 1 < segments.length && segments[i + 1].startsWith("{"));

            String word = segment.replace('-', '_');
            kind.append(camelize(one ? singular(word) : word));
        }

        return kind.length() == 0 ? "Resource" : kind.toString();
    }

    @Override
    public String toApiFilename(String name) {
        return underscore(toApiName(name));
    }

    /** A trailing {@code /{param}} is the member of a collection, not a collection. */
    private String collectionOf(String path) {
        // A document that spells a collection with a trailing slash in one
        // place and without in another means the same collection both times;
        // without this they are two groups writing one file.
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        int cut = path.lastIndexOf('/');

        if (cut > 0 && path.endsWith("}") && path.startsWith("{", cut + 1)) {
            return path.substring(0, cut);
        }
        return path;
    }

    private boolean isMember(String collection, String path) {
        return collectionOf(path).equals(collection) && !path.equals(collection);
    }

    private String lastSegment(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    /** A Go type that cannot be qualified with a package name. */
    private boolean isNotAStruct(String dataType) {
        return dataType.startsWith("[") || dataType.startsWith("map[")
                || dataType.startsWith("interface{");
    }

    // ponytail: four suffix rules rather than an inflector, checked against
    // every plural segment this document actually spells -- educations,
    // employment-statuses, job-categories, currencies, vacancies, licenses,
    // expenses, memberships, subunits, kpis. A document adding "people" or
    // "indices" wants a real one.
    /**
     * A collection segment as ONE of what it holds, which is what a Kind is
     * named after.
     *
     * The hard case is {@code -ses}, which is two rules wearing one spelling:
     * {@code statuses} drops {@code es} and {@code licenses} drops {@code s}.
     * The sibilant before it decides -- {@code uses}, {@code sses},
     * {@code shes}, {@code ches}, {@code xes}, {@code zes} take {@code es} --
     * and "status", "analysis" and "metadata" are not plurals at all.
     */
    private String singular(String name) {
        String lower = name.toLowerCase(Locale.ROOT);

        if (!lower.endsWith("s") || lower.endsWith("ss") || lower.endsWith("us")
                || lower.endsWith("sis") || lower.endsWith("data")) {
            return name;
        }
        if (lower.endsWith("ies")) {
            return name.substring(0, name.length() - 3) + "y";
        }
        if (lower.endsWith("uses") || lower.endsWith("sses") || lower.endsWith("shes")
                || lower.endsWith("ches") || lower.endsWith("xes") || lower.endsWith("zes")) {
            return name.substring(0, name.length() - 2);
        }
        return name.substring(0, name.length() - 1);
    }
}
