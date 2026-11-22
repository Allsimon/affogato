package org.affogato.update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.affogato.lsp.Json;

/** GETs what forges and registries say of their packages. */
final class Http {
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final String githubApi;
    private final String githubToken;
    /** The base URL that stands for each host, so that tests can answer in their place. */
    private final Map<String, String> hosts;

    Http(String githubApi, String githubToken, Map<String, String> hosts) {
        this.githubApi = strip(githubApi);
        this.githubToken = githubToken;
        this.hosts = hosts;
    }

    private static String strip(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** GitHub's API on github.com, else that of GitHub Enterprise. */
    String githubApi(String domain) {
        return domain.equals("github.com") ? githubApi : base(domain) + "/api/v3";
    }

    /** {@code https://host}, or what stands for it. */
    String base(String host) {
        String base = hosts.get(host);
        return base != null ? strip(base) : "https://" + host;
    }

    /** {@code url} with what stands for its host. */
    String resolve(String url) {
        if (!url.startsWith("https://")) return url;
        int slash = url.indexOf('/', "https://".length());
        String host = url.substring("https://".length(), slash < 0 ? url.length() : slash);
        return hosts.containsKey(host) ? base(host) + (slash < 0 ? "" : url.substring(slash)) : url;
    }

    /** The JSON {@code who} answers at {@code url}, or null when there is none. */
    Object json(String who, String url) throws IOException, InterruptedException {
        String body = get(who, url, "application/json");
        if (body == null) return null;
        try {
            return Json.parse(body);
        } catch (IllegalArgumentException e) {
            throw new IOException(who + " sent invalid JSON for " + path(url), e);
        }
    }

    /** The text {@code who} answers at {@code url}, or null when there is none. */
    String text(String who, String url) throws IOException, InterruptedException {
        return get(who, url, "*/*");
    }

    private String get(String who, String url, String accept) throws IOException, InterruptedException {
        boolean github = url.startsWith(githubApi + "/");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", github ? "application/vnd.github+json" : accept)
                .header("User-Agent", "affogato");
        if (github && githubToken != null && !githubToken.isEmpty())
            request.header("Authorization", "Bearer " + githubToken);
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            URI uri = URI.create(url);
            String origin = github ? githubApi : uri.getScheme() + "://" + uri.getAuthority();
            throw new IOException(
                    "cannot reach " + origin + ": "
                            + (e.getMessage() != null
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName()),
                    e);
        }
        int status = response.statusCode();
        if (status == 404 || status == 410) return null;
        if (status == 403 || status == 429) {
            boolean limited = response.headers()
                    .firstValue("x-ratelimit-remaining")
                    .map("0"::equals)
                    .orElse(status == 429);
            throw new IOException(
                    limited
                            ? who + "'s rate limit is reached"
                                    + (github && githubToken == null ? "; set GITHUB_TOKEN" : "")
                            : who + " refused " + path(url) + " (" + status + ")");
        }
        if (status != 200) throw new IOException(who + " answered " + status + " for " + path(url));
        return response.body();
    }

    private static String path(String url) {
        URI uri = URI.create(url);
        return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    }
}
