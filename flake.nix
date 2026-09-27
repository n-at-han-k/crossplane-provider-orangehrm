{
  # The shell the generator and the provider share: openapi-generator writes
  # the Go, controller-gen and angryjet finish it, Go builds it.
  description = "OrangeHRM Crossplane provider, generated from the REST v2 OpenAPI description";
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    utils.url = "github:numtide/flake-utils";
  };
  outputs = { self, nixpkgs, utils }:
    (utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        # The one hook a template cannot reach: which operations are one
        # resource. javac against the CLI's own jar and an SPI entry -- no
        # Maven, no checkout of the generator.
        orangehrm-codegen = pkgs.stdenv.mkDerivation {
          name = "orangehrm-crossplane-codegen";
          src = ./generators/orangehrm;

          nativeBuildInputs = [ pkgs.jdk ];

          buildPhase = ''
            mkdir -p classes
            javac -nowarn -proc:none \
              -cp ${pkgs.openapi-generator-cli}/share/java/openapi-generator-cli.jar \
              -d classes $(find src -name '*.java')
            cp -r resources/. classes/
            jar cf orangehrm-codegen.jar -C classes .
          '';

          installPhase = ''
            install -Dm644 orangehrm-codegen.jar $out/share/java/orangehrm-codegen.jar
          '';
        };

        # The packaged CLI runs `java -jar`, which ignores -cp; a generator on
        # the classpath needs the main class named.
        openapi-generator-orangehrm = pkgs.writeShellApplication {
          name = "openapi-generator-orangehrm";
          runtimeInputs = [ pkgs.jre ];
          text = ''
            exec java -cp ${orangehrm-codegen}/share/java/orangehrm-codegen.jar:${pkgs.openapi-generator-cli}/share/java/openapi-generator-cli.jar \
              org.openapitools.codegen.OpenAPIGenerator "$@"
          '';
        };

      in
      {
        packages = { inherit orangehrm-codegen openapi-generator-orangehrm; };

        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            go
            gopls

            # bin/generate: the generated imports are whatever the document
            # made each resource need.
            gotools

            # The patched generator (`-g orangehrm-crossplane`), which groups
            # the document's operations into managed resources.
            openapi-generator-orangehrm

            # hack/check-coverage.py and hack/gen-spec.sh read and write the
            # API description, which is JSON -- no yaml needed.
            python3

            # `make generate` runs controller-gen and angryjet through `go
            # tool`, and `make build` needs docker for the image; the CLI is
            # what turns the result into an xpkg.
            gnumake
            crossplane-cli
            kubectl
          ];

          # A provider binary is pure Go, and cgo only costs a C compiler.
          shellHook = ''
            export CGO_ENABLED=0
          '';
        };
      }));
}
