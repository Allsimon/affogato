package org.affogato.update;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.affogato.Interpreter;
import org.affogato.lint.Packages.Kind;
import org.affogato.lint.Packages.Source;

/**
 * The newest version of a source: the latest release of its repository, else its newest tag, of the shape of the
 * current one; the newest version on its registry; or the newest file of the directory listing of its URL.
 */
final class Releases {
    private static final Pattern UNSTABLE =
            Pattern.compile("(?i).*(alpha|beta|rc|pre|dev|nightly|snapshot|canary|preview|\\d[ab]\\d).*");

    /** The commit at the head of a branch, and its date as {@code YYYY-MM-DD}. */
    record Head(String commit, String date) {}

    private static final Map<String, String> MIRRORS = Map.ofEntries(
            Map.entry("gnu", "https://ftp.gnu.org/gnu/"),
            Map.entry("savannah", "https://download.savannah.gnu.org/releases/"),
            Map.entry("gnome", "https://download.gnome.org/"),
            Map.entry("kernel", "https://cdn.kernel.org/pub/"),
            Map.entry("xorg", "https://xorg.freedesktop.org/releases/"),
            Map.entry("apache", "https://archive.apache.org/dist/"),
            Map.entry("cpan", "https://cpan.metacpan.org/"),
            Map.entry("mozilla", "https://download.cdn.mozilla.net/pub/"),
            Map.entry("pypi", "https://files.pythonhosted.org/packages/source/"),
            Map.entry("hackage", "https://hackage.haskell.org/package/"),
            Map.entry("sourceforge", "https://downloads.sourceforge.net/"),
            Map.entry("gentoo", "https://distfiles.gentoo.org/"),
            Map.entry("debian", "https://deb.debian.org/debian/"),
            Map.entry("ubuntu", "https://archive.ubuntu.com/ubuntu/"),
            Map.entry("samba", "https://download.samba.org/pub/"),
            Map.entry("kde", "https://download.kde.org/"),
            Map.entry("qt", "https://download.qt.io/"));

    private static final String V = "\\x00";
    private static final Pattern NPM = Pattern.compile("https://registry\\.npmjs\\.org/((?:@[^/]+/)?[^/@]+)/-/.*");
    private static final Pattern RUBYGEMS =
            Pattern.compile("https://rubygems\\.org/(?:downloads|gems)/(.+)-" + V + "\\.gem");
    private static final Pattern HACKAGE =
            Pattern.compile("https://hackage\\.haskell\\.org/package/(.+?)-" + V + "(?:/.*|\\.tar\\.gz)");
    private static final Pattern PYPI =
            Pattern.compile("https://files\\.pythonhosted\\.org/packages/source/./([^/]+)/.*");
    private static final Pattern CRATES =
            Pattern.compile("https://(?:crates\\.io/api/v1/crates|static\\.crates\\.io/crates)/([^/]+)/.*");
    private static final Pattern GITHUB = Pattern.compile("https://github\\.com/([^/]+)/([^/]+)/(?:releases/download/"
            + "([^/]*" + V + "[^/]*)/.*|archive/(?:refs/tags/)?([^/]*" + V + "[^/]*?)\\.(?:tar\\.gz|tar\\.bz2|zip))");
    private static final Pattern HREF = Pattern.compile("(?i)href\\s*=\\s*[\"']?([^\"' >]+)");

    private final Http http;

    Releases(Http http) {
        this.http = http;
    }

    /** {@code url} with its {@code mirror://} replaced with one of the mirror's URLs. */
    static String expand(String url) {
        if (!url.startsWith("mirror://")) return url;
        int slash = url.indexOf('/', "mirror://".length());
        if (slash < 0) return url;
        String mirror = MIRRORS.get(url.substring("mirror://".length(), slash));
        return mirror == null ? url : mirror + url.substring(slash + 1);
    }

    /** What the fetcher of {@code s} downloads at {@code version}, or at {@code commit} when not null. */
    static String archive(Source s, String version, String commit) {
        String o = s.owner();
        String r = s.repo();
        String rev = s.rev(version, commit);
        return switch (s.kind()) {
            case GITHUB, GITEA, SOURCEHUT ->
                "https://" + s.domain() + "/" + o + "/" + r + "/archive/" + rev + ".tar.gz";
            case GITLAB ->
                "https://" + s.domain() + "/api/v4/projects/" + encode(o + "/" + r) + "/repository/archive.tar.gz?sha="
                        + encode(rev);
            case BITBUCKET -> "https://bitbucket.org/" + o + "/" + r + "/get/" + rev + ".tar.gz";
            case PYPI, CRATES, URL -> expand(rev);
        };
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** The newest version of {@code s} past {@code current}, or null when {@code current} is the newest. */
    String newer(Source s, String current) throws IOException, InterruptedException {
        boolean unstable = UNSTABLE.matcher(current).matches();
        String best = switch (s.kind()) {
            case GITHUB, GITLAB, GITEA, SOURCEHUT, BITBUCKET -> forge(s, unstable);
            case PYPI -> pypi(s.owner(), unstable);
            case CRATES -> crate(s.owner(), unstable);
            case URL -> url(s, current, unstable);
        };
        return best != null && Interpreter.compareVersions(best, current) > 0 ? best : null;
    }

    private String forge(Source s, boolean unstable) throws IOException, InterruptedException {
        String latest = latestRelease(s);
        String best = latest == null ? null : version(s, latest, unstable);
        if (best == null) best = newest(s, tags(s), unstable);
        return best;
    }

    private String forgeName(Source s) {
        return switch (s.kind()) {
            case GITHUB -> "GitHub";
            case GITLAB -> "GitLab";
            case GITEA -> s.domain().equals("codeberg.org") ? "Codeberg" : "Gitea";
            case SOURCEHUT -> "SourceHut";
            case BITBUCKET -> "Bitbucket";
            default -> s.domain();
        };
    }

    private String gitlab(Source s) {
        return http.base(s.domain()) + "/api/v4/projects/" + encode(s.owner() + "/" + s.repo());
    }

    private String gitea(Source s) {
        return http.base(s.domain()) + "/api/v1/repos/" + s.owner() + "/" + s.repo();
    }

    private String bitbucket(Source s) {
        return http.base("api.bitbucket.org") + "/2.0/repositories/" + s.owner() + "/" + s.repo();
    }

    private String github(Source s) {
        return http.githubApi(s.domain()) + "/repos/" + s.owner() + "/" + s.repo();
    }

    private String sourcehut(Source s) {
        return http.base(s.domain()) + "/" + s.owner() + "/" + s.repo();
    }

    /** The tag of the latest release, or null. */
    private String latestRelease(Source s) throws IOException, InterruptedException {
        String who = forgeName(s);
        Object release = switch (s.kind()) {
            case GITHUB -> http.json(who, github(s) + "/releases/latest");
            case GITLAB -> http.json(who, gitlab(s) + "/releases/permalink/latest");
            case GITEA -> http.json(who, gitea(s) + "/releases/latest");
            default -> null;
        };
        return release instanceof Map<?, ?> m && m.get("tag_name") instanceof String tag ? tag : null;
    }

    private List<String> tags(Source s) throws IOException, InterruptedException {
        String who = forgeName(s);
        List<String> out = new ArrayList<>();
        switch (s.kind()) {
            case GITHUB -> names(http.json(who, github(s) + "/tags?per_page=100"), out);
            case GITLAB -> names(http.json(who, gitlab(s) + "/repository/tags?per_page=100"), out);
            case GITEA -> names(http.json(who, gitea(s) + "/tags?limit=50"), out);
            case BITBUCKET -> {
                if (http.json(who, bitbucket(s) + "/refs/tags?pagelen=100&sort=-target.date") instanceof Map<?, ?> m)
                    names(m.get("values"), out);
            }
            case SOURCEHUT -> {
                String rss = http.text(who, sourcehut(s) + "/refs/rss.xml");
                if (rss != null) for (Map<String, String> item : items(rss)) out.add(item.get("title"));
            }
            default -> {}
        }
        return out;
    }

    private static void names(Object list, List<String> out) {
        if (!(list instanceof List<?> l)) return;
        for (Object t : l) if (t instanceof Map<?, ?> m && m.get("name") instanceof String name) out.add(name);
    }

    private static String newest(Source s, Collection<String> tags, boolean unstable) {
        String best = null;
        for (String tag : tags) {
            String v = tag == null ? null : version(s, tag, unstable);
            if (v != null && (best == null || Interpreter.compareVersions(v, best) > 0)) best = v;
        }
        return best;
    }

    /** The version {@code tag} is when it has the shape of the source's tag, else null. */
    private static String version(Source s, String tag, boolean unstable) {
        String prefix = s.tagPrefix();
        if (!tag.startsWith(prefix)
                || !tag.endsWith(s.suffix())
                || tag.length() <= prefix.length() + s.suffix().length()) return null;
        String v = tag.substring(prefix.length(), tag.length() - s.suffix().length());
        return stable(v, unstable) ? v : null;
    }

    private static boolean stable(String v, boolean unstable) {
        return !v.isEmpty()
                && Character.isDigit(v.charAt(0))
                && (unstable || !UNSTABLE.matcher(v).matches());
    }

    /** The head of the default branch of the repository of {@code s}. */
    Head head(Source s) throws IOException, InterruptedException {
        String who = forgeName(s);
        switch (s.kind()) {
            case GITHUB -> {
                String branch = field(http.json(who, github(s)), "default_branch");
                Object c = http.json(who, github(s) + "/commits/" + encode(branch));
                return head(field(c, "sha"), field(c, "commit", "committer", "date"), who);
            }
            case GITLAB -> {
                String branch = field(http.json(who, gitlab(s)), "default_branch");
                Object c = http.json(who, gitlab(s) + "/repository/commits/" + encode(branch));
                return head(field(c, "id"), field(c, "committed_date"), who);
            }
            case GITEA -> {
                String branch = field(http.json(who, gitea(s)), "default_branch");
                Object b = http.json(who, gitea(s) + "/branches/" + encode(branch));
                return head(field(b, "commit", "id"), field(b, "commit", "timestamp"), who);
            }
            case BITBUCKET -> {
                String branch = field(http.json(who, bitbucket(s)), "mainbranch", "name");
                Object c = http.json(who, bitbucket(s) + "/commit/" + encode(branch));
                return head(field(c, "hash"), field(c, "date"), who);
            }
            case SOURCEHUT -> {
                String rss = http.text(who, sourcehut(s) + "/log/rss.xml");
                List<Map<String, String>> items = rss == null ? List.of() : items(rss);
                if (items.isEmpty()) throw new IOException(who + " has no commit of " + s.owner() + "/" + s.repo());
                String link = items.getFirst().getOrDefault("link", "");
                return head(
                        link.substring(link.lastIndexOf('/') + 1),
                        items.getFirst().get("pubDate"),
                        who);
            }
            default -> throw new IOException("a " + s.kind() + " source has no branch");
        }
    }

    private static Head head(String commit, String date, String who) throws IOException {
        if (commit == null || !commit.matches("[0-9a-f]{40}") || date == null)
            throw new IOException(who + " did not tell the head of the default branch");
        return new Head(commit, day(date));
    }

    /** The UTC day of an ISO 8601 or RFC 1123 date. */
    private static String day(String date) throws IOException {
        try {
            return ZonedDateTime.parse(date, DateTimeFormatter.ISO_DATE_TIME)
                    .withZoneSameInstant(ZoneOffset.UTC)
                    .toLocalDate()
                    .toString();
        } catch (DateTimeParseException e) {
            try {
                return ZonedDateTime.parse(date.strip(), DateTimeFormatter.RFC_1123_DATE_TIME)
                        .withZoneSameInstant(ZoneOffset.UTC)
                        .toLocalDate()
                        .toString();
            } catch (DateTimeParseException f) {
                throw new IOException("not a date: " + date, f);
            }
        }
    }

    private static String field(Object json, String... path) {
        Object at = json;
        for (String k : path) at = at instanceof Map<?, ?> m ? m.get(k) : null;
        return at instanceof String s ? s : null;
    }

    /** The fields of each {@code <item>} of an RSS feed. */
    private static List<Map<String, String>> items(String rss) {
        List<Map<String, String>> out = new ArrayList<>();
        Matcher item = Pattern.compile("(?s)<item>(.*?)</item>").matcher(rss);
        Pattern field = Pattern.compile("(?s)<(title|link|pubDate)>(.*?)</\\1>");
        while (item.find()) {
            Map<String, String> fields = new HashMap<>();
            Matcher f = field.matcher(item.group(1));
            while (f.find()) fields.put(f.group(1), unescape(f.group(2).strip()));
            out.add(fields);
        }
        return out;
    }

    private static String unescape(String s) {
        if (s.startsWith("<![CDATA[") && s.endsWith("]]>")) return s.substring(9, s.length() - 3);
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
    }

    private String pypi(String name, boolean unstable) throws IOException, InterruptedException {
        Object json = http.json("PyPI", http.base("pypi.org") + "/pypi/" + name + "/json");
        if (!unstable) return field(json, "info", "version");
        if (!(json instanceof Map<?, ?> m && m.get("releases") instanceof Map<?, ?> releases)) return null;
        String best = null;
        for (Map.Entry<?, ?> e : releases.entrySet()) {
            if (e.getKey() instanceof String v
                    && e.getValue() instanceof List<?> files
                    && !files.isEmpty()
                    && stable(v, true)
                    && (best == null || Interpreter.compareVersions(v, best) > 0)) best = v;
        }
        return best;
    }

    private String crate(String name, boolean unstable) throws IOException, InterruptedException {
        Object json = http.json("crates.io", http.base("crates.io") + "/api/v1/crates/" + name);
        String stable = field(json, "crate", "max_stable_version");
        return unstable || stable == null ? field(json, "crate", "max_version") : stable;
    }

    private String url(Source s, String current, boolean unstable) throws IOException, InterruptedException {
        String url = expand(s.template());
        Matcher m;
        if ((m = NPM.matcher(url)).matches())
            return field(http.json("npm", http.base("registry.npmjs.org") + "/" + m.group(1) + "/latest"), "version");
        if ((m = RUBYGEMS.matcher(url)).matches())
            return field(
                    http.json(
                            "RubyGems", http.base("rubygems.org") + "/api/v1/versions/" + m.group(1) + "/latest.json"),
                    "version");
        if ((m = HACKAGE.matcher(url)).matches()) return hackage(m.group(1), unstable);
        if ((m = PYPI.matcher(url)).matches()) return pypi(m.group(1), unstable);
        if ((m = CRATES.matcher(url)).matches()) return crate(m.group(1), unstable);
        if ((m = GITHUB.matcher(url)).matches()) {
            String tag = m.group(3) != null ? m.group(3) : m.group(4);
            Source repo = new Source(
                    Kind.GITHUB, "github.com", m.group(1), m.group(2), tag, true, -1, -1, -1, -1, null, null);
            return forge(repo, unstable);
        }
        return listing(http.resolve(url), current, unstable);
    }

    private String hackage(String name, boolean unstable) throws IOException, InterruptedException {
        Object json = http.json("Hackage", http.base("hackage.haskell.org") + "/package/" + name + "/preferred");
        String best = null;
        if (json instanceof Map<?, ?> m && m.get("normal-version") instanceof List<?> versions) {
            for (Object o : versions)
                if (o instanceof String v
                        && stable(v, unstable)
                        && (best == null || Interpreter.compareVersions(v, best) > 0)) best = v;
        }
        return best;
    }

    /**
     * The newest version in the directory listings of {@code template}: of the directory before its first part with
     * the version, then of the newest directories that match that part, down to the file.
     */
    private String listing(String template, String current, boolean unstable) throws IOException, InterruptedException {
        int marker = firstMarker(template);
        if (marker < 0 || !template.startsWith("http")) return null;
        int slash = template.lastIndexOf('/', marker);
        String dir = template.substring(0, slash + 1);
        List<String> parts = List.of(template.substring(slash + 1).split("/", -1));
        return search(dir, parts, Map.of(), current, unstable, 0);
    }

    private static int firstMarker(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) <= '\2') return i;
        return -1;
    }

    private String search(
            String dir, List<String> parts, Map<Character, String> known, String current, boolean unstable, int depth)
            throws IOException, InterruptedException {
        if (depth > 4) return null;
        String html = http.text(host(dir), dir);
        if (html == null) return null;
        Pattern part = pattern(parts.getFirst(), known);
        boolean last = parts.size() == 1;
        List<Map<Character, String>> found = new ArrayList<>();
        for (String entry : entries(html)) {
            Matcher m = part.matcher(entry);
            if (!m.matches()) continue;
            Map<Character, String> values = new HashMap<>(known);
            boolean consistent = true;
            for (char c = '\0'; c <= '\2'; c++) {
                String group = "m" + (int) c;
                if (!part.pattern().contains("?<" + group + ">")) continue;
                String v = m.group(group);
                if (values.containsKey(c) && !values.get(c).equals(v)) consistent = false;
                values.put(c, v);
            }
            if (consistent && fits(values)) found.add(values);
        }
        found.sort((a, b) -> Interpreter.compareVersions(order(b), order(a)));
        if (last) {
            for (Map<Character, String> values : found) {
                String v = values.get('\0');
                if (v != null && stable(v, unstable)) return v;
            }
            return null;
        }
        int tried = 0;
        for (Map<Character, String> values : found) {
            if (tried++ == 3) break;
            String sub = parts.getFirst();
            for (Map.Entry<Character, String> e : values.entrySet())
                sub = sub.replace(String.valueOf(e.getKey()), e.getValue());
            String v = search(dir + sub + "/", parts.subList(1, parts.size()), values, current, unstable, depth + 1);
            if (v != null) return v;
        }
        return null;
    }

    private static String host(String url) {
        int start = url.indexOf("://") + 3;
        int end = url.indexOf('/', start);
        return url.substring(start, end < 0 ? url.length() : end);
    }

    /** Whether the version found agrees with its first components found in directories. */
    private static boolean fits(Map<Character, String> values) {
        String v = values.get('\0');
        if (v == null) return true;
        String mm = values.get('\1');
        String major = values.get('\2');
        return (mm == null || v.equals(mm) || v.startsWith(mm + "."))
                && (major == null || v.equals(major) || v.startsWith(major + "."));
    }

    private static String order(Map<Character, String> values) {
        for (char c = '\0'; c <= '\2'; c++) if (values.containsKey(c)) return values.get(c);
        return "";
    }

    /** {@code part} as a pattern, with a group for each marker it does not know yet. */
    private static Pattern pattern(String part, Map<Character, String> known) {
        StringBuilder sb = new StringBuilder();
        Set<Character> named = new LinkedHashSet<>();
        int at = 0;
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c > '\2') continue;
            sb.append(Pattern.quote(part.substring(at, i)));
            if (known.containsKey(c)) sb.append(Pattern.quote(known.get(c)));
            else if (named.add(c))
                sb.append("(?<m")
                        .append((int) c)
                        .append('>')
                        .append(c == '\0' ? "\\d[\\w.+~-]*?" : c == '\1' ? "\\d+\\.\\d+" : "\\d+")
                        .append(')');
            else sb.append("\\k<m").append((int) c).append('>');
            at = i + 1;
        }
        sb.append(Pattern.quote(part.substring(at)));
        return Pattern.compile(sb.toString());
    }

    /** The names a directory listing links to. */
    private static List<String> entries(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            int q = href.indexOf('?');
            if (q >= 0) href = href.substring(0, q);
            if (href.endsWith("/")) href = href.substring(0, href.length() - 1);
            href = href.substring(href.lastIndexOf('/') + 1);
            try {
                out.add(URLDecoder.decode(href, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                out.add(href);
            }
        }
        return out;
    }
}
