package org.affogato.web;

import java.util.Map;
import org.affogato.lsp.Json;
import org.affogato.lsp.Server;
import org.teavm.jso.JSExport;

/** The language server for JavaScript, one message at a time; offsets count UTF-16 code units, as both sides do. */
public final class LanguageServer {
    private static final Server SERVER = new Server();

    private LanguageServer() {}

    /** The JSON array of the messages answering the JSON {@code message}. */
    @JSExport
    public static String handle(String message) {
        StringBuilder sb = new StringBuilder("[");
        for (Map<String, Object> m : SERVER.handle(Json.parse(message))) {
            if (sb.length() > 1) sb.append(',');
            sb.append(Json.write(m));
        }
        return sb.append(']').toString();
    }
}
