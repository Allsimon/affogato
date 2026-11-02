package org.affogato.lint;

import java.util.Set;

/**
 * What the packaging rules know of nixpkgs' builders, by the name they are called with: mkDerivation stands for
 * {@code stdenv.mkDerivation} and its variants. The test data builders.txt holds the same values, which a check of the
 * flake compares with nixpkgs.
 */
public final class Builders {
    private Builders() {}

    /** The builders other than {@code stdenv.mkDerivation} that nixpkgs calls with {@code finalAttrs:}. */
    public static final Set<String> FINAL_ATTRS = Set.of(
            "buildDotnetModule",
            "buildDunePackage",
            "buildGoModule",
            "buildHomeAssistantComponent",
            "buildMavenPackage",
            "buildNpmPackage",
            "buildPythonApplication",
            "buildPythonPackage",
            "buildRustPackage");

    /** The builders that set {@code strictDeps} to true when a derivation does not set it. */
    public static final Set<String> STRICT_DEPS = Set.of(
            "buildDunePackage",
            "buildGoModule",
            "buildHomeAssistantComponent",
            "buildNpmPackage",
            "buildPythonApplication",
            "buildPythonPackage",
            "buildRustPackage");

    /** The builders that set {@code __structuredAttrs} to true when a derivation does not set it. */
    public static final Set<String> STRUCTURED_ATTRS =
            Set.of("buildHomeAssistantComponent", "buildPythonApplication", "buildPythonPackage");

    /** The fetchers that take {@code hash}, as nixpkgs calls them; others, such as fetchHex, take only sha256. */
    public static final Set<String> HASH_FETCHERS = Set.of(
            "fetchbzr",
            "fetchCargoVendor",
            "fetchCrate",
            "fetchDebianPatch",
            "fetchdarcs",
            "fetchfossil",
            "fetchFromBitbucket",
            "fetchFromCodeberg",
            "fetchFromGitea",
            "fetchFromGitHub",
            "fetchFromGitiles",
            "fetchFromGitLab",
            "fetchFromRepoOrCz",
            "fetchFromSourcehut",
            "fetchgit",
            "fetchhg",
            "fetchMavenArtifact",
            "fetchNpmDeps",
            "fetchNuGet",
            "fetchpatch",
            "fetchpatch2",
            "fetchPypi",
            "fetchRepoProject",
            "fetchsvn",
            "fetchurl",
            "fetchYarnDeps",
            "fetchzip");
}
