package org.affogato.lint;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.affogato.parser.Parser;
import org.affogato.parser.Syntax;
import org.affogato.parser.Syntax.*;
import org.affogato.parser.TokenType;

/**
 * The packages of a file whose source comes from a forge, a registry or a URL with the version in it, and the edits
 * that move them to another version.
 */
public final class Packages {
    /** Stand for the version, its first two components and its first one in a resolved string. */
    private static final char VERSION = '\0';

    private static final char MAJOR_MINOR = '\1';
    private static final char MAJOR = '\2';

    private static final String VERSION_ONLY = String.valueOf(VERSION);

    /** Why a package pinned to a commit whose version is not of the {@code 0-unstable-YYYY-MM-DD} shape is skipped. */
    public static final String PINNED = "pinned to a commit";

    private static final Pattern UNSTABLE = Pattern.compile("(.*?)unstable-\\d{4}-\\d{2}-\\d{2}");

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    /** Where a source comes from. */
    public enum Kind {
        GITHUB,
        GITLAB,
        GITEA,
        SOURCEHUT,
        BITBUCKET,
        PYPI,
        CRATES,
        URL;

        /** Whether its sources are repositories, with tags and branches. */
        public boolean forge() {
            return compareTo(BITBUCKET) <= 0;
        }
    }

    private static final Map<String, Kind> FETCHERS = Map.of(
            "fetchFromGitHub", Kind.GITHUB,
            "fetchFromGitLab", Kind.GITLAB,
            "fetchFromGitea", Kind.GITEA,
            "fetchFromCodeberg", Kind.GITEA,
            "fetchFromSourcehut", Kind.SOURCEHUT,
            "fetchFromBitbucket", Kind.BITBUCKET,
            "fetchPypi", Kind.PYPI,
            "fetchCrate", Kind.CRATES,
            "fetchurl", Kind.URL,
            "fetchzip", Kind.URL);

    private static final Set<String> FORGE_ARGS = Set.of("owner", "repo", "rev", "tag", "hash", "sha256", "name");

    /** The arguments each fetcher takes besides {@link #FORGE_ARGS}, or all of them for the others. */
    private static final Map<Kind, Set<String>> ARGS = Map.of(
            Kind.GITHUB, Set.of("githubBase"),
            Kind.GITLAB, Set.of("domain", "group"),
            Kind.GITEA, Set.of("domain"),
            Kind.SOURCEHUT, Set.of("domain", "vc"),
            Kind.BITBUCKET, Set.of(),
            Kind.PYPI, Set.of("pname", "version", "hash", "sha256", "extension", "format"),
            Kind.CRATES, Set.of("pname", "crateName", "version", "hash", "sha256"),
            Kind.URL, Set.of("url", "hash", "sha256", "name", "pname", "version"));

    /** Options of the fetchers that are fine when false, as they are by default. */
    private static final Set<String> GIT_OPTIONS =
            Set.of("fetchSubmodules", "leaveDotGit", "deepClone", "forceFetchGit", "fetchLFS");

    private static final Set<String> FAKE_HASHES = Set.of("fakeHash", "fakeSha256");

    /** The hashes builders take for what a build fetches besides its source, and the attribute that fetches it. */
    private static final Map<String, String> DEPENDENCY_HASHES = Map.of(
            "cargoHash", "cargoDeps",
            "cargoSha256", "cargoDeps",
            "vendorHash", "goModules",
            "vendorSha256", "goModules",
            "npmDepsHash", "npmDeps",
            "mvnHash", "fetchedMavenDeps");

    /** Fetchers of dependencies, such as {@code cargoDeps = rustPlatform.fetchCargoVendor { hash = …; }}. */
    private static final Pattern DEPENDENCY_FETCHER = Pattern.compile("fetch[A-Za-z]*Deps|fetchCargoVendor");

    /** Update scripts that do what updating the package does, so that they are not run instead. */
    private static final Set<String> GENERIC_UPDATERS = Set.of(
            "nix-update-script",
            "gitUpdater",
            "unstableGitUpdater",
            "genericUpdater",
            "directoryListingUpdater",
            "httpTwoLevelsUpdater");

    /**
     * The hash {@code name} of what the derivation's attribute {@code attr} fetches. {@code start} is -1 when the hash
     * is not a string to rewrite.
     */
    public record Dependency(String name, String attr, int start, int end) {
        public Edit update(String hash) {
            return new Edit(start, end, quote(hash));
        }
    }

    /** A file next to the package that lists its dependencies, and is generated again for each version. */
    public enum LockType {
        /** {@code cargoLock.lockFile}, the Cargo.lock of the source. */
        CARGO,
        /** {@code nugetDeps} of buildDotnetModule, written by its {@code fetch-deps} script. */
        NUGET,
        /** The {@code data} of {@code mitmCache = gradle.fetchDeps}, written by its {@code updateScript}. */
        GRADLE
    }

    /**
     * The file {@code path}, as written in the package, relative to it. {@code outputHashes} says whether a Cargo.lock
     * comes with the hashes of its git dependencies, which are not updated.
     */
    public record Lock(LockType type, String path, boolean outputHashes) {}

    /**
     * How the derivation of a package is built from its file: the edits after which the file's value, called with
     * callPackage, leads to it by the attribute {@code path}, as a package in a {@code let} becomes the value of the
     * {@code let} once its body is the package's name.
     */
    public record Route(List<String> path, List<Edit> edits) {}

    /**
     * A call of a fetcher. {@code template} is its tag, or its URL for a registry or a URL, with the version in it; or
     * the commit a source is pinned to. {@code revStart} is -1 when the tag follows the version binding, else the span
     * of the literal to rewrite. {@code domain} is the forge's host, as {@code git.sr.ht}; {@code owner} the name of
     * a package on a registry. {@code unpack} says whether the hash is that of the unpacked archive.
     * {@code built} names the argument that makes its hash differ from the archive's, as {@code fetchSubmodules}:
     * the source is then built with a fake hash, as the attribute {@code attr} of the derivation, null when it is
     * not one.
     */
    public record Source(
            Kind kind,
            String domain,
            String owner,
            String repo,
            String template,
            boolean unpack,
            int hashStart,
            int hashEnd,
            int revStart,
            int revEnd,
            List<String> attr,
            String built) {
        /** The tag, or URL, at {@code version}. */
        public String tag(String version) {
            String majorMinor = version;
            String[] parts = version.split("\\.");
            if (parts.length > 2) majorMinor = parts[0] + "." + parts[1];
            return template.replace(VERSION_ONLY, version)
                    .replace(String.valueOf(MAJOR_MINOR), majorMinor)
                    .replace(String.valueOf(MAJOR), parts[0]);
        }

        /** What is fetched: {@code commit} when not null, else the tag at {@code version}. */
        public String rev(String version, String commit) {
            return commit != null ? commit : tag(version);
        }

        /** The part of the template before the version. */
        public String prefix() {
            int at = template.indexOf(VERSION);
            return at < 0 ? template : template.substring(0, at);
        }

        /** The part of the template after the version. */
        public String suffix() {
            int at = template.indexOf(VERSION);
            return at < 0 ? "" : template.substring(at + 1);
        }

        /** The prefix of the tag as forges name it. */
        public String tagPrefix() {
            String prefix = prefix();
            return prefix.startsWith("refs/tags/") ? prefix.substring("refs/tags/".length()) : prefix;
        }
    }

    /**
     * {@code versionStart} is -1 when the version is only in the tag. {@code skipped} says why the package cannot be
     * updated, else is null; a package pinned to a commit keeps its source, with the commit in {@code commit}, so
     * that its hashes can be refreshed at that commit, and is updated to the head of the default branch when its
     * version is of the {@code 0-unstable-YYYY-MM-DD} shape. {@code route} builds its derivation, null when the file's
     * value does not lead to it; {@code name} is null when the file does not name the package. {@code updateScript}
     * says whether it has a {@code passthru.updateScript} of its own, not one that does what updating it does. {@code
     * start} and {@code end} span the derivation, else the fetcher.
     */
    public record Package(
            String name,
            String version,
            int versionStart,
            int versionEnd,
            List<Source> sources,
            List<Dependency> dependencies,
            List<Lock> locks,
            String skipped,
            String commit,
            boolean updateScript,
            int start,
            int end,
            Route route) {
        /** Whether it can be updated; to the head of its branch as well when {@code branch}, though not unstable. */
        public boolean updatable(boolean branch) {
            return skipped == null
                    || branch && PINNED.equals(skipped) && sources.getFirst().revStart() >= 0;
        }

        /**
         * The edits to {@code version}, and to {@code commit} when not null, with the hash of each source in order,
         * which is left as it is when null.
         */
        public List<Edit> update(String version, String commit, List<String> hashes) {
            List<Edit> out = new ArrayList<>();
            if (versionStart >= 0 && version != null) out.add(new Edit(versionStart, versionEnd, quote(version)));
            for (int i = 0; i < sources.size(); i++) {
                Source s = sources.get(i);
                if (hashes.get(i) != null) out.add(new Edit(s.hashStart, s.hashEnd, quote(hashes.get(i))));
                if (s.revStart >= 0) out.add(new Edit(s.revStart, s.revEnd, quote(s.rev(version, commit))));
            }
            return out;
        }

        /** {@link #update(String, String, List)} to a release. */
        public List<Edit> update(String version, List<String> hashes) {
            return update(version, null, hashes);
        }
    }

    /**
     * The version of a snapshot at {@code date}, as {@code YYYY-MM-DD}, after {@code version}: its prefix kept when
     * it is of the {@code 0-unstable-YYYY-MM-DD} shape already.
     */
    public static String unstable(String version, String date) {
        if (version == null || version.isEmpty()) return "0-unstable-" + date;
        Matcher m = UNSTABLE.matcher(version);
        if (m.matches()) return m.group(1) + "unstable-" + date;
        return version + "-unstable-" + date;
    }

    private final String src;
    private final Scope scope;
    /** The literal of the version binding the last {@link #resolve} went through. */
    private Str version;

    private Packages(String src, Scope scope) {
        this.src = src;
        this.scope = scope;
    }

    /** @throws org.affogato.parser.ParseException when {@code src} does not parse */
    public static List<Package> find(String src) {
        Node root = Parser.parseSyntax(src);
        Parser.parse(src);
        return new Packages(src, new Scope(root)).find();
    }

    /** What the scan of one fetcher found: a source, or why there is none. */
    private record Scan(Source source, String why, Str versionLit, String version, String commit) {
        static Scan skip(String why) {
            return new Scan(null, why, null, null, null);
        }
    }

    private List<Package> find() {
        List<Package> out = new ArrayList<>();
        Map<Str, Integer> byVersion = new IdentityHashMap<>();
        for (Node n : scope.nodes) {
            if (!(n instanceof Call c) || c.args().size() != 1) continue;
            Kind kind = FETCHERS.get(fnName(c.fn()));
            if (kind == null || !(c.args().getFirst() instanceof AttrSet args)) continue;
            AttrSet drv = derivation(c);
            Scan scan = scan(kind, fnName(c.fn()), c, args, drv);
            if (scan == null) continue;
            String name = drv == null ? null : name(drv);
            List<Dependency> deps = drv == null ? List.of() : dependencies(drv);
            List<Lock> locks = drv == null ? List.of() : locks(drv);
            boolean script = drv != null && updateScript(drv);
            Node span = drv != null ? drv : c;
            int start = Nodes.start(span);
            int end = Nodes.end(span);
            Route route = drv == null ? null : route(drv);
            if (scan.source == null) {
                out.add(new Package(
                        name, null, -1, -1, List.of(), deps, locks, scan.why, null, script, start, end, route));
                continue;
            }
            Source s = scan.source;
            Integer existing = scan.versionLit == null ? null : byVersion.get(scan.versionLit);
            if (existing != null && scan.commit == null) {
                Package p = out.get(existing);
                List<Source> sources = new ArrayList<>(p.sources());
                if (isSrc(drv, c)) sources.add(0, s);
                else sources.add(s);
                String why = p.skipped() != null ? p.skipped() : scan.why;
                out.set(
                        existing,
                        new Package(
                                p.name(),
                                p.version(),
                                p.versionStart(),
                                p.versionEnd(),
                                List.copyOf(sources),
                                p.dependencies(),
                                p.locks(),
                                why,
                                null,
                                p.updateScript(),
                                p.start(),
                                p.end(),
                                p.route()));
                continue;
            }
            if (scan.versionLit != null && scan.commit == null) byVersion.put(scan.versionLit, out.size());
            out.add(new Package(
                    name,
                    scan.version,
                    scan.versionLit == null ? -1 : Nodes.start(scan.versionLit),
                    scan.versionLit == null ? -1 : Nodes.end(scan.versionLit),
                    List.of(s),
                    deps,
                    locks,
                    scan.why,
                    scan.commit,
                    script,
                    start,
                    end,
                    route));
        }
        return out;
    }

    /** The source {@code c} fetches, why it has none, or null when it is not one to report. */
    private Scan scan(Kind kind, String fn, Call c, AttrSet args, AttrSet drv) {
        Node hash = arg(args, "hash");
        if (hash == null) hash = arg(args, "sha256");
        String built = built(kind, args);
        List<String> attr = attribute(drv, c);
        String domain = null;
        String owner = null;
        String repo = null;
        Node rev;
        String template;
        boolean unpack = true;
        version = null;
        switch (kind) {
            case PYPI, CRATES -> {
                owner = kind == Kind.CRATES && constant(args, "crateName") != null
                        ? constant(args, "crateName")
                        : constant(args, "pname");
                if (owner == null || owner.isEmpty()) return Scan.skip("the name is not a constant");
                if (kind == Kind.PYPI && "wheel".equals(constant(args, "format")))
                    return Scan.skip("wheels are not supported");
                rev = arg(args, "version");
                String v = binding(args, "version") == null ? null : resolveBinding(args, "version", 0);
                if (!VERSION_ONLY.equals(v)) return Scan.skip("the version is not a constant");
                if (kind == Kind.PYPI) {
                    String extension = constant(args, "extension");
                    template = "https://files.pythonhosted.org/packages/source/" + owner.charAt(0) + "/" + owner + "/"
                            + owner + "-" + VERSION + "." + (extension == null ? "tar.gz" : extension);
                    unpack = false;
                } else {
                    template = "https://crates.io/api/v1/crates/" + owner + "/" + VERSION + "/download";
                }
                rev = null;
            }
            case URL -> {
                rev = arg(args, "url");
                template = rev == null ? null : resolve(rev, 0);
                unpack = fn.equals("fetchzip");
                if (template == null || !hasVersion(template)) {
                    if (!isSrc(drv, c)) return null;
                    String literal = rev instanceof Str s ? literal(s) : null;
                    Str v = drv == null ? null : versionOf(drv);
                    String lv = v == null ? null : literal(v);
                    if (literal == null || lv == null || lv.isEmpty() || !literal.contains(lv))
                        return Scan.skip(
                                template == null ? "the URL is not a constant" : "the URL does not have the version");
                    if (hash == null || !isHash(hash)) return Scan.skip("the hash is not a string");
                    return withHash(
                            new Source(
                                    kind,
                                    null,
                                    null,
                                    null,
                                    literal.replace(lv, VERSION_ONLY),
                                    unpack,
                                    -1,
                                    -1,
                                    Nodes.start(rev),
                                    Nodes.end(rev),
                                    attr,
                                    built),
                            hash,
                            v,
                            lv,
                            null);
                }
            }
            default -> {
                domain = switch (kind) {
                    case GITHUB -> or(constant(args, "githubBase"), "github.com", args, "githubBase");
                    case GITLAB -> or(constant(args, "domain"), "gitlab.com", args, "domain");
                    case GITEA ->
                        or(
                                constant(args, "domain"),
                                fn.equals("fetchFromCodeberg") ? "codeberg.org" : null,
                                args,
                                "domain");
                    case SOURCEHUT -> {
                        String vc = or(constant(args, "vc"), "git", args, "vc");
                        String d = or(constant(args, "domain"), "sr.ht", args, "domain");
                        yield !"git".equals(vc) || d == null ? null : "git." + d;
                    }
                    default -> "bitbucket.org";
                };
                if (domain == null) return Scan.skip("the domain is not a constant, or not a git one");
                owner = constant(args, "owner");
                repo = constant(args, "repo");
                if (kind == Kind.GITLAB && owner != null && binding(args, "group") != null) {
                    String group = constant(args, "group");
                    owner = group == null ? null : group + "/" + owner;
                }
                if (owner == null || repo == null) return Scan.skip("the owner or repository is not a constant");
                rev = arg(args, "tag");
                if (rev == null) rev = arg(args, "rev");
                template = rev == null ? null : resolve(rev, 0);
            }
        }
        if (hash == null || !isHash(hash)) return Scan.skip("the hash is not a string");
        if (built != null && attr == null)
            return Scan.skip(built + ": the source is not an attribute of the package, to build its hash");
        Str versionLit = version;
        if (template == null) return Scan.skip("the tag is not a constant");
        if (template.indexOf(MAJOR_MINOR) >= 0 || template.indexOf(MAJOR) >= 0) {
            if (kind.forge()) return Scan.skip("the tag is not a constant");
        }
        int revStart = -1;
        int revEnd = -1;
        if (kind.forge()) {
            if (template.indexOf(VERSION) != template.lastIndexOf(VERSION))
                return Scan.skip("the tag has the version twice");
            if (template.indexOf(VERSION) < 0) {
                if (COMMIT.matcher(template).matches())
                    return pinned(kind, domain, owner, repo, template, rev, hash, attr, built, drv);
                if (!(rev instanceof Str)) return Scan.skip("the tag is not a string");
                revStart = Nodes.start(rev);
                revEnd = Nodes.end(rev);
                Str literalVersion = drv == null ? null : versionOf(drv);
                String v = literalVersion == null ? null : literal(literalVersion);
                if (v != null && !v.isEmpty() && template.contains(v)) {
                    int at = template.indexOf(v);
                    template = template.substring(0, at) + VERSION + template.substring(at + v.length());
                    versionLit = literalVersion;
                } else {
                    int digit = 0;
                    while (digit < template.length() && !Character.isDigit(template.charAt(digit))) digit++;
                    if (digit == template.length()) return Scan.skip("the tag has no version");
                    Source s = new Source(
                            kind,
                            domain,
                            owner,
                            repo,
                            template.substring(0, digit) + VERSION,
                            true,
                            -1,
                            -1,
                            revStart,
                            revEnd,
                            attr,
                            built);
                    return withHash(s, hash, null, template.substring(digit), null);
                }
            }
        }
        Source s = new Source(kind, domain, owner, repo, template, unpack, -1, -1, revStart, revEnd, attr, built);
        return withHash(s, hash, versionLit, versionLit == null ? null : literal(versionLit), null);
    }

    private Scan pinned(
            Kind kind,
            String domain,
            String owner,
            String repo,
            String commit,
            Node rev,
            Node hash,
            List<String> attr,
            String built,
            AttrSet drv) {
        boolean literal = rev instanceof Str s && commit.equals(literal(s));
        Source s = new Source(
                kind,
                domain,
                owner,
                repo,
                commit,
                true,
                -1,
                -1,
                literal ? Nodes.start(rev) : -1,
                literal ? Nodes.end(rev) : -1,
                attr,
                built);
        Str v = drv == null ? null : versionOf(drv);
        String version = v == null ? null : literal(v);
        String why = !literal
                ? "the commit is not a string"
                : version != null && UNSTABLE.matcher(version).matches() ? null : PINNED;
        Scan scan = withHash(s, hash, v, version, commit);
        return new Scan(scan.source, why, v, version, commit);
    }

    private static Scan withHash(Source s, Node hash, Str versionLit, String version, String commit) {
        return new Scan(
                new Source(
                        s.kind(),
                        s.domain(),
                        s.owner(),
                        s.repo(),
                        s.template(),
                        s.unpack(),
                        Nodes.start(hash),
                        Nodes.end(hash),
                        s.revStart(),
                        s.revEnd(),
                        s.attr(),
                        s.built()),
                null,
                versionLit,
                version,
                commit);
    }

    /** {@code value}, else {@code fallback} when {@code args} does not set {@code name}, else null. */
    private static String or(String value, String fallback, AttrSet args, String name) {
        if (value != null) return value;
        return binding(args, name) == null ? fallback : null;
    }

    private static boolean hasVersion(String template) {
        return template.indexOf(VERSION) >= 0;
    }

    /** The first argument that makes the hash differ from the archive's, or null. */
    private static String built(Kind kind, AttrSet args) {
        Set<String> known = ARGS.get(kind);
        for (Binding b : args.binds()) {
            if (!(b instanceof Assign a) || a.path().size() != 1) continue;
            String k = Nodes.staticName(a.path().getFirst().attr());
            if (k == null || known.contains(k) || kind.forge() && FORGE_ARGS.contains(k)) continue;
            if (k.equals("pname") && kind.forge()) continue;
            if (GIT_OPTIONS.contains(k)
                    && a.value() instanceof Ident i
                    && i.tok().text().equals("false")) continue;
            return k;
        }
        return null;
    }

    /** The attribute path of the derivation {@code c} is the value of, or null. */
    private List<String> attribute(AttrSet drv, Call c) {
        if (drv == null) return null;
        Node at = c;
        while (scope.parents.get(at) instanceof Paren p) at = p;
        if (scope.parents.get(at) != drv) return null;
        List<String> names = assignedTo(drv.binds(), at);
        return names == null ? null : List.copyOf(names);
    }

    private static boolean isHash(Node n) {
        return switch (n) {
            case Str s -> s.parts().stream().allMatch(p -> p instanceof Lit);
            case Select s ->
                s.fallback() == null
                        && FAKE_HASHES.contains(String.valueOf(
                                Nodes.staticName(s.path().getLast().attr())));
            default -> false;
        };
    }

    /** The nearest set around {@code c} that names a package. */
    private AttrSet derivation(Call c) {
        for (Node n = scope.parents.get(c); n != null; n = scope.parents.get(n)) {
            if (n instanceof AttrSet s
                    && (binding(s, "pname") != null || binding(s, "version") != null || binding(s, "name") != null)
                    && !isFetcherArgs(s)) {
                return s;
            }
        }
        return null;
    }

    /** Whether {@code s} is the argument of a fetcher, as fetchPypi's {@code { pname, version }} is. */
    private boolean isFetcherArgs(AttrSet s) {
        return scope.parents.get(s) instanceof Call c
                && c.args().size() == 1
                && c.args().getFirst() == s
                && FETCHERS.containsKey(fnName(c.fn()));
    }

    /** The route to the builder call that takes {@code drv}, or to {@code drv}, from the root of the file. */
    private Route route(AttrSet drv) {
        List<String> path = new ArrayList<>();
        List<Edit> edits = new ArrayList<>();
        Node at = drv;
        for (Node parent = scope.parents.get(at); parent != null; at = parent, parent = scope.parents.get(at)) {
            switch (parent) {
                case Paren p -> {}
                case Lambda l -> {
                    Node up = scope.parents.get(l);
                    while (up instanceof Paren) up = scope.parents.get(up);
                    if (up != null && !(up instanceof Call c && unwrap(c.args().getLast()) == l)) return null;
                }
                case Call c -> {
                    if (unwrap(c.args().getLast()) != unwrap(at)) return null;
                }
                case With w -> {
                    if (w.body() != at) return null;
                }
                case Assert a -> {
                    if (a.body() != at) return null;
                }
                case Let l -> {
                    if (l.body() == at) break;
                    String name = boundTo(l.binds(), at);
                    if (name == null) return null;
                    edits.add(new Edit(Nodes.start(l.body()), Nodes.end(l.body()), name));
                }
                case AttrSet s -> {
                    List<String> names = assignedTo(s.binds(), at);
                    if (names == null) return null;
                    path.addAll(0, names);
                }
                default -> {
                    return null;
                }
            }
        }
        return new Route(List.copyOf(path), List.copyOf(edits));
    }

    private static Node unwrap(Node n) {
        while (n instanceof Paren p) n = p.expr();
        return n;
    }

    /** The name a {@code let} binds to {@code value}, when it is an identifier. */
    private static String boundTo(List<Binding> binds, Node value) {
        List<String> names = assignedTo(binds, value);
        return names != null && names.size() == 1 && names.getFirst().matches("[A-Za-z_][A-Za-z0-9_'-]*")
                ? names.getFirst()
                : null;
    }

    /** The attribute path a set assigns {@code value} to, when its names are constant. */
    private static List<String> assignedTo(List<Binding> binds, Node value) {
        for (Binding b : binds) {
            if (!(b instanceof Assign a) || a.value() != value) continue;
            List<String> names = new ArrayList<>();
            for (Sel sel : a.path()) {
                String name = Nodes.staticName(sel.attr());
                if (name == null) return null;
                names.add(name);
            }
            return names;
        }
        return null;
    }

    private static boolean isSrc(AttrSet drv, Call c) {
        return drv != null && binding(drv, "src") instanceof Assign a && unwrap(a.value()) == c;
    }

    private String name(AttrSet drv) {
        String pname = resolveBinding(drv, "pname", 0);
        if (pname != null && pname.indexOf(VERSION) < 0) return pname;
        String name = resolveBinding(drv, "name", 0);
        if (name == null) return null;
        if (name.endsWith("-" + VERSION)) name = name.substring(0, name.length() - 2);
        return name.indexOf(VERSION) < 0 ? name : null;
    }

    private Str versionOf(AttrSet drv) {
        Str saved = version;
        version = null;
        String v = resolveBinding(drv, "version", 0);
        Str out = VERSION_ONLY.equals(v) ? version : null;
        version = saved;
        return out;
    }

    private static List<Dependency> dependencies(AttrSet drv) {
        List<Dependency> out = new ArrayList<>();
        for (Binding b : drv.binds()) {
            if (!(b instanceof Assign a) || a.path().size() != 1) continue;
            String k = Nodes.staticName(a.path().getFirst().attr());
            if (k == null) continue;
            String attr = DEPENDENCY_HASHES.get(k);
            if (attr != null) {
                if (!(a.value() instanceof Ident i && i.tok().text().equals("null")))
                    out.add(dependency(k, attr, a.value()));
            } else if (a.value() instanceof Call c
                    && c.args().size() == 1
                    && c.args().getFirst() instanceof AttrSet args
                    && DEPENDENCY_FETCHER.matcher(fnName(c.fn())).matches()) {
                Node hash = arg(args, "hash");
                if (hash == null) hash = arg(args, "sha256");
                if (hash != null) out.add(dependency(k, k, hash));
            }
        }
        return out;
    }

    private static Dependency dependency(String name, String attr, Node hash) {
        return isHash(hash)
                ? new Dependency(name, attr, Nodes.start(hash), Nodes.end(hash))
                : new Dependency(name, attr, -1, -1);
    }

    /** The lock files of {@code drv} that are paths next to it. */
    private List<Lock> locks(AttrSet drv) {
        List<Lock> out = new ArrayList<>();
        boolean outputHashes = false;
        for (Binding b : drv.binds()) {
            if (b instanceof Assign a && startsWith(names(a), "cargoLock", "outputHashes")) outputHashes = true;
        }
        for (Binding b : drv.binds()) {
            if (!(b instanceof Assign a)) continue;
            List<String> path = names(a);
            if (path.equals(List.of("cargoLock", "lockFile"))) {
                String file = path(a.value());
                if (file != null) out.add(new Lock(LockType.CARGO, file, outputHashes));
            } else if (path.equals(List.of("cargoLock")) && a.value() instanceof AttrSet lock) {
                String file = path(arg(lock, "lockFile"));
                boolean hashes = lock.binds().stream()
                        .anyMatch(l -> l instanceof Assign h && startsWith(names(h), "outputHashes"));
                if (file != null) out.add(new Lock(LockType.CARGO, file, hashes));
            } else if (path.equals(List.of("nugetDeps"))) {
                String file = path(a.value());
                if (file != null) out.add(new Lock(LockType.NUGET, file, false));
            } else if (path.equals(List.of("mitmCache"))
                    && a.value() instanceof Call c
                    && fnName(c.fn()).equals("fetchDeps")
                    && c.args().size() == 1
                    && c.args().getFirst() instanceof AttrSet args) {
                String file = path(arg(args, "data"));
                if (file != null) out.add(new Lock(LockType.GRADLE, file, false));
            }
        }
        return out;
    }

    private static boolean startsWith(List<String> names, String... prefix) {
        return names.size() >= prefix.length && names.subList(0, prefix.length).equals(List.of(prefix));
    }

    private static List<String> names(Assign a) {
        List<String> path = new ArrayList<>();
        for (Sel sel : a.path()) path.add(Nodes.staticName(sel.attr()));
        return path;
    }

    /** The text of a path literal without interpolations, else null. */
    private String path(Node n) {
        if (!(n instanceof Syntax.Path p) || p.rest().stream().anyMatch(x -> x instanceof Interp)) return null;
        return src.substring(Nodes.start(p), Nodes.end(p));
    }

    /** Whether {@code drv} has an update script of its own. */
    private static boolean updateScript(AttrSet drv) {
        for (Binding b : drv.binds()) {
            if (!(b instanceof Assign a)) continue;
            List<String> path = names(a);
            Node script = null;
            if (path.equals(List.of("passthru", "updateScript"))) script = a.value();
            else if (path.equals(List.of("passthru")) && a.value() instanceof AttrSet passthru)
                script = arg(passthru, "updateScript");
            if (script != null) return !GENERIC_UPDATERS.contains(updater(unwrap(script)));
        }
        return false;
    }

    /** The name of the function an update script is made by, or the script's name. */
    private static String updater(Node script) {
        Node fn = script;
        while (fn instanceof Call c) fn = unwrap(c.fn());
        return fnName(fn);
    }

    private static String fnName(Node fn) {
        String name = switch (fn) {
            case Ident i -> i.tok().text();
            case Select s ->
                s.fallback() == null ? Nodes.staticName(s.path().getLast().attr()) : null;
            default -> null;
        };
        return name == null ? "" : name;
    }

    private static Node arg(AttrSet args, String name) {
        return binding(args, name) instanceof Assign a ? a.value() : null;
    }

    /** The binding of {@code name} in {@code set}: an {@link Assign}, or the {@link AttrNode} of an inherit. */
    private static Object binding(AttrSet set, String name) {
        for (Binding b : set.binds()) {
            switch (b) {
                case Assign a -> {
                    if (a.path().size() == 1
                            && name.equals(Nodes.staticName(a.path().getFirst().attr()))) return a;
                }
                case Inherit i -> {
                    for (AttrNode n : i.names()) if (name.equals(Nodes.staticName(n))) return i.from() == null ? n : i;
                }
            }
        }
        return null;
    }

    private String constant(AttrSet set, String name) {
        Str saved = version;
        String s = resolveBinding(set, name, 0);
        version = saved;
        return s == null || s.indexOf(VERSION) >= 0 || s.indexOf(MAJOR_MINOR) >= 0 || s.indexOf(MAJOR) >= 0 ? null : s;
    }

    private String resolveBinding(AttrSet set, String name, int depth) {
        Object b = binding(set, name);
        if (b instanceof Inherit i && unwrap(i.from().expr()) instanceof Ident from) return field(from, name, depth);
        return site(b, depth);
    }

    /** The string {@code name} is in the set the argument {@code arg} stands for, as {@code finalAttrs} does. */
    private String field(Ident arg, String name, int depth) {
        Scope.Def d = scope.resolved.get(arg);
        if (d == null || depth > 16 || d.kind != Scope.Kind.ARG || !(d.owner instanceof Lambda l)) return null;
        Node body = l.body();
        if (body instanceof Let let) body = let.body();
        return body instanceof AttrSet set ? resolveBinding(set, name, depth + 1) : null;
    }

    /**
     * The string {@code n} stands for, with {@link #VERSION} where a literal {@code version} binding is used, and
     * {@link #MAJOR_MINOR} and {@link #MAJOR} where {@code lib.versions.majorMinor} and {@code lib.versions.major}
     * take it.
     */
    private String resolve(Node n, int depth) {
        if (depth > 16) return null;
        return switch (n) {
            case Str s -> {
                StringBuilder sb = new StringBuilder();
                for (Part p : s.parts()) {
                    switch (p) {
                        case Lit l -> sb.append(l.text());
                        case Interp i -> {
                            String v = resolve(i.expr(), depth + 1);
                            if (v == null) yield null;
                            sb.append(v);
                        }
                    }
                }
                yield sb.toString();
            }
            case Paren p -> resolve(p.expr(), depth + 1);
            case Ident i -> def(scope.resolved.get(i), depth + 1);
            case Call c
            when c.args().size() == 1 -> {
                String fn = fnName(c.fn());
                if (!fn.equals("majorMinor") && !fn.equals("major")) yield null;
                if (!VERSION_ONLY.equals(resolve(c.args().getFirst(), depth + 1))) yield null;
                yield String.valueOf(fn.equals("major") ? MAJOR : MAJOR_MINOR);
            }
            case Select s
            when s.fallback() == null && s.path().size() == 1 && s.target() instanceof Ident i -> {
                String k = Nodes.staticName(s.path().getFirst().attr());
                yield k == null ? null : field(i, k, depth + 1);
            }
            default -> null;
        };
    }

    private String def(Scope.Def d, int depth) {
        if (d == null || d.sites.size() != 1) return null;
        return site(d.sites.getFirst(), depth);
    }

    private String site(Object site, int depth) {
        return switch (site) {
            case Assign a -> {
                if ("version".equals(Nodes.staticName(a.path().getFirst().attr()))
                        && a.path().size() == 1
                        && a.value() instanceof Str s
                        && literal(s) != null) {
                    if (version == null) version = s;
                    yield version == s ? VERSION_ONLY : literal(s);
                }
                yield resolve(a.value(), depth + 1);
            }
            case AttrNode n -> def(scope.inherited.get(n), depth + 1);
            case null, default -> null;
        };
    }

    /** The value of a string without interpolations, else null. */
    private static String literal(Str s) {
        if (s.open().type() != TokenType.STR_OPEN) return null;
        StringBuilder sb = new StringBuilder();
        for (Part p : s.parts()) {
            if (!(p instanceof Lit l)) return null;
            sb.append(l.text());
        }
        return sb.toString();
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("${", "\\${") + "\"";
    }
}
