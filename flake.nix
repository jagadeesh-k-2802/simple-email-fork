{
  description = "Kotlin development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    android.url = "github:tadfisher/android-nixpkgs";
    android.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { self, nixpkgs, android }:
    let
      javaVersion = 11;

      overlays = [
        (final: prev: rec {
          jdk = prev."jdk${toString javaVersion}";
          gradle = prev.gradle.override { java = jdk; };
          kotlin = prev.kotlin.override { jre = jdk; };
        })
      ];
      supportedSystems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs supportedSystems (system: f system);
    in
      {
        devShells = forAllSystems
          (system:
            let
              pkgs = import nixpkgs {
                inherit system;
              };
              android-sdk = android.sdk.${system} (sdkPkgs: with sdkPkgs;
                [
                  build-tools-30-0-2
                  cmdline-tools-latest
                  platform-tools
                  platforms-android-31
                  platforms-android-30
                  ndk-23-1-7779620
                  patcher-v4
                ]);
            in
              {
                default = pkgs.mkShell {
                  packages = with pkgs; [
                    jdk11
                    gradle
                    kotlin
                    kotlin-language-server
                    gcc
                    gcc-unwrapped.lib
                    glibc
                    ncurses
                    patchelf
                    zlib
                  ];
                  shellHook = ''
                    export LD_LIBRARY_PATH=${pkgs.gcc-unwrapped.lib}/lib:$LD_LIBRARY_PATH
                    export ANDROID_SDK_ROOT=${android-sdk}/share/android-sdk
                  '';
                };
              });
      };
}
