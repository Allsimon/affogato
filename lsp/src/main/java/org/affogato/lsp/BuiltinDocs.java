package org.affogato.lsp;

/** Nix's documentation of its builtins, from https://nix.dev/manual/nix/2.35/, written by tools/ (builtin-docs). */
final class BuiltinDocs {
    private BuiltinDocs() {}

    /** The documentation of {@code builtins.name} in Markdown, or null. */
    static String markdown(String name) {
        return switch (name) {
            case "abort" -> """
                ```nix
                builtins.abort s
                ```

                Abort Nix expression evaluation and print the error message *s*.\
                """;
            case "add" -> """
                ```nix
                builtins.add e1 e2
                ```

                Return the sum of the numbers *e1* and *e2*.\
                """;
            case "addDrvOutputDependencies" -> """
                ```nix
                builtins.addDrvOutputDependencies s
                ```

                Create a copy of the given string where a single
                [constant](https://nix.dev/manual/nix/2.35/language/string-context.html#string-context-constant)
                string context element is turned into a
                [derivation deep](https://nix.dev/manual/nix/2.35/language/string-context.html#string-context-element-derivation-deep)
                string context element.

                The store path that is the constant string context element should point to a valid derivation, and end in `.drv`.

                The original string context element must not be empty or have multiple elements, and it must not have any other type of element other than a constant or derivation deep element.
                The latter is supported so this function is idempotent.

                This is the opposite of [`builtins.unsafeDiscardOutputDependency`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-unsafeDiscardOutputDependency).\
                """;
            case "addErrorContext" -> """
                ```nix
                builtins.addErrorContext context value
                ```

                Evaluate *context*, which can be coerced to a string,
                and append it to any error or stack traces displayed while evaluating *value*.
                Then return *value*.

                This function is useful for providing helpful context in complex Nix expressions
                when the evaluation of *value* fails.
                The additional context is applied when evaluating *value* itself fails,
                not when attributes or elements of *value* are evaluated.

                For example, the module system from nixpkgs uses this to show
                the relevant information about the options that were evaluating
                when an error occurs.

                ```nix-repl
                nix-repl> addErrorContext "while evaluating foo" (throw "bar")
                error:
                       … while evaluating foo

                       … while calling the 'throw' builtin
                         at «string»:1:56:
                            1| with builtins; addErrorContext "while evaluating foo" (throw "bar")
                             |                                                        ^

                       error: bar
                ```\
                """;
            case "all" -> """
                ```nix
                builtins.all pred list
                ```

                Return `true` if the function *pred* returns `true` for all elements
                of *list*, and `false` otherwise.
                Short-circuits and does not evaluate elements that appear later in the list if `pred` evaluates to `false`.\
                """;
            case "any" -> """
                ```nix
                builtins.any pred list
                ```

                Return `true` if the function *pred* returns `true` for at least one
                element of *list*, and `false` otherwise.
                Short-circuits and does not evaluate elements that appear later in the list if `pred` evaluates to `true`.\
                """;
            case "attrNames" -> """
                ```nix
                builtins.attrNames set
                ```

                Return the names of the attributes in the set *set* in an
                alphabetically sorted list. For instance, `builtins.attrNames { y
                = 1; x = "foo"; }` evaluates to `[ "x" "y" ]`.

                Has `O(n log n)` time complexity, where `n` is number of attributes in the *set*.\
                """;
            case "attrValues" -> """
                ```nix
                builtins.attrValues set
                ```

                Return the values of the attributes in the set *set* in the order
                corresponding to the sorted attribute names.

                Has `O(n log n)` time complexity, where `n` is number of attributes in the *set*.\
                """;
            case "baseNameOf" -> """
                ```nix
                builtins.baseNameOf x
                ```

                Return the *base name* of either a [path value](https://nix.dev/manual/nix/2.35/language/types.html#type-path) *x* or a string *x*, depending on which type is passed, and according to the following rules.

                For a path value, the *base name* is considered to be the part of the path after the last directory separator, including any file extensions.
                This is the simple case, as path values don't have trailing slashes.

                When the argument is a string, a more involved logic applies. If the string ends with a `/`, only this one final slash is removed.

                After this, the *base name* is returned as previously described, assuming `/` as the directory separator. (Note that evaluation must be platform independent.)

                This is somewhat similar to the [GNU `basename`](https://www.gnu.org/software/coreutils/manual/html_node/basename-invocation.html) command, but GNU `basename` strips any number of trailing slashes.\
                """;
            case "bitAnd" -> """
                ```nix
                builtins.bitAnd e1 e2
                ```

                Return the bitwise AND of the integers *e1* and *e2*.\
                """;
            case "bitOr" -> """
                ```nix
                builtins.bitOr e1 e2
                ```

                Return the bitwise OR of the integers *e1* and *e2*.\
                """;
            case "bitXor" -> """
                ```nix
                builtins.bitXor e1 e2
                ```

                Return the bitwise XOR of the integers *e1* and *e2*.\
                """;
            case "break" -> """
                ```nix
                builtins.break v
                ```

                In debug mode (enabled using `--debugger`), pause Nix expression evaluation and enter the REPL.
                Otherwise, return the argument `v`.\
                """;
            case "builtins" -> """
                ```nix
                builtins.builtins
                ```

                *Type:* set

                Contains all the built-in functions and values.

                Since built-in functions were added over time, [testing for attributes](https://nix.dev/manual/nix/2.35/language/operators.html#has-attribute) in `builtins` can be used for graceful fallback on older Nix installations:

                ```nix
                # if hasContext is not available, we assume `s` has a context
                if builtins ? hasContext then builtins.hasContext s else true
                ```\
                """;
            case "catAttrs" -> """
                ```nix
                builtins.catAttrs attr list
                ```

                Collect each attribute named *attr* from a list of attribute
                sets.  Attrsets that don't contain the named attribute are
                ignored. For example,

                ```nix
                builtins.catAttrs "a" [{a = 1;} {b = 0;} {a = 2;}]
                ```

                evaluates to `[1 2]`.

                Has `O(n)` time complexity, where `n` is the size of the *list*.\
                """;
            case "ceil" -> """
                ```nix
                builtins.ceil number
                ```

                Rounds and converts *number* to the next higher NixInt value if possible, i.e. `ceil *number* >= *number*` and
                `ceil *number* - *number* < 1`.

                An evaluation error is thrown, if there exists no such NixInt value `ceil *number*`.
                Due to bugs in previous Nix versions an evaluation error might be thrown, if the datatype of *number* is
                a NixInt and if `*number* < -9007199254740992` or `*number* > 9007199254740992`.

                If the datatype of *number* is neither a NixInt (signed 64-bit integer) nor a NixFloat
                (IEEE-754 double-precision floating-point number), an evaluation error is thrown.\
                """;
            case "compareVersions" -> """
                ```nix
                builtins.compareVersions s1 s2
                ```

                Compare two strings representing versions and return `-1` if
                version *s1* is older than version *s2*, `0` if they are the same,
                and `1` if *s1* is newer than *s2*. The version comparison
                algorithm is the same as the one used by [`nix-env
                -u`](https://nix.dev/manual/nix/2.35/command-ref/nix-env/upgrade.html).\
                """;
            case "concatLists" -> """
                ```nix
                builtins.concatLists lists
                ```

                Concatenate a list of lists into a single list.\
                """;
            case "concatMap" -> """
                ```nix
                builtins.concatMap f list
                ```

                This function is equivalent to `builtins.concatLists (map f list)`
                but is more efficient.\
                """;
            case "concatStringsSep" -> """
                ```nix
                builtins.concatStringsSep separator list
                ```

                Concatenate a list of strings with a separator between each
                element, e.g. `concatStringsSep "/" ["usr" "local" "bin"] ==
                "usr/local/bin"`.\
                """;
            case "convertHash" -> """
                ```nix
                builtins.convertHash args
                ```

                Return the specified representation of a hash string, based on the attributes presented in *args*:

                - `hash`

                  The hash to be converted.
                  The hash format is detected automatically.

                - `hashAlgo`

                  The algorithm used to create the hash. Must be one of
                  - `"md5"`
                  - `"sha1"`
                  - `"sha256"`
                  - `"sha512"`

                  The attribute may be omitted when `hash` is an [SRI hash](https://www.w3.org/TR/SRI/#the-integrity-attribute) or when the hash is prefixed with the hash algorithm name followed by a colon.
                  That `<hashAlgo>:<hashBody>` syntax is supported for backwards compatibility with existing tooling.

                - `toHashFormat`

                  The format of the resulting hash. Must be one of
                  - `"base16"`
                  - `"nix32"`
                  - `"base32"` (deprecated alias for `"nix32"`)
                  - `"base64"`
                  - `"sri"`

                The result hash is the *toHashFormat* representation of the hash *hash*.

                > **Example**
                >
                >   Convert a SHA256 hash in Base16 to SRI:
                >
                > ```nix
                > builtins.convertHash {
                >   hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
                >   toHashFormat = "sri";
                >   hashAlgo = "sha256";
                > }
                > ```
                >
                >     "sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="

                > **Example**
                >
                >   Convert a SHA256 hash in SRI to Base16:
                >
                > ```nix
                > builtins.convertHash {
                >   hash = "sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";
                >   toHashFormat = "base16";
                > }
                > ```
                >
                >     "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

                > **Example**
                >
                >   Convert a hash in the form `<hashAlgo>:<hashBody>` in Base16 to SRI:
                >
                > ```nix
                > builtins.convertHash {
                >   hash = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
                >   toHashFormat = "sri";
                > }
                > ```
                >
                >     "sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="\
                """;
            case "currentSystem" -> """
                ```nix
                builtins.currentSystem
                ```

                *Type:* string

                *Impure only*

                The value of the
                [`eval-system`](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-eval-system)
                or else
                [`system`](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-system)
                configuration option.

                It can be used to set the `system` attribute for [`builtins.derivation`](https://nix.dev/manual/nix/2.35/language/derivations.html) such that the resulting derivation can be built on the same system that evaluates the Nix expression:

                ```nix
                 builtins.derivation {
                   # ...
                   system = builtins.currentSystem;
                }
                ```

                It can be overridden in order to create derivations for different system than the current one:

                ```console
                $ nix-instantiate --system "mips64-linux" --eval --expr 'builtins.currentSystem'
                "mips64-linux"
                ```\
                """;
            case "currentTime" -> """
                ```nix
                builtins.currentTime
                ```

                *Type:* integer

                *Impure only*

                Return the [Unix time](https://en.wikipedia.org/wiki/Unix_time) at first evaluation.
                Repeated references to that name re-use the initially obtained value.

                Example:

                ```console
                $ nix repl
                Welcome to Nix 2.15.1 Type :? for help.

                nix-repl> builtins.currentTime
                1683705525

                nix-repl> builtins.currentTime
                1683705525
                ```

                The [store path](https://nix.dev/manual/nix/2.35/store/store-path.html) of a derivation depending on `currentTime` differs for each evaluation, unless both evaluate `builtins.currentTime` in the same second.\
                """;
            case "deepSeq" -> """
                ```nix
                builtins.deepSeq e1 e2
                ```

                This is like `seq e1 e2`, except that *e1* is evaluated *deeply*:
                if it’s a list or set, its elements or attributes are also
                evaluated recursively.\
                """;
            case "dirOf" -> """
                ```nix
                builtins.dirOf s
                ```

                Return the directory part of the string *s*, that is, everything
                before the final slash in the string. This is similar to the GNU
                `dirname` command.\
                """;
            case "div" -> """
                ```nix
                builtins.div e1 e2
                ```

                Return the quotient of the numbers *e1* and *e2*.\
                """;
            case "elem" -> """
                ```nix
                builtins.elem x xs
                ```

                Return `true` if a value equal to *x* occurs in the list *xs*, and
                `false` otherwise.
                Short-circuits and does not evaluate elements that occur in the list after the first match.\
                """;
            case "elemAt" -> """
                ```nix
                builtins.elemAt xs n
                ```

                Return element *n* from the list *xs*. Elements are counted starting
                from 0. A fatal error occurs if the index is out of bounds.\
                """;
            case "false" -> """
                ```nix
                builtins.false
                ```

                *Type:* Boolean

                Primitive value.

                It can be returned by
                [comparison operators](https://nix.dev/manual/nix/2.35/language/operators.html#comparison)
                and used in
                [conditional expressions](https://nix.dev/manual/nix/2.35/language/syntax.html#conditionals).

                The name `false` is not special, and can be shadowed:

                ```nix-repl
                nix-repl> let false = 1; in false
                1
                ```\
                """;
            case "fetchClosure" -> """
                ```nix
                builtins.fetchClosure args
                ```

                *Experimental feature* [`fetch-closure`](https://nix.dev/manual/nix/2.35/development/experimental-features.html#xp-feature-fetch-closure)

                Fetch a store path [closure](https://nix.dev/manual/nix/2.35/glossary.html#gloss-closure) from a binary cache, and return the store path as a string with context.

                This function can be invoked in three ways that we will discuss in order of preference.

                **Fetch a content-addressed store path**

                Example:

                ```nix
                builtins.fetchClosure {
                  fromStore = "https://cache.nixos.org";
                  fromPath = /nix/store/ldbhlwhh39wha58rm61bkiiwm6j7211j-git-2.33.1;
                }
                ```

                This is the simplest invocation, and it does not require the user of the expression to configure [`trusted-public-keys`](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-trusted-public-keys) to ensure their authenticity.

                If your store path is [input addressed](https://nix.dev/manual/nix/2.35/glossary.html#gloss-input-addressed-store-object) instead of content addressed, consider the other two invocations.

                **Fetch any store path and rewrite it to a fully content-addressed store path**

                Example:

                ```nix
                builtins.fetchClosure {
                  fromStore = "https://cache.nixos.org";
                  fromPath = /nix/store/nph9br6y2dmciy6q3dj3fwk2brdlr4gh-git-2.33.1;
                  toPath = /nix/store/ldbhlwhh39wha58rm61bkiiwm6j7211j-git-2.33.1;
                }
                ```

                This example fetches `/nix/store/r2jd...` from the specified binary cache,
                and rewrites it into the content-addressed store path
                `/nix/store/ldbh...`.

                Like the previous example, no extra configuration or privileges are required.

                To find out the correct value for `toPath` given a `fromPath`,
                use [`nix store make-content-addressed`](https://nix.dev/manual/nix/2.35/command-ref/new-cli/nix3-store-make-content-addressed.html):

                ```console
                # nix store make-content-addressed --from https://cache.nixos.org /nix/store/nph9br6y2dmciy6q3dj3fwk2brdlr4gh-git-2.33.1
                rewrote '/nix/store/nph9br6y2dmciy6q3dj3fwk2brdlr4gh-git-2.33.1' to '/nix/store/ldbhlwhh39wha58rm61bkiiwm6j7211j-git-2.33.1'
                ```

                Alternatively, set `toPath = ""` and find the correct `toPath` in the error message.

                **Fetch an input-addressed store path as is**

                Example:

                ```nix
                builtins.fetchClosure {
                  fromStore = "https://cache.nixos.org";
                  fromPath = /nix/store/nph9br6y2dmciy6q3dj3fwk2brdlr4gh-git-2.33.1;
                  inputAddressed = true;
                }
                ```

                It is possible to fetch an [input-addressed store path](https://nix.dev/manual/nix/2.35/glossary.html#gloss-input-addressed-store-object) and return it as is.
                However, this is the least preferred way of invoking `fetchClosure`, because it requires that the input-addressed paths are trusted by the Nix configuration.

                **`builtins.storePath`**

                `fetchClosure` is similar to [`builtins.storePath`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-storePath) in that it allows you to use a previously built store path in a Nix expression.
                However, `fetchClosure` is more reproducible because it specifies a binary cache from which the path can be fetched.
                Also, using content-addressed store paths does not require users to configure [`trusted-public-keys`](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-trusted-public-keys) to ensure their authenticity.\
                """;
            case "fetchGit" -> """
                ```nix
                builtins.fetchGit args
                ```

                Fetch a path from git. *args* can be a URL, in which case the HEAD
                of the repo at that URL is fetched. Otherwise, it can be an
                attribute with the following attributes (all except `url` optional):

                - `url`

                  The [Git URL] of the repo. SCP-like syntax is supported, but relative
                  paths are rewritten to absolute ones. For example:

                  `git@github.com:repo/path` becomes `ssh://git@github.com/repo/path`

                  [Git URL]: https://git-scm.com/docs/git-clone#_git_urls

                - `name` (default: `source`)

                  The name of the directory the repo should be exported to in the store.

                - `rev` (default: *the tip of `ref`*)

                  The [Git revision] to fetch.
                  This is typically a commit hash.

                  [Git revision]: https://git-scm.com/docs/git-rev-parse#_specifying_revisions

                - `ref` (default: `HEAD`)

                  The [Git reference] under which to look for the requested revision.
                  This is often a branch or tag name.

                  [Git reference]: https://git-scm.com/book/en/v2/Git-Internals-Git-References

                  This option has no effect once `shallow` cloning is enabled.

                  By default, the `ref` value is prefixed with `refs/heads/`.
                  As of 2.3.0, Nix doesn't prefix `refs/heads/` if `ref` starts with `refs/`.

                - `submodules` (default: `false`)

                  A Boolean parameter that specifies whether submodules should be checked out.

                - `exportIgnore` (default: `true`)

                  A Boolean parameter that specifies whether `export-ignore` from `.gitattributes` should be applied.
                  This approximates part of the `git archive` behavior.

                  Enabling this option is not recommended because it is unknown whether the Git developers commit to the reproducibility of `export-ignore` in newer Git versions.

                - `shallow` (default: `false`)

                  Make a shallow clone when fetching the Git tree.
                  When this is enabled, the options `ref` and `allRefs` have no effect anymore.

                - `lfs` (default: `false`)

                  A boolean that when `true` specifies that [Git LFS] files should be fetched.

                  [Git LFS]: https://git-lfs.com/

                - `allRefs`

                  Whether to fetch all references (eg. branches and tags) of the repository.
                  With this argument being true, it's possible to load a `rev` from *any* `ref`.
                  (by default only `rev`s from the specified `ref` are supported).

                  This option has no effect once `shallow` cloning is enabled.

                - `verifyCommit` (default: `true` if `publicKey` or `publicKeys` are provided, otherwise `false`)

                  Whether to check `rev` for a signature matching `publicKey` or `publicKeys`.
                  If `verifyCommit` is enabled, then `fetchGit` cannot use a local repository with uncommitted changes.
                  Requires the [`verified-fetches` experimental feature](https://nix.dev/manual/nix/2.35/development/experimental-features.html#xp-feature-verified-fetches).

                - `publicKey`

                  The public key against which `rev` is verified if `verifyCommit` is enabled.
                  Requires the [`verified-fetches` experimental feature](https://nix.dev/manual/nix/2.35/development/experimental-features.html#xp-feature-verified-fetches).

                - `keytype` (default: `"ssh-ed25519"`)

                  The key type of `publicKey`.
                  Possible values:
                  - `"ssh-dsa"`
                  - `"ssh-ecdsa"`
                  - `"ssh-ecdsa-sk"`
                  - `"ssh-ed25519"`
                  - `"ssh-ed25519-sk"`
                  - `"ssh-rsa"`
                  Requires the [`verified-fetches` experimental feature](https://nix.dev/manual/nix/2.35/development/experimental-features.html#xp-feature-verified-fetches).

                - `publicKeys`

                  The public keys against which `rev` is verified if `verifyCommit` is enabled.
                  Must be given as a list of attribute sets with the following form:

                  ```nix
                  {
                    key = "<public key>";
                    type = "<key type>"; # optional, default: "ssh-ed25519"
                  }
                  ```

                  Requires the [`verified-fetches` experimental feature](https://nix.dev/manual/nix/2.35/development/experimental-features.html#xp-feature-verified-fetches).


                Here are some examples of how to use `fetchGit`.

                  - To fetch a private repository over SSH:

                    ```nix
                    builtins.fetchGit {
                      url = "git@github.com:my-secret/repository.git";
                      ref = "master";
                      rev = "adab8b916a45068c044658c4158d81878f9ed1c3";
                    }
                    ```

                  - To fetch an arbitrary reference:

                    ```nix
                    builtins.fetchGit {
                      url = "https://github.com/NixOS/nix.git";
                      ref = "refs/heads/0.5-release";
                    }
                    ```

                  - If the revision you're looking for is in the default branch of
                    the git repository you don't strictly need to specify the branch
                    name in the `ref` attribute.

                    However, if the revision you're looking for is in a future
                    branch for the non-default branch you will need to specify the
                    the `ref` attribute as well.

                    ```nix
                    builtins.fetchGit {
                      url = "https://github.com/nixos/nix.git";
                      rev = "841fcbd04755c7a2865c51c1e2d3b045976b7452";
                      ref = "1.11-maintenance";
                    }
                    ```

                    > **Note**
                    >
                    > It is nice to always specify the branch which a revision
                    > belongs to. Without the branch being specified, the fetcher
                    > might fail if the default branch changes. Additionally, it can
                    > be confusing to try a commit from a non-default branch and see
                    > the fetch fail. If the branch is specified the fault is much
                    > more obvious.

                  - If the revision you're looking for is in the default branch of
                    the git repository you may omit the `ref` attribute.

                    ```nix
                    builtins.fetchGit {
                      url = "https://github.com/nixos/nix.git";
                      rev = "841fcbd04755c7a2865c51c1e2d3b045976b7452";
                    }
                    ```

                  - To fetch a specific tag:

                    ```nix
                    builtins.fetchGit {
                      url = "https://github.com/nixos/nix.git";
                      ref = "refs/tags/1.9";
                    }
                    ```

                  - To fetch the latest version of a remote branch:

                    ```nix
                    builtins.fetchGit {
                      url = "ssh://git@github.com/nixos/nix.git";
                      ref = "master";
                    }
                    ```

                  - To verify the commit signature:

                    ```nix
                    builtins.fetchGit {
                      url = "ssh://git@github.com/nixos/nix.git";
                      verifyCommit = true;
                      publicKeys = [
                          {
                            type = "ssh-ed25519";
                            key = "AAAAC3NzaC1lZDI1NTE5AAAAIArPKULJOid8eS6XETwUjO48/HKBWl7FTCK0Z//fplDi";
                          }
                      ];
                    }
                    ```

                    Nix refetches the branch according to the [`tarball-ttl`](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-tarball-ttl) setting.

                    This behavior is disabled in [pure evaluation mode](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-pure-eval).

                  - To fetch the content of a checked-out work directory:

                    ```nix
                    builtins.fetchGit ./work-dir
                    ```

                If the URL points to a local directory, and no `ref` or `rev` is
                given, `fetchGit` uses the current content of the checked-out
                files, even if they are not committed or added to Git's index. It
                only considers files added to the Git repository, as listed by `git ls-files`.\
                """;
            case "fetchTarball" -> """
                ```nix
                builtins.fetchTarball args
                ```

                Download the specified URL, unpack it and return the path of the
                unpacked tree. The file must be a tape archive (`.tar`) compressed
                with `gzip`, `bzip2` or `xz`. If the tarball consists of a
                single directory, then the top-level path component of the files
                in the tarball is removed. The typical use of the function is to
                obtain external Nix expression dependencies, such as a
                particular version of Nixpkgs, e.g.

                ```nix
                with import (fetchTarball https://github.com/NixOS/nixpkgs/archive/nixos-14.12.tar.gz) {};

                stdenv.mkDerivation { … }
                ```

                The fetched tarball is cached for a certain amount of time (1
                hour by default) in `~/.cache/nix/tarballs/`. You can change the
                cache timeout either on the command line with `--tarball-ttl`
                *number-of-seconds* or in the Nix configuration file by adding
                the line `tarball-ttl = ` *number-of-seconds*.

                Note that when obtaining the hash with `nix-prefetch-url` the
                option `--unpack` is required.

                This function can also verify the contents against a hash. In that
                case, the function takes a set instead of a URL. The set requires
                the attribute `url` and the attribute `sha256`, e.g.

                ```nix
                with import (fetchTarball {
                  url = "https://github.com/NixOS/nixpkgs/archive/nixos-14.12.tar.gz";
                  sha256 = "1jppksrfvbk5ypiqdz4cddxdl8z6zyzdb2srq8fcffr327ld5jj2";
                }) {};

                stdenv.mkDerivation { … }
                ```

                Not available in [restricted evaluation mode](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-restrict-eval).\
                """;
            case "fetchTree" -> """
                ```nix
                builtins.fetchTree input
                ```

                *Experimental feature* [`fetch-tree`](https://nix.dev/manual/nix/2.35/development/experimental-features.html#xp-feature-fetch-tree)

                Fetch a file system tree or a plain file using one of the supported backends and return an attribute set with:

                - the resulting fixed-output [store path](https://nix.dev/manual/nix/2.35/store/store-path.html)
                - the corresponding [NAR](https://nix.dev/manual/nix/2.35/store/file-system-object/content-address.html#serial-nix-archive) hash
                - backend-specific metadata (currently not documented). <!-- TODO: document output attributes -->

                *input* must be an attribute set with the following attributes:

                - `type` (String, required)

                  One of the [supported source types](https://nix.dev/manual/nix/2.35/language/builtins.html#source-types).
                  This determines other required and allowed input attributes.

                - `narHash` (String, optional)

                  The `narHash` parameter can be used to substitute the source of the tree.
                  It also allows for verification of tree contents that may not be provided by the underlying transfer mechanism.
                  If `narHash` is set, the source is first looked up is the Nix store and [substituters](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-substituters), and only fetched if not available.

                A subset of the output attributes of `fetchTree` can be re-used for subsequent calls to `fetchTree` to produce the same result again.
                That is, `fetchTree` is idempotent.

                Downloads are cached in `$XDG_CACHE_HOME/nix`.
                The remote source is fetched from the network if both are true:
                - A NAR hash is supplied and the corresponding store path is not [valid](https://nix.dev/manual/nix/2.35/glossary.html#gloss-validity), that is, not available in the store

                  > **Note**
                  >
                  > [Substituters](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-substituters) are not used in fetching.

                - There is no cache entry or the cache entry is older than [`tarball-ttl`](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-tarball-ttl)

                ## Source types

                The following source types and associated input attributes are supported.

                <!-- TODO: It would be soooo much more predictable to work with (and
                document) if `fetchTree` was a curried call with the first parameter for
                `type` or an attribute like `builtins.fetchTree.git`! -->


                - `"file"`


                  Place a plain file into the Nix store.
                  This is similar to [`builtins.fetchurl`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-fetchurl)


                  - `lastModified` (String, required)


                  - `name` (String, required)


                  - `narHash` (String, required)


                  - `rev` (String, required)


                  - `revCount` (String, required)


                  - `unpack` (String, required)


                  - `url` (String, required)


                    Supported protocols:

                    - `https`

                      > **Example**
                      >
                      > ```nix
                      > fetchTree {
                      >   type = "file";
                      >   url = "https://example.com/index.html";
                      > }
                      > ```

                    - `http`

                      Insecure HTTP transfer for legacy sources.

                      > **Warning**
                      >
                      > HTTP performs no encryption or authentication.
                      > Use a `narHash` known in advance to ensure the output has expected contents.

                    - `file`

                      A file on the local file system.

                      > **Example**
                      >
                      > ```nix
                      > fetchTree {
                      >   type = "file";
                      >   url = "file:///home/eelco/nix/README.md";
                      > }
                      > ```


                - `"git"`


                  Fetch a Git tree and copy it to the Nix store.
                  This is similar to [`builtins.fetchGit`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-fetchGit).


                  - `allRefs` (Bool, optional)


                    By default, this has no effect. This becomes relevant only once `shallow` cloning is disabled.

                    Whether to fetch all references (eg. branches and tags) of the repository.
                    With this argument being true, it's possible to load a `rev` from *any* `ref`.
                    (Without setting this option, only `rev`s from the specified `ref` are supported).

                    Default: `false`


                  - `dirtyRev` (String, required)


                  - `dirtyShortRev` (String, required)


                  - `exportIgnore` (String, required)


                  - `keytype` (String, required)


                  - `lastModified` (Integer, optional)


                    Unix timestamp of the fetched commit.

                    If set, pass through the value to the output attribute set.
                    Otherwise, generated from the fetched Git tree.


                  - `lfs` (Bool, optional)


                    Fetch any [Git LFS](https://git-lfs.com/) files.

                    Default: `false`


                  - `name` (String, required)


                  - `narHash` (String, required)


                  - `publicKey` (String, required)


                  - `publicKeys` (String, required)


                  - `ref` (String, optional)


                    By default, this has no effect. This becomes relevant only once `shallow` cloning is disabled.

                    A [Git reference](https://git-scm.com/book/en/v2/Git-Internals-Git-References), such as a branch or tag name.

                    Default: `"HEAD"`


                  - `rev` (String, optional)


                    A Git revision; a commit hash.

                    Default: the tip of `ref`


                  - `revCount` (Integer, optional)


                    Number of revisions in the history of the Git repository before the fetched commit.

                    If set, pass through the value to the output attribute set.
                    Otherwise, generated from the fetched Git tree.


                  - `shallow` (Bool, optional)


                    Make a shallow clone when fetching the Git tree.
                    When this is enabled, the options `ref` and `allRefs` have no effect anymore.

                    Default: `true`


                  - `submodules` (Bool, optional)


                    Also fetch submodules if available.

                    Default: `false`


                  - `url` (String, required)


                    The URL formats supported are the same as for Git itself.

                    > **Example**
                    >
                    > ```nix
                    > fetchTree {
                    >   type = "git";
                    >   url = "git@github.com:NixOS/nixpkgs.git";
                    > }
                    > ```

                    > **Note**
                    >
                    > If the URL points to a local directory, and no `ref` or `rev` is given, Nix only considers files added to the Git index, as listed by `git ls-files` but uses the *current file contents* of the Git working directory.


                  - `verifyCommit` (String, required)


                - `"github"`


                  - `host` (String, required)


                  - `lastModified` (String, required)


                  - `narHash` (String, required)


                  - `owner` (String, required)


                  - `ref` (String, required)


                  - `repo` (String, required)


                  - `rev` (String, required)


                  - `treeHash` (String, required)


                - `"gitlab"`


                  - `host` (String, required)


                  - `lastModified` (String, required)


                  - `narHash` (String, required)


                  - `owner` (String, required)


                  - `ref` (String, required)


                  - `repo` (String, required)


                  - `rev` (String, required)


                  - `treeHash` (String, required)


                - `"hg"`


                  - `name` (String, required)


                  - `narHash` (String, required)


                  - `ref` (String, required)


                  - `rev` (String, required)


                  - `revCount` (String, required)


                  - `url` (String, required)


                - `"indirect"`


                  - `id` (String, required)


                  - `narHash` (String, required)


                  - `ref` (String, required)


                  - `rev` (String, required)


                - `"path"`


                  - `lastModified` (String, required)


                  - `narHash` (String, required)


                  - `path` (String, required)


                  - `rev` (String, required)


                  - `revCount` (String, required)


                - `"sourcehut"`


                  - `host` (String, required)


                  - `lastModified` (String, required)


                  - `narHash` (String, required)


                  - `owner` (String, required)


                  - `ref` (String, required)


                  - `repo` (String, required)


                  - `rev` (String, required)


                  - `treeHash` (String, required)


                - `"tarball"`


                  Download a tar archive and extract it into the Nix store.
                  This has the same underlying implementation as [`builtins.fetchTarball`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-fetchTarball)


                  - `lastModified` (String, required)


                  - `name` (String, required)


                  - `narHash` (String, required)


                  - `rev` (String, required)


                  - `revCount` (String, required)


                  - `unpack` (String, required)


                  - `url` (String, required)


                    > **Example**
                    >
                    > ```nix
                    > fetchTree {
                    >   type = "tarball";
                    >   url = "https://github.com/NixOS/nixpkgs/tarball/nixpkgs-23.11";
                    > }
                    > ```



                 The following input types are still subject to change:

                 - `"path"`
                 - `"github"`
                 - `"gitlab"`
                 - `"sourcehut"`
                 - `"mercurial"`

                *input* can also be a [URL-like reference](https://nix.dev/manual/nix/2.35/command-ref/new-cli/nix3-flake.html#flake-references).
                The additional input types and the URL-like syntax requires the [`flakes` experimental feature](https://nix.dev/manual/nix/2.35/development/experimental-features.html#xp-feature-flakes) to be enabled.

                 > **Example**
                 >
                 > Fetch a GitHub repository using the attribute set representation:
                 >
                 > ```nix
                 > builtins.fetchTree {
                 >   type = "github";
                 >   owner = "NixOS";
                 >   repo = "nixpkgs";
                 >   rev = "ae2e6b3958682513d28f7d633734571fb18285dd";
                 > }
                 > ```
                 >
                 > This evaluates to the following attribute set:
                 >
                 > ```nix
                 > {
                 >   lastModified = 1686503798;
                 >   lastModifiedDate = "20230611171638";
                 >   narHash = "sha256-rA9RqKP9OlBrgGCPvfd5HVAXDOy8k2SmPtB/ijShNXc=";
                 >   outPath = "/nix/store/l5m6qlvfs9sdw14ja3qbzpglcjlb6j1x-source";
                 >   rev = "ae2e6b3958682513d28f7d633734571fb18285dd";
                 >   shortRev = "ae2e6b3";
                 > }
                 > ```

                 > **Example**
                 >
                 > Fetch the same GitHub repository using the URL-like syntax:
                 >
                 >   ```nix
                 >   builtins.fetchTree "github:NixOS/nixpkgs/ae2e6b3958682513d28f7d633734571fb18285dd"
                 >   ```\
                """;
            case "fetchurl" -> """
                ```nix
                builtins.fetchurl arg
                ```

                Download the specified URL and return the path of the downloaded file.
                `arg` can be either a string denoting the URL, or an attribute set with the following attributes:

                - `url`

                  The URL of the file to download.

                - `name` (default: the last path component of the URL)

                  A name for the file in the store. This can be useful if the URL has any
                  characters that are invalid for the store.

                Not available in [restricted evaluation mode](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-restrict-eval).\
                """;
            case "filter" -> """
                ```nix
                builtins.filter f list
                ```

                Return a list consisting of the elements of *list* for which the
                function *f* returns `true`.
                Has linear time complexity in the size of the input *list*.\
                """;
            case "filterSource" -> """
                ```nix
                builtins.filterSource e1 e2
                ```

                > **Warning**
                >
                > `filterSource` should not be used to filter store paths. Since
                > `filterSource` uses the name of the input directory while naming
                > the output directory, doing so produces a directory name in
                > the form of `<hash2>-<hash>-<name>`, where `<hash>-<name>` is
                > the name of the input directory. Since `<hash>` depends on the
                > unfiltered directory, the name of the output directory
                > indirectly depends on files that are filtered out by the
                > function. This triggers a rebuild even when a filtered out
                > file is changed. Use `builtins.path` instead, which allows
                > specifying the name of the output directory.

                This function allows you to copy sources into the Nix store while
                filtering certain files. For instance, suppose that you want to use
                the directory `source-dir` as an input to a Nix expression, e.g.

                ```nix
                stdenv.mkDerivation {
                  ...
                  src = ./source-dir;
                }
                ```

                However, if `source-dir` is a Subversion working copy, then all of
                those annoying `.svn` subdirectories are also copied to the
                store. Worse, the contents of those directories may change a lot,
                causing lots of spurious rebuilds. With `filterSource` you can
                filter out the `.svn` directories:

                ```nix
                src = builtins.filterSource
                  (path: type: type != "directory" || baseNameOf path != ".svn")
                  ./source-dir;
                ```

                Thus, the first argument *e1* must be a predicate function that is
                called for each regular file, directory or symlink in the source
                tree *e2*. If the function returns `true`, the file is copied to the
                Nix store, otherwise it is omitted. The function is called with two
                arguments. The first is the full path of the file. The second is a
                string that identifies the type of the file, which is either
                `"regular"`, `"directory"`, `"symlink"` or `"unknown"` (for other
                kinds of files such as device nodes or fifos — but note that those
                cannot be copied to the Nix store, so if the predicate returns
                `true` for them, the copy fails). If you exclude a directory,
                the entire corresponding subtree of *e2* is excluded.\
                """;
            case "findFile" -> """
                ```nix
                builtins.findFile search-path lookup-path
                ```

                Find *lookup-path* in *search-path*.

                [Lookup path](https://nix.dev/manual/nix/2.35/language/constructs/lookup-path.html) expressions are [desugared](https://en.wikipedia.org/wiki/Syntactic_sugar) using this and [`builtins.nixPath`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-nixPath):

                ```nix
                <nixpkgs>
                ```

                is equivalent to:

                ```nix
                builtins.findFile builtins.nixPath "nixpkgs"
                ```

                A search path is represented as a list of [attribute sets](https://nix.dev/manual/nix/2.35/language/types.html#type-attrs) with two attributes:
                - `prefix` is a relative path.
                - `path` denotes a file system location

                Examples of search path attribute sets:

                - ```
                  {
                    prefix = "";
                    path = "/nix/var/nix/profiles/per-user/root/channels";
                  }
                  ```
                - ```
                  {
                    prefix = "nixos-config";
                    path = "/etc/nixos/configuration.nix";
                  }
                  ```
                - ```
                  {
                    prefix = "nixpkgs";
                    path = "https://github.com/NixOS/nixpkgs/tarballs/master";
                  }
                  ```
                - ```
                  {
                    prefix = "nixpkgs";
                    path = "channel:nixpkgs-unstable";
                  }
                  ```
                - ```
                  {
                    prefix = "flake-compat";
                    path = "flake:github:edolstra/flake-compat";
                  }
                  ```

                The lookup algorithm checks each entry until a match is found, returning a [path value](https://nix.dev/manual/nix/2.35/language/types.html#type-path) of the match:

                - If a prefix of `lookup-path` matches `prefix`, then the remainder of *lookup-path* (the "suffix") is searched for within the directory denoted by `path`.
                  The contents of `path` may need to be downloaded at this point to look inside.

                - If the suffix is found inside that directory, then the entry is a match.
                  The combined absolute path of the directory (now downloaded if need be) and the suffix is returned.

                > **Example**
                >
                > A *search-path* value
                >
                > ```
                > [
                >   {
                >     prefix = "";
                >     path = "/home/eelco/Dev";
                >   }
                >   {
                >     prefix = "nixos-config";
                >     path = "/etc/nixos";
                >   }
                > ]
                > ```
                >
                > and a *lookup-path* value `"nixos-config"` causes Nix to try `/home/eelco/Dev/nixos-config` and `/etc/nixos` in that order and return the first path that exists.

                If `path` starts with `http://` or `https://`, it is interpreted as the URL of a tarball to be downloaded and unpacked to a temporary location.
                The tarball must consist of a single top-level directory.

                The URLs of the tarballs from the official `nixos.org` channels can be abbreviated as `channel:<channel-name>`.
                See [documentation on `nix-channel`](https://nix.dev/manual/nix/2.35/command-ref/nix-channel.html) for details about channels.

                > **Example**
                >
                > These two search path entries are equivalent:
                >
                > - ```
                >   {
                >     prefix = "nixpkgs";
                >     path = "channel:nixpkgs-unstable";
                >   }
                >   ```
                > - ```
                >   {
                >     prefix = "nixpkgs";
                >     path = "https://channels.nixos.org/nixos-unstable/nixexprs.tar.xz";
                >   }
                >   ```

                Search paths can also point to source trees using [flake URLs](https://nix.dev/manual/nix/2.35/command-ref/new-cli/nix3-flake.html#url-like-syntax).


                > **Example**
                >
                > The search path entry
                >
                > ```
                > {
                >   prefix = "nixpkgs";
                >   path = "flake:nixpkgs";
                > }
                > ```
                > specifies that the prefix `nixpkgs` shall refer to the source tree downloaded from the `nixpkgs` entry in the flake registry.
                >
                > Similarly
                >
                > ```
                > {
                >   prefix = "nixpkgs";
                >   path = "flake:github:nixos/nixpkgs/nixos-22.05";
                > }
                > ```
                >
                > makes `<nixpkgs>` refer to a particular branch of the `NixOS/nixpkgs` repository on GitHub.\
                """;
            case "flakeRefToString" -> """
                ```nix
                builtins.flakeRefToString attrs
                ```

                *Experimental feature* [`flakes`](https://nix.dev/manual/nix/2.35/development/experimental-features.html#xp-feature-flakes)

                Convert a flake reference from attribute set format to URL format.

                For example:

                ```nix
                builtins.flakeRefToString {
                  dir = "lib"; owner = "NixOS"; ref = "23.05"; repo = "nixpkgs"; type = "github";
                }
                ```

                evaluates to

                ```nix
                "github:NixOS/nixpkgs/23.05?dir=lib"
                ```\
                """;
            case "floor" -> """
                ```nix
                builtins.floor number
                ```

                Rounds and converts *number* to the next lower NixInt value if possible, i.e. `floor *number* <= *number*` and
                `*number* - floor *number* < 1`.

                An evaluation error is thrown, if there exists no such NixInt value `floor *number*`.
                Due to bugs in previous Nix versions an evaluation error might be thrown, if the datatype of *number* is
                a NixInt and if `*number* < -9007199254740992` or `*number* > 9007199254740992`.

                If the datatype of *number* is neither a NixInt (signed 64-bit integer) nor a NixFloat
                (IEEE-754 double-precision floating-point number), an evaluation error will be thrown.\
                """;
            case "foldl'" -> """
                ```nix
                builtins.foldl' op nul list
                ```

                Reduce a list by applying a binary operator, from left to right,
                e.g.
                ```nix
                foldl' op nul [ x0 x1 x2 ]
                  =
                    let
                      strictly = f: a: builtins.seq a (f a);
                      y0 = op nul x0;
                      y1 = strictly op y0 x1;
                      y2 = strictly op y1 x2;
                    in
                      y2

                  # and, ignoring strictness/laziness
                  ==
                    op (op (op nul x0) x1) x2
                ```

                For example, `foldl' (acc: elem: acc + elem) 0 [1 2 3]` evaluates
                to `6` and `foldl' (acc: elem: { "${elem}" = elem; } // acc) {}
                ["a" "b"]` evaluates to `{ a = "a"; b = "b"; }`.

                The first argument of `op` is the accumulator whereas the second
                argument is the current element being processed.

                The return value of each application of `op` is evaluated immediately,
                even for intermediate values.
                This way, `foldl'` can operate in constant stack space, allowing it to operate on large lists,
                regardless of [max-call-depth](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-max-call-depth).

                Conventionally, a fold function without the `'` ("prime") preserves laziness,
                but lacks these benefits.
                See also [Nixpkgs `lib.foldl`](https://nixos.org/manual/nixpkgs/unstable/#function-library-lib.lists.foldl).

                Has linear time complexity in the size of the list.\
                """;
            case "fromJSON" -> """
                ```nix
                builtins.fromJSON e
                ```

                Convert a JSON string to a Nix value. For example,

                ```nix
                builtins.fromJSON ''{"x": [1, 2, 3], "y": null}''
                ```

                returns the value `{ x = [ 1 2 3 ]; y = null; }`.\
                """;
            case "fromTOML" -> """
                ```nix
                builtins.fromTOML e
                ```

                Convert a TOML string to a Nix value. For example,

                ```nix
                builtins.fromTOML ''
                  x=1
                  s="a"
                  [table]
                  y=2
                ''
                ```

                returns the value `{ s = "a"; table = { y = 2; }; x = 1; }`.\
                """;
            case "functionArgs" -> """
                ```nix
                builtins.functionArgs f
                ```

                Return a set containing the names of the formal arguments expected
                by the function *f*. The value of each attribute is a Boolean
                denoting whether the corresponding argument has a default value. For
                instance, `functionArgs ({ x, y ? 123}: ...) = { x = false; y =
                true; }`.

                "Formal argument" here refers to the attributes pattern-matched by
                the function. Plain lambdas are not included, e.g. `functionArgs (x:
                ...) = { }`.

                Has constant time complexity.\
                """;
            case "genList" -> """
                ```nix
                builtins.genList generator length
                ```

                Generate list of size *length*, with each element *i* equal to the
                value returned by *generator* `i`. For example,

                ```nix
                builtins.genList (x: x * x) 5
                ```

                returns the list `[ 0 1 4 9 16 ]`.

                Has linear time complexity.\
                """;
            case "genericClosure" -> """
                ```nix
                builtins.genericClosure attrset
                ```

                `builtins.genericClosure` iteratively computes the transitive closure over an arbitrary relation defined by a function.

                It takes *attrset* with two attributes named `startSet` and `operator`, and returns a list of attribute sets:

                - `startSet`:
                  The initial list of attribute sets.

                - `operator`:
                  A function that takes an attribute set and returns a list of attribute sets.
                  It defines how each item in the current set is processed and expanded into more items.

                Each attribute set in the list `startSet` and the list returned by `operator` must have an attribute `key`, which must support equality comparison.
                The value of `key` can be one of the following types:

                - [Int](https://nix.dev/manual/nix/2.35/language/types.html#type-int)
                - [Float](https://nix.dev/manual/nix/2.35/language/types.html#type-float)
                - [Boolean](https://nix.dev/manual/nix/2.35/language/types.html#type-bool)
                - [String](https://nix.dev/manual/nix/2.35/language/types.html#type-string)
                - [Path](https://nix.dev/manual/nix/2.35/language/types.html#type-path)
                - [List](https://nix.dev/manual/nix/2.35/language/types.html#type-list)

                The result is produced by calling the `operator` on each `item` that has not been called yet, including newly added items, until no new items are added.
                Items are compared by their `key` attribute.

                Common usages are:

                - Generating unique collections of items, such as dependency graphs.
                - Traversing through structures that may contain cycles or loops.
                - Processing data structures with complex internal relationships.

                > **Example**
                >
                > ```nix
                > builtins.genericClosure {
                >   startSet = [ {key = 5;} ];
                >   operator = item: [{
                >     key = if (item.key / 2 ) * 2 == item.key
                >          then item.key / 2
                >          else 3 * item.key + 1;
                >   }];
                > }
                > ```
                >
                > evaluates to
                >
                > ```nix
                > [ { key = 5; } { key = 16; } { key = 8; } { key = 4; } { key = 2; } { key = 1; } ]
                > ```\
                """;
            case "getAttr" -> """
                ```nix
                builtins.getAttr s set
                ```

                `getAttr` returns the attribute named *s* from *set*. Evaluation
                aborts if the attribute doesn’t exist. This is a dynamic version of
                the `.` operator, since *s* is an expression rather than an
                identifier.

                Has `O(log n)` time complexity, where `n` is number of attributes in the *set*.\
                """;
            case "getContext" -> """
                ```nix
                builtins.getContext s
                ```

                Return the string context of *s*.

                The string context tracks references to derivations within a string.
                It is represented as an attribute set of [store derivation](https://nix.dev/manual/nix/2.35/glossary.html#gloss-store-derivation) paths mapping to output names.

                Using [string interpolation](https://nix.dev/manual/nix/2.35/language/string-interpolation.html) on a derivation adds that derivation to the string context.
                For example,

                ```nix
                builtins.getContext "${derivation { name = "a"; builder = "b"; system = "c"; }}"
                ```

                evaluates to

                ```
                { "/nix/store/arhvjaf6zmlyn8vh8fgn55rpwnxq0n7l-a.drv" = { outputs = [ "out" ]; }; }
                ```\
                """;
            case "getEnv" -> """
                ```nix
                builtins.getEnv s
                ```

                `getEnv` returns the value of the environment variable *s*, or an
                empty string if the variable doesn’t exist. This function should be
                used with care, as it can introduce all sorts of nasty environment
                dependencies in your Nix expression.

                `getEnv` is used in Nix Packages to locate the file
                `~/.nixpkgs/config.nix`, which contains user-local settings for Nix
                Packages. (That is, it does a `getEnv "HOME"` to locate the user’s
                home directory.)\
                """;
            case "getFlake" -> """
                ```nix
                builtins.getFlake args
                ```

                *Experimental feature* [`flakes`](https://nix.dev/manual/nix/2.35/development/experimental-features.html#xp-feature-flakes)

                Fetch a flake from a flake reference or a path, and return its output attributes and some metadata. For example:

                ```nix
                (builtins.getFlake "nix/55bc52401966fbffa525c574c14f67b00bc4fb3a").packages.x86_64-linux.nix
                ```

                Unless impure evaluation is allowed (`--impure`), the flake reference
                must be "locked", e.g. contain a Git revision or content hash. An
                example of an unlocked usage is:

                ```nix
                (builtins.getFlake "github:edolstra/dwarffs").rev
                ```\
                """;
            case "groupBy" -> """
                ```nix
                builtins.groupBy f list
                ```

                Groups elements of *list* together by the string returned from the
                function *f* called on each element. It returns an attribute set
                where each attribute value contains the elements of *list* that are
                mapped to the same corresponding attribute name returned by *f*.

                For example,

                ```nix
                builtins.groupBy (builtins.substring 0 1) ["foo" "bar" "baz"]
                ```

                evaluates to

                ```nix
                { b = [ "bar" "baz" ]; f = [ "foo" ]; }
                ```

                Has `O(n log n)` time complexity, where `n` is the size of the input *list*.\
                """;
            case "hasAttr" -> """
                ```nix
                builtins.hasAttr s set
                ```

                `hasAttr` returns `true` if *set* has an attribute named *s*, and
                `false` otherwise. This is a dynamic version of the `?` operator,
                since *s* is an expression rather than an identifier.

                Has `O(log n)` time complexity, where `n` is number of attributes in the *set*.\
                """;
            case "hasContext" -> """
                ```nix
                builtins.hasContext s
                ```

                Return `true` if string *s* has a non-empty context.
                The context can be obtained with
                [`getContext`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-getContext).

                > **Example**
                >
                > Many operations require a string context to be empty because they are intended only to work with "regular" strings, and also to help users avoid unintentionally loosing track of string context elements.
                > `builtins.hasContext` can help create better domain-specific errors in those case.
                >
                > ```nix
                > name: meta:
                >
                > if builtins.hasContext name
                > then throw "package name cannot contain string context"
                > else { ${name} = meta; }
                > ```\
                """;
            case "hashFile" -> """
                ```nix
                builtins.hashFile type p
                ```

                Return a base-16 representation of the cryptographic hash of the
                file at path *p*. The hash algorithm specified by *type* must be one
                of `"md5"`, `"sha1"`, `"sha256"` or `"sha512"`.\
                """;
            case "hashString" -> """
                ```nix
                builtins.hashString type s
                ```

                Return a base-16 representation of the cryptographic hash of string
                *s*. The hash algorithm specified by *type* must be one of `"md5"`,
                `"sha1"`, `"sha256"` or `"sha512"`.\
                """;
            case "head" -> """
                ```nix
                builtins.head list
                ```

                Return the first element of a list; abort evaluation if the argument
                isn’t a list or is an empty list. You can test whether a list is
                empty by comparing it with `[]`.

                Has constant time complexity.\
                """;
            case "import" -> """
                ```nix
                builtins.import path
                ```

                Load, parse, and return the Nix expression in the file *path*.

                > **Note**
                >
                > Unlike some languages, `import` is a regular function in Nix.

                The *path* argument must meet the same criteria as an [interpolated expression](https://nix.dev/manual/nix/2.35/language/string-interpolation.html#interpolated-expression).

                If *path* is a directory, the file `default.nix` in that directory is used if it exists.

                > **Example**
                >
                > ```console
                > $ echo 123 > default.nix
                > ```
                >
                > Import `default.nix` from the current directory.
                >
                > ```nix
                > import ./.
                > ```
                >
                >     123

                Evaluation aborts if the file doesn’t exist or contains an invalid Nix expression.

                A Nix expression loaded by `import` must not contain any *free variables*, that is, identifiers that are not defined in the Nix expression itself and are not built-in.
                Therefore, it cannot refer to variables that are in scope at the call site.

                > **Example**
                >
                > If you have a calling expression
                >
                > ```nix
                > rec {
                >   x = 123;
                >   y = import ./foo.nix;
                > }
                > ```
                >
                >  then the following `foo.nix` throws an error:
                >
                >  ```nix
                >  # foo.nix
                >  x + 456
                >  ```
                >
                >  since `x` is not in scope in `foo.nix`.
                > If you want `x` to be available in `foo.nix`, pass it as a function argument:
                >
                >  ```nix
                >  rec {
                >    x = 123;
                >    y = import ./foo.nix x;
                >  }
                >  ```
                >
                >  and
                >
                >  ```nix
                >  # foo.nix
                >  x: x + 456
                >  ```
                >
                >  The function argument doesn’t have to be called `x` in `foo.nix`; any name would work.\
                """;
            case "intersectAttrs" -> """
                ```nix
                builtins.intersectAttrs e1 e2
                ```

                Return a set consisting of the attributes in the set *e2* which have the
                same name as some attribute in *e1*.

                Has `O(n log m)` time complexity, where `n` and `m` are the sizes of the smallest and largest set respectively.\
                """;
            case "isAttrs" -> """
                ```nix
                builtins.isAttrs e
                ```

                Return `true` if *e* evaluates to a set, and `false` otherwise.\
                """;
            case "isBool" -> """
                ```nix
                builtins.isBool e
                ```

                Return `true` if *e* evaluates to a bool, and `false` otherwise.\
                """;
            case "isFloat" -> """
                ```nix
                builtins.isFloat e
                ```

                Return `true` if *e* evaluates to a float, and `false` otherwise.\
                """;
            case "isFunction" -> """
                ```nix
                builtins.isFunction e
                ```

                Return `true` if *e* evaluates to a function, and `false` otherwise.\
                """;
            case "isInt" -> """
                ```nix
                builtins.isInt e
                ```

                Return `true` if *e* evaluates to an integer, and `false` otherwise.\
                """;
            case "isList" -> """
                ```nix
                builtins.isList e
                ```

                Return `true` if *e* evaluates to a list, and `false` otherwise.\
                """;
            case "isNull" -> """
                ```nix
                builtins.isNull e
                ```

                Return `true` if *e* evaluates to `null`, and `false` otherwise.

                This is equivalent to `e == null`.\
                """;
            case "isPath" -> """
                ```nix
                builtins.isPath e
                ```

                Return `true` if *e* evaluates to a path, and `false` otherwise.\
                """;
            case "isString" -> """
                ```nix
                builtins.isString e
                ```

                Return `true` if *e* evaluates to a string, and `false` otherwise.\
                """;
            case "langVersion" -> """
                ```nix
                builtins.langVersion
                ```

                *Type:* integer

                The current version of the Nix language.\
                """;
            case "length" -> """
                ```nix
                builtins.length e
                ```

                Return the length of the list *e*.\
                """;
            case "lessThan" -> """
                ```nix
                builtins.lessThan e1 e2
                ```

                Return `true` if the value *e1* is less than the value *e2*, and `false` otherwise.
                Evaluation aborts if either *e1* or *e2* does not evaluate to a number, string or path.
                Furthermore, it aborts if *e2* does not match *e1*'s type according to the aforementioned classification of number, string or path.\
                """;
            case "listToAttrs" -> """
                ```nix
                builtins.listToAttrs e
                ```

                Construct a set from a list specifying the names and values of each
                attribute. Each element of the list should be a set consisting of a
                string-valued attribute `name` specifying the name of the attribute,
                and an attribute `value` specifying its value.

                In case of duplicate occurrences of the same name, the first
                takes precedence.

                Example:

                ```nix
                builtins.listToAttrs
                  [ { name = "foo"; value = 123; }
                    { name = "bar"; value = 456; }
                    { name = "bar"; value = 420; }
                  ]
                ```

                evaluates to

                ```nix
                { foo = 123; bar = 456; }
                ```

                Has `O(n log n)` time complexity, where `n` is size of the list.\
                """;
            case "map" -> """
                ```nix
                builtins.map f list
                ```

                Apply the function *f* to each element in the list *list*. For
                example,

                ```nix
                map (x: "foo" + x) [ "bar" "bla" "abc" ]
                ```

                evaluates to `[ "foobar" "foobla" "fooabc" ]`.

                Has `O(n)` time complexity, where `n` is the size of the *list*.
                Note that no calls to *f* are performed by the builtin, but *f* itself is evaluated and its type is checked eagerly.
                The function *f* is called on demand when a resulting list element is evaluated.\
                """;
            case "mapAttrs" -> """
                ```nix
                builtins.mapAttrs f attrset
                ```

                Apply function *f* to every element of *attrset*. For example,

                ```nix
                builtins.mapAttrs (name: value: value * 10) { a = 1; b = 2; }
                ```

                evaluates to `{ a = 10; b = 20; }`.

                Has `O(n)` time complexity, where `n` is the size of the *attrset*.
                Note that no calls to *f* are performed by the builtin.
                The function *f* is called on demand when a resulting attribute value is evaluated.\
                """;
            case "match" -> """
                ```nix
                builtins.match regex str
                ```

                Returns a list if the [extended POSIX regular
                expression](http://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap09.html#tag_09_04)
                *regex* matches *str* precisely, otherwise returns `null`. Each item
                in the list is a regex group.

                ```nix
                builtins.match "ab" "abc"
                ```

                Evaluates to `null`.

                ```nix
                builtins.match "abc" "abc"
                ```

                Evaluates to `[ ]`.

                ```nix
                builtins.match "a(b)(c)" "abc"
                ```

                Evaluates to `[ "b" "c" ]`.

                ```nix
                builtins.match "[[:space:]]+([[:upper:]]+)[[:space:]]+" "  FOO   "
                ```

                Evaluates to `[ "FOO" ]`.\
                """;
            case "mul" -> """
                ```nix
                builtins.mul e1 e2
                ```

                Return the product of the numbers *e1* and *e2*.\
                """;
            case "nixPath" -> """
                ```nix
                builtins.nixPath
                ```

                *Type:* list

                A list of search path entries used to resolve [lookup paths](https://nix.dev/manual/nix/2.35/language/constructs/lookup-path.html).
                Its value is primarily determined by the [`nix-path` configuration setting](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-nix-path), which are
                - Overridden by the [`NIX_PATH`](https://nix.dev/manual/nix/2.35/command-ref/env-common.html#env-NIX_PATH) environment variable or the `--nix-path` option
                - Extended by the [`-I` option](https://nix.dev/manual/nix/2.35/command-ref/opt-common.html#opt-I) or `--extra-nix-path`

                > **Example**
                >
                > ```bash
                > $ NIX_PATH= nix-instantiate --eval --expr "builtins.nixPath" -I foo=bar --no-pure-eval
                > [ { path = "bar"; prefix = "foo"; } ]
                > ```

                Lookup path expressions are [desugared](https://en.wikipedia.org/wiki/Syntactic_sugar) using this and
                [`builtins.findFile`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-findFile):

                ```nix
                <nixpkgs>
                ```

                is equivalent to:

                ```nix
                builtins.findFile builtins.nixPath "nixpkgs"
                ```\
                """;
            case "nixVersion" -> """
                ```nix
                builtins.nixVersion
                ```

                *Type:* string

                The version of Nix.

                For example, where the command line returns the current Nix version,

                ```shell-session
                $ nix --version
                nix (Nix) 2.16.0
                ```

                the Nix language evaluator returns the same value:

                ```nix-repl
                nix-repl> builtins.nixVersion
                "2.16.0"
                ```\
                """;
            case "null" -> """
                ```nix
                builtins.null
                ```

                *Type:* null

                Primitive value.

                The name `null` is not special, and can be shadowed:

                ```nix-repl
                nix-repl> let null = 1; in null
                1
                ```\
                """;
            case "outputOf" -> """
                ```nix
                builtins.outputOf derivation-reference output-name
                ```

                *Experimental feature* [`dynamic-derivations`](https://nix.dev/manual/nix/2.35/development/experimental-features.html#xp-feature-dynamic-derivations)

                Return the output path of a derivation, literally or using an
                [input placeholder string](https://nix.dev/manual/nix/2.35/store/derivation/index.html#input-placeholder)
                if needed.

                If the derivation has a statically-known output path (i.e. the derivation output is input-addressed, or fixed content-addressed), the output path is returned.
                But if the derivation is content-addressed or if the derivation is itself not-statically produced (i.e. is the output of another derivation), an input placeholder is returned instead.

                *`derivation reference`* must be a string that may contain a regular store path to a derivation, or may be an input placeholder reference.
                If the derivation is produced by a derivation, you must explicitly select `drv.outPath`.
                This primop can be chained arbitrarily deeply.
                For instance,

                ```nix
                builtins.outputOf
                  (builtins.outputOf myDrv "out")
                  "out"
                ```

                returns an input placeholder for the output of the output of `myDrv`.

                This primop corresponds to the `^` sigil for [deriving paths](https://nix.dev/manual/nix/2.35/glossary.html#gloss-deriving-path), e.g. as part of installable syntax on the command line.\
                """;
            case "parseDrvName" -> """
                ```nix
                builtins.parseDrvName s
                ```

                Split the string *s* into a package name and version. The package
                name is everything up to but not including the first dash not followed
                by a letter, and the version is everything following that dash. The
                result is returned in a set `{ name, version }`. Thus,
                `builtins.parseDrvName "nix-0.12pre12876"` returns `{ name =
                "nix"; version = "0.12pre12876"; }`.\
                """;
            case "parseFlakeRef" -> """
                ```nix
                builtins.parseFlakeRef flake-ref
                ```

                *Experimental feature* [`flakes`](https://nix.dev/manual/nix/2.35/development/experimental-features.html#xp-feature-flakes)

                Parse a flake reference, and return its exploded form.

                For example:

                ```nix
                builtins.parseFlakeRef "github:NixOS/nixpkgs/23.05?dir=lib"
                ```

                evaluates to:

                ```nix
                { dir = "lib"; owner = "NixOS"; ref = "23.05"; repo = "nixpkgs"; type = "github"; }
                ```\
                """;
            case "partition" -> """
                ```nix
                builtins.partition pred list
                ```

                Given a predicate function *pred*, this function returns an
                attrset containing a list named `right`, containing the elements
                in *list* for which *pred* returned `true`, and a list named
                `wrong`, containing the elements for which it returned
                `false`. For example,

                ```nix
                builtins.partition (x: x > 10) [1 23 9 3 42]
                ```

                evaluates to

                ```nix
                { right = [ 23 42 ]; wrong = [ 1 9 3 ]; }
                ```

                Runs in linear time in the size of the *list*.\
                """;
            case "path" -> """
                ```nix
                builtins.path args
                ```

                An enrichment of the built-in path type, based on the attributes
                present in *args*. All are optional except `path`:

                  - path\\
                    The underlying path.

                  - name\\
                    The name of the path when added to the store. This can used to
                    reference paths that have nix-illegal characters in their names,
                    like `@`.

                  - filter\\
                    A function of the type expected by [`builtins.filterSource`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-filterSource),
                    with the same semantics.

                  - recursive\\
                    When `false`, when `path` is added to the store it is with a
                    [flat hash](https://nix.dev/manual/nix/2.35/store/file-system-object/content-address.html#serial-flat),
                    rather than a hash of the
                    [NAR serialization](https://nix.dev/manual/nix/2.35/store/file-system-object/content-address.html#serial-nix-archive)
                    of the file. Thus, `path` must refer to a regular file, not a
                    directory. This allows similar behavior to `fetchurl`. Defaults
                    to `true`.

                  - sha256\\
                    When provided, this is the expected
                    [content hash](https://nix.dev/manual/nix/2.35/store/file-system-object/content-address.html)
                    of the path. Evaluation fails if the hash is incorrect,
                    and providing a hash allows `builtins.path` to be used even
                    when the `pure-eval` nix config option is on.\
                """;
            case "pathExists" -> """
                ```nix
                builtins.pathExists path
                ```

                Return `true` if the path *path* exists at evaluation time, and
                `false` otherwise.\
                """;
            case "placeholder" -> """
                ```nix
                builtins.placeholder output
                ```

                Return an
                [output placeholder string](https://nix.dev/manual/nix/2.35/store/derivation/index.html#output-placeholder)
                for the specified *output* that will be substituted by the corresponding
                [output path](https://nix.dev/manual/nix/2.35/glossary.html#gloss-output-path)
                at build time.

                Typical outputs would be `"out"`, `"bin"` or `"dev"`.\
                """;
            case "readDir" -> """
                ```nix
                builtins.readDir path
                ```

                Return the contents of the directory *path* as a set mapping
                directory entries to the corresponding file type. For instance, if
                directory `A` contains a regular file `B` and another directory
                `C`, then `builtins.readDir ./A` returns the set

                ```nix
                { B = "regular"; C = "directory"; }
                ```

                The possible values for the file type are `"regular"`,
                `"directory"`, `"symlink"` and `"unknown"`.\
                """;
            case "readFile" -> """
                ```nix
                builtins.readFile path
                ```

                Return the contents of the file *path* as a string.\
                """;
            case "readFileType" -> """
                ```nix
                builtins.readFileType p
                ```

                Determine the directory entry type of a filesystem node, being
                one of `"directory"`, `"regular"`, `"symlink"`, or `"unknown"`.\
                """;
            case "removeAttrs" -> """
                ```nix
                builtins.removeAttrs set list
                ```

                Remove the attributes listed in *list* from *set*. The attributes
                don’t have to exist in *set*. For instance,

                ```nix
                removeAttrs { x = 1; y = 2; z = 3; } [ "a" "x" "z" ]
                ```

                evaluates to `{ y = 2; }`.

                Has `O(n + k log k)` time complexity, where `n` is number of attributes in the *set* and `k` is the size of *list*.\
                """;
            case "replaceStrings" -> """
                ```nix
                builtins.replaceStrings from to s
                ```

                Given string *s*, replace every occurrence of the strings in *from*
                with the corresponding string in *to*.

                The argument *to* is lazy, that is, it is only evaluated when its corresponding pattern in *from* is matched in the string *s*

                Example:

                ```nix
                builtins.replaceStrings ["oo" "a"] ["a" "i"] "foobar"
                ```

                evaluates to `"fabir"`.

                Has `O(n k)` time complexity, where `n` is the length of *s* and `k` is the number of replacements.\
                """;
            case "scopedImport" -> """
                ```nix
                builtins.scopedImport scope path
                ```

                Load, parse, and return the Nix expression in the file *path*, with the attributes from *scope* available as variables in the lexical scope of the imported file.

                This function is similar to [`import`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-import), but allows you to provide additional variables that will be available in the scope of the imported expression.
                The *scope* argument must be an attribute set; each attribute becomes a variable available in the imported file.
                Built-in functions and values remain accessible unless shadowed by *scope* attributes.

                > **Note**
                >
                > Variables from *scope* shadow built-ins with the same name, allowing you to override built-ins for the imported expression.

                > **Note**
                >
                > Unlike [`import`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-import), `scopedImport` does not memoize evaluation results.
                > While the parsing result may be reused, each call produces a distinct value.
                > This is observable through performance and side effects such as [`builtins.trace`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-trace).

                The *path* argument must meet the same criteria as an [interpolated expression](https://nix.dev/manual/nix/2.35/language/string-interpolation.html#interpolated-expression).

                If *path* is a directory, the file `default.nix` in that directory is used if it exists.

                > **Example**
                >
                > Create a file `greet.nix`:
                >
                > ```nix
                > # greet.nix
                > "${greeting}, ${name}!"
                > ```
                >
                > Import it with additional variables in scope:
                >
                > ```nix
                > scopedImport { greeting = "Hello"; name = "World"; } ./greet.nix
                > ```
                >
                >     "Hello, World!"

                Evaluation aborts if the file doesn't exist or contains an invalid Nix expression.\
                """;
            case "seq" -> """
                ```nix
                builtins.seq e1 e2
                ```

                Evaluate *e1*, then evaluate and return *e2*. This ensures that a
                computation is strict in the value of *e1*.\
                """;
            case "sort" -> """
                ```nix
                builtins.sort comparator list
                ```

                Return *list* in sorted order. It repeatedly calls the function
                *comparator* with two elements. The comparator should return `true`
                if the first element is less than the second, and `false` otherwise.
                For example,

                ```nix
                builtins.sort builtins.lessThan [ 483 249 526 147 42 77 ]
                ```

                produces the list `[ 42 77 147 249 483 526 ]`.

                This is a stable sort: it preserves the relative order of elements
                deemed equal by the comparator.

                *comparator* must impose a strict weak ordering on the set of values
                in the *list*. This means that for any elements *a*, *b* and *c* from the
                *list*, *comparator* must satisfy the following relations:

                  1. Transitivity

                  If a is less than b and b is less than c, then it follows that a is less than c.

                  ```nix
                  comparator a b && comparator b c -> comparator a c
                  ```

                  1. Irreflexivity

                  ```nix
                  comparator a a == false
                  ```

                  1. Transitivity of equivalence

                  First, two values a and b are considered equivalent with respect to the comparator if:

                  ```
                  !comparator a b && !comparator b a
                  ```

                  In other words, neither is considered "less than" the other.

                  Transitivity of equivalence means:

                  If a is equivalent to b, and b is equivalent to c, then a must also be equivalent to c.

                  ```nix
                  let
                    equiv = x: y: (!comparator x y && !comparator y x);
                  in
                    equiv a b && equiv b c -> equiv a c
                  ```

                If the *comparator* violates any of these properties, then `builtins.sort`
                reorders elements in an unspecified manner.

                Runs in `O(n log n)` time on average, where `n` is the size of the *list*.
                Uses an adaptive sort that exploits existing sorted runs in the input, down to `O(n)` when the list is already sorted.\
                """;
            case "split" -> """
                ```nix
                builtins.split regex str
                ```

                Returns a list composed of non matched strings interleaved with the
                lists of the [extended POSIX regular
                expression](http://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap09.html#tag_09_04)
                *regex* matches of *str*. Each item in the lists of matched
                sequences is a regex group.

                ```nix
                builtins.split "(a)b" "abc"
                ```

                Evaluates to `[ "" [ "a" ] "c" ]`.

                ```nix
                builtins.split "([ac])" "abc"
                ```

                Evaluates to `[ "" [ "a" ] "b" [ "c" ] "" ]`.

                ```nix
                builtins.split "(a)|(c)" "abc"
                ```

                Evaluates to `[ "" [ "a" null ] "b" [ null "c" ] "" ]`.

                ```nix
                builtins.split "([[:upper:]]+)" " FOO "
                ```

                Evaluates to `[ " " [ "FOO" ] " " ]`.\
                """;
            case "splitVersion" -> """
                ```nix
                builtins.splitVersion s
                ```

                Split a string representing a version into its components, by the
                same version splitting logic underlying the version comparison in
                [`nix-env -u`](https://nix.dev/manual/nix/2.35/command-ref/nix-env/upgrade.html).\
                """;
            case "storeDir" -> """
                ```nix
                builtins.storeDir
                ```

                *Type:* string

                Logical file system location of the [Nix store](https://nix.dev/manual/nix/2.35/glossary.html#gloss-store) currently in use.

                This value is determined by the `store` parameter in [Store URLs](https://nix.dev/manual/nix/2.35/store/types/index.html#store-url-format):

                ```shell-session
                $ nix-instantiate --store 'dummy://?store=/blah' --eval --expr builtins.storeDir
                "/blah"
                ```\
                """;
            case "storePath" -> """
                ```nix
                builtins.storePath path
                ```

                This function allows you to define a dependency on an already
                existing store path. For example, the derivation attribute `src
                = builtins.storePath /nix/store/f1d18v1y…-source` causes the
                derivation to depend on the specified path, which must exist or
                be substitutable. Note that this differs from a plain path
                (e.g. `src = /nix/store/f1d18v1y…-source`) in that the latter
                causes the path to be *copied* again to the Nix store, resulting
                in a new path (e.g. `/nix/store/ld01dnzc…-source-source`).

                Not available in [pure evaluation mode](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-pure-eval).

                See also [`builtins.fetchClosure`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-fetchClosure).\
                """;
            case "stringLength" -> """
                ```nix
                builtins.stringLength e
                ```

                Return the number of bytes of the string *e*. If *e* is not a string,
                evaluation is aborted.\
                """;
            case "sub" -> """
                ```nix
                builtins.sub e1 e2
                ```

                Return the difference between the numbers *e1* and *e2*.\
                """;
            case "substring" -> """
                ```nix
                builtins.substring start len s
                ```

                Return the substring of *s* from byte position *start*
                (zero-based) up to but not including *start + len*. If *start* is
                greater than the length of the string, an empty string is returned.
                If *start + len* lies beyond the end of the string or *len* is `-1`,
                only the substring up to the end of the string is returned.
                *start* must be non-negative.
                For example,

                ```nix
                builtins.substring 0 3 "nixos"
                ```

                evaluates to `"nix"`.\
                """;
            case "tail" -> """
                ```nix
                builtins.tail list
                ```

                Return the list without its first item; abort evaluation if
                the argument isn’t a list or is an empty list.

                > **Warning**
                >
                > This function should generally be avoided since it's inefficient:
                > unlike Haskell's `tail`, it takes O(n) time, so recursing over a
                > list by repeatedly calling `tail` takes O(n^2) time.\
                """;
            case "throw" -> """
                ```nix
                builtins.throw s
                ```

                Throw an error message *s*. This usually aborts Nix expression
                evaluation, but in `nix-env -qa` and other commands that try to
                evaluate a set of derivations to get information about those
                derivations, a derivation that throws an error is silently skipped
                (which is not the case for `abort`).\
                """;
            case "toFile" -> """
                ```nix
                builtins.toFile name s
                ```

                Store the string *s* in a file in the Nix store and return its
                path.  The file has suffix *name*. This file can be used as an
                input to derivations. One application is to write builders
                “inline”. For instance, the following Nix expression combines the
                Nix expression for GNU Hello and its build script into one file:

                ```nix
                { stdenv, fetchurl, perl }:

                stdenv.mkDerivation {
                  name = "hello-2.1.1";

                  builder = builtins.toFile "builder.sh" "
                    source $stdenv/setup

                    PATH=$perl/bin:$PATH

                    tar xvfz $src
                    cd hello-*
                    ./configure --prefix=$out
                    make
                    make install
                  ";

                  src = fetchurl {
                    url = "http://ftp.nluug.nl/pub/gnu/hello/hello-2.1.1.tar.gz";
                    sha256 = "1md7jsfd8pa45z73bz1kszpp01yw6x5ljkjk2hx7wl800any6465";
                  };
                  inherit perl;
                }
                ```

                It is even possible for one file to refer to another, e.g.,

                ```nix
                builder = let
                  configFile = builtins.toFile "foo.conf" "
                    # This is some dummy configuration file.
                    ...
                  ";
                in builtins.toFile "builder.sh" "
                  source $stdenv/setup
                  ...
                  cp ${configFile} $out/etc/foo.conf
                ";
                ```

                Note that `${configFile}` is a
                [string interpolation](https://nix.dev/manual/nix/2.35/language/types.html#type-string), so the result of the
                expression `configFile`
                (i.e., a path like `/nix/store/m7p7jfny445k...-foo.conf`) will be
                spliced into the resulting string.

                It is however *not* allowed to have files mutually referring to each
                other, like so:

                ```nix
                let
                  foo = builtins.toFile "foo" "...${bar}...";
                  bar = builtins.toFile "bar" "...${foo}...";
                in foo
                ```

                This is not allowed because it would cause a cyclic dependency in
                the computation of the cryptographic hashes for `foo` and `bar`.

                It is also not possible to reference the result of a derivation. If
                you are using Nixpkgs, the `writeTextFile` function is able to do
                that.\
                """;
            case "toJSON" -> """
                ```nix
                builtins.toJSON e
                ```

                Return a string containing a JSON representation of *e*. Strings,
                integers, floats, booleans, nulls and lists are mapped to their JSON
                equivalents. Sets (except derivations) are represented as objects.
                Derivations are translated to a JSON string containing the
                derivation’s output path. Paths are copied to the store and
                represented as a JSON string of the resulting store path.\
                """;
            case "toPath" -> """
                ```nix
                builtins.toPath s
                ```

                **DEPRECATED.** Use `/. + "/path"` to convert a string into an absolute
                path. For relative paths, use `./. + "/path"`.\
                """;
            case "toString" -> """
                ```nix
                builtins.toString e
                ```

                Convert the expression *e* to a string. *e* can be:

                  - A string (in which case the string is returned unmodified).

                  - A path (e.g., `toString /foo/bar` yields `"/foo/bar"`.

                  - A set containing `{ __toString = self: ...; }` or `{ outPath = ...; }`.

                  - An integer.

                  - A list, in which case the string representations of its elements
                    are joined with spaces.

                  - A Boolean (`false` yields `""`, `true` yields `"1"`).

                  - `null`, which yields the empty string.\
                """;
            case "toXML" -> """
                ```nix
                builtins.toXML e
                ```

                Return a string containing an XML representation of *e*. The main
                application for `toXML` is to communicate information with the
                builder in a more structured format than plain environment
                variables.

                Here is an example where this is the case:

                ```nix
                { stdenv, fetchurl, libxslt, jira, uberwiki }:

                stdenv.mkDerivation (rec {
                  name = "web-server";

                  buildInputs = [ libxslt ];

                  builder = builtins.toFile "builder.sh" "
                    source $stdenv/setup
                    mkdir $out
                    echo "$servlets" | xsltproc ${stylesheet} - > $out/server-conf.xml ①
                  ";

                  stylesheet = builtins.toFile "stylesheet.xsl" ②
                   "<?xml version='1.0' encoding='UTF-8'?>
                    <xsl:stylesheet xmlns:xsl='http://www.w3.org/1999/XSL/Transform' version='1.0'>
                      <xsl:template match='/'>
                        <Configure>
                          <xsl:for-each select='/expr/list/attrs'>
                            <Call name='addWebApplication'>
                              <Arg><xsl:value-of select=\\"attr[@name = 'path']/string/@value\\" /></Arg>
                              <Arg><xsl:value-of select=\\"attr[@name = 'war']/path/@value\\" /></Arg>
                            </Call>
                          </xsl:for-each>
                        </Configure>
                      </xsl:template>
                    </xsl:stylesheet>
                  ";

                  servlets = builtins.toXML [ ③
                    { path = "/bugtracker"; war = jira + "/lib/atlassian-jira.war"; }
                    { path = "/wiki"; war = uberwiki + "/uberwiki.war"; }
                  ];
                })
                ```

                The builder is supposed to generate the configuration file for a
                [Jetty servlet container](http://jetty.mortbay.org/). A servlet
                container contains a number of servlets (`*.war` files) each
                exported under a specific URI prefix. So the servlet configuration
                is a list of sets containing the `path` and `war` of the servlet
                (①). This kind of information is difficult to communicate with the
                normal method of passing information through an environment
                variable, which just concatenates everything together into a
                string (which might just work in this case, but wouldn’t work if
                fields are optional or contain lists themselves). Instead the Nix
                expression is converted to an XML representation with `toXML`,
                which is unambiguous and can easily be processed with the
                appropriate tools. For instance, in the example an XSLT stylesheet
                (at point ②) is applied to it (at point ①) to generate the XML
                configuration file for the Jetty server. The XML representation
                produced at point ③ by `toXML` is as follows:

                ```xml
                <?xml version='1.0' encoding='utf-8'?>
                <expr>
                  <list>
                    <attrs>
                      <attr name="path">
                        <string value="/bugtracker" />
                      </attr>
                      <attr name="war">
                        <path value="/nix/store/d1jh9pasa7k2...-jira/lib/atlassian-jira.war" />
                      </attr>
                    </attrs>
                    <attrs>
                      <attr name="path">
                        <string value="/wiki" />
                      </attr>
                      <attr name="war">
                        <path value="/nix/store/y6423b1yi4sx...-uberwiki/uberwiki.war" />
                      </attr>
                    </attrs>
                  </list>
                </expr>
                ```

                Note that we used the `toFile` built-in to write the builder and
                the stylesheet “inline” in the Nix expression. The path of the
                stylesheet is spliced into the builder using the syntax `xsltproc
                ${stylesheet}`.\
                """;
            case "trace" -> """
                ```nix
                builtins.trace e1 e2
                ```

                Evaluate *e1* and print its abstract syntax representation on
                standard error. Then return *e2*. This function is useful for
                debugging.

                If the
                [`debugger-on-trace`](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-debugger-on-trace)
                option is set to `true` and the `--debugger` flag is given, the
                interactive debugger is started when `trace` is called (like
                [`break`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-break)).\
                """;
            case "traceVerbose" -> """
                ```nix
                builtins.traceVerbose e1 e2
                ```

                Evaluate *e1* and print its abstract syntax representation on standard
                error if `--trace-verbose` is enabled. Then return *e2*. This function
                is useful for debugging.\
                """;
            case "true" -> """
                ```nix
                builtins.true
                ```

                *Type:* Boolean

                Primitive value.

                It can be returned by
                [comparison operators](https://nix.dev/manual/nix/2.35/language/operators.html#comparison)
                and used in
                [conditional expressions](https://nix.dev/manual/nix/2.35/language/syntax.html#conditionals).

                The name `true` is not special, and can be shadowed:

                ```nix-repl
                nix-repl> let true = 1; in true
                1
                ```\
                """;
            case "tryEval" -> """
                ```nix
                builtins.tryEval e
                ```

                Try to shallowly evaluate *e*. Return a set containing the
                attributes `success` (`true` if *e* evaluated successfully,
                `false` if an error was thrown) and `value`, equalling *e* if
                successful and `false` otherwise. `tryEval` only prevents
                errors created by `throw` or `assert` from being thrown.
                Errors `tryEval` doesn't catch are, for example, those created
                by `abort` and type errors generated by builtins. Also note that
                this doesn't evaluate *e* deeply, so `let e = { x = throw ""; };
                in (builtins.tryEval e).success` is `true`. Using
                `builtins.deepSeq` one can get the expected result:
                `let e = { x = throw ""; }; in
                (builtins.tryEval (builtins.deepSeq e e)).success` is
                `false`.

                `tryEval` intentionally does not return the error message, because that risks bringing non-determinism into the evaluation result, and it would become very difficult to improve error reporting without breaking existing expressions.
                Instead, use [`builtins.addErrorContext`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-addErrorContext) to add context to the error message, and use a Nix unit testing tool for testing.\
                """;
            case "typeOf" -> """
                ```nix
                builtins.typeOf e
                ```

                Return a string representing the type of the value *e*, namely
                `"int"`, `"bool"`, `"string"`, `"path"`, `"null"`, `"set"`,
                `"list"`, `"lambda"` or `"float"`.\
                """;
            case "unsafeDiscardOutputDependency" -> """
                ```nix
                builtins.unsafeDiscardOutputDependency s
                ```

                Create a copy of the given string where every
                [derivation deep](https://nix.dev/manual/nix/2.35/language/string-context.html#string-context-element-derivation-deep)
                string context element is turned into a
                [constant](https://nix.dev/manual/nix/2.35/language/string-context.html#string-context-constant)
                string context element.

                This is the opposite of [`builtins.addDrvOutputDependencies`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-addDrvOutputDependencies).

                This is unsafe because it allows us to "forget" store objects we would have otherwise referred to with the string context,
                whereas Nix normally tracks all dependencies consistently.
                Safe operations "grow" but never "shrink" string contexts.
                [`builtins.addDrvOutputDependencies`] in contrast is safe because "derivation deep" string context element always refers to the underlying derivation (among many more things).
                Replacing a constant string context element with a "derivation deep" element is a safe operation that just enlargens the string context without forgetting anything.

                [`builtins.addDrvOutputDependencies`]: #builtins-addDrvOutputDependencies\
                """;
            case "unsafeDiscardStringContext" -> """
                ```nix
                builtins.unsafeDiscardStringContext s
                ```

                Discard the [string context](https://nix.dev/manual/nix/2.35/language/string-context.html) from a value that can be coerced to a string.\
                """;
            case "unsafeGetAttrPos" -> """
                ```nix
                builtins.unsafeGetAttrPos s set
                ```

                `unsafeGetAttrPos` returns the position of the attribute named *s*
                from *set*. This is used by Nixpkgs to provide location information
                in error messages.\
                """;
            case "warn" -> """
                ```nix
                builtins.warn e1 e2
                ```

                Evaluate *e1*, which must be a string, and print it on standard error as a warning.
                Then return *e2*.
                This function is useful for non-critical situations where attention is advisable.

                If the
                [`debugger-on-trace`](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-debugger-on-trace)
                or [`debugger-on-warn`](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-debugger-on-warn)
                option is set to `true` and the `--debugger` flag is given, the
                interactive debugger will be started when `warn` is called (like
                [`break`](https://nix.dev/manual/nix/2.35/language/builtins.html#builtins-break)).

                If the
                [`abort-on-warn`](https://nix.dev/manual/nix/2.35/command-ref/conf-file.html#conf-abort-on-warn)
                option is set, the evaluation is aborted after the warning is printed.
                This is useful to reveal the stack trace of the warning, when the context is non-interactive and a debugger can not be launched.\
                """;
            case "zipAttrsWith" -> """
                ```nix
                builtins.zipAttrsWith f list
                ```

                Transpose a list of attribute sets into an attribute set of lists,
                then apply `mapAttrs`.

                `f` receives two arguments: the attribute name and a non-empty
                list of all values encountered for that attribute name.

                The result is an attribute set where the attribute names are the
                union of the attribute names in each element of `list`. The attribute
                values are the return values of `f`.

                ```nix
                builtins.zipAttrsWith
                  (name: values: { inherit name values; })
                  [ { a = "x"; } { a = "y"; b = "z"; } ]
                ```

                evaluates to

                ```
                {
                  a = { name = "a"; values = [ "x" "y" ]; };
                  b = { name = "b"; values = [ "z" ]; };
                }
                ```

                Has `O(n log n)` time complexity, where `n` is the number of attributes across all sets.\
                """;
            default -> null;
        };
    }
}
