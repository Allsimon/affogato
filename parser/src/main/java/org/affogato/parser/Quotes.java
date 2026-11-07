package org.affogato.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.affogato.parser.Ast.Expr;
import org.affogato.parser.Ast.Interp;
import org.affogato.parser.Ast.StrLit;

/** Rewrites a string literal in the other quoting, {@code "..."} or {@code ''...''}, keeping its value. */
public final class Quotes {
    /** An interpolation, as written between its braces. */
    private record Code(String source) {}

    private Quotes() {}

    /**
     * The other quoting of {@code literal}, or null when {@code literal} is not a single string or no other quoting
     * gives the same value, as for {@code "  x"}, whose indentation {@code ''...''} would strip.
     */
    public static String toggle(String literal) {
        List<Object> parts = parts(literal);
        if (parts == null) return null;
        String other = literal.startsWith("''") ? doubleQuoted(parts) : indented(parts);
        return Objects.equals(parts(other), parts) ? other : null;
    }

    /** The literal text and the interpolations of a string, adjacent texts joined and empty ones dropped. */
    private static List<Object> parts(String src) {
        Syntax.Node syntax;
        Expr expr;
        try {
            syntax = Parser.parseSyntax(src);
            expr = Parser.parse(src);
        } catch (ParseException e) {
            return null;
        }
        if (!(syntax instanceof Syntax.Str str)) return null;
        List<Syntax.Interp> interps = new ArrayList<>();
        for (Syntax.Part p : str.parts()) if (p instanceof Syntax.Interp i) interps.add(i);
        List<Expr> exprs = switch (expr) {
            case StrLit s -> List.of(s);
            case Interp i when !i.isPath() -> i.parts();
            default -> null;
        };
        if (exprs == null) return null;
        List<Object> out = new ArrayList<>();
        for (Expr e : exprs) {
            Syntax.Interp interp = interps.stream()
                    .filter(i -> i.open().start() <= e.pos().start()
                            && e.pos().start() < i.close().end())
                    .findFirst()
                    .orElse(null);
            if (interp != null) {
                out.add(new Code(
                        src.substring(interp.open().end(), interp.close().start())));
            } else if (e instanceof StrLit s && !s.value().isEmpty()) {
                if (!out.isEmpty() && out.getLast() instanceof String last) out.set(out.size() - 1, last + s.value());
                else out.add(s.value());
            }
        }
        return out;
    }

    private static String doubleQuoted(List<Object> parts) {
        StringBuilder out = new StringBuilder("\"");
        for (int p = 0; p < parts.size(); p++) {
            if (parts.get(p) instanceof Code code) {
                out.append("${").append(code.source()).append('}');
                continue;
            }
            String text = (String) parts.get(p);
            boolean beforeCode = p + 1 < parts.size();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                boolean last = i == text.length() - 1;
                if (c == '$' && !last && text.charAt(i + 1) == '$') {
                    out.append("$$");
                    i++;
                    continue;
                }
                switch (c) {
                    case '\\' -> out.append("\\\\");
                    case '"' -> out.append("\\\"");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    case '$' -> out.append((last ? beforeCode : text.charAt(i + 1) == '{') ? "\\$" : "$");
                    default -> out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    private static String indented(List<Object> parts) {
        StringBuilder out = new StringBuilder("''");
        for (int p = 0; p < parts.size(); p++) {
            if (parts.get(p) instanceof Code code) {
                out.append("${").append(code.source()).append('}');
                continue;
            }
            String text = (String) parts.get(p);
            boolean beforeCode = p + 1 < parts.size();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                boolean last = i == text.length() - 1;
                if (c == '$' && !last && text.charAt(i + 1) == '$') {
                    out.append("$$");
                    i++;
                } else if (p == 0 && c == '\n' && text.substring(0, i).isBlank()) out.append("''\\n");
                else if (c == '\'' && !last && text.charAt(i + 1) == '\'') {
                    out.append("'''");
                    i++;
                } else if (c == '\'' && last && !beforeCode) out.append("''\\'");
                else if (c == '$' && (last ? beforeCode : text.charAt(i + 1) == '{')) out.append("''$");
                else if (c == '\r') out.append("''\\r");
                else out.append(c);
            }
        }
        return out.append("''").toString();
    }
}
