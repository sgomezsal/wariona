{
  inputs = {
    nixpkgs.url     = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachSystem [ "x86_64-linux" "aarch64-linux" ] (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree                = true;
            android_sdk.accept_license = true;
          };
        };

        androidSdk = pkgs.androidenv.composeAndroidPackages {
          platformVersions    = [ "34" "35" ];
          buildToolsVersions  = [ "34.0.0" ];
          includeEmulator     = false;
          includeSystemImages = false;
        };

        sdkPath = "${androidSdk.androidsdk}/share/android-sdk";
      in
      {
        devShell = pkgs.mkShell {
          buildInputs = with pkgs; [
            androidSdk.androidsdk
            openjdk17
            android-tools
            glibc
            coreutils-full
          ];

          ANDROID_HOME     = sdkPath;
          ANDROID_SDK_ROOT = sdkPath;

          shellHook = ''
            set -e
            SDK_LOCAL="$PWD/android-sdk"

            if [ ! -d "$SDK_LOCAL" ]; then
              mkdir -p "$SDK_LOCAL"
              cp -al "$ANDROID_HOME"/. "$SDK_LOCAL"/
            fi

            rm -rf "$SDK_LOCAL/licenses"
            mkdir -p "$SDK_LOCAL/licenses"

            cat > "$SDK_LOCAL/licenses/android-sdk-license" <<'EOF'
8933bad161af4178b1185d1a37fbf41ea5269c55
d56f2eacc04d77a1a6767aaabaf54f32a648bfa
24333f8a63b6825ea9c5514f83c2829b004d1fee
EOF

            cat > "$SDK_LOCAL/licenses/android-sdk-preview-license" <<'EOF'
504667f4c0de7af1a06e9d0e8158a6ecb7041cdc
84831b9409646a918e30573bab4c9c91346d8abd
EOF

            for mgr in \
              "$SDK_LOCAL/cmdline-tools/latest/bin/sdkmanager" \
              "$SDK_LOCAL/cmdline-tools/bin/sdkmanager" \
              "$SDK_LOCAL/tools/bin/sdkmanager"
            do
              if [ -x "$mgr" ]; then
                yes | "$mgr" "build-tools;34.0.0" "platforms;android-35" >/dev/null 2>&1 || true
                break
              fi
            done

            echo "sdk.dir=$SDK_LOCAL" > local.properties

            cat > gradle.properties <<'EOF'
moko.resources.disableStaticFrameworkWarning=true
kotlin.native.ignoreDisabledTargets=true
android.useAndroidX=true
android.enableJetifier=true
org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8
EOF
            exec fish
          '';
        };
      });
}

