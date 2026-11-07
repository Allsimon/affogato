package org.affogato.parser;

/** The nodes {@link TreeParser} builds; the comments show their children, tokens quoted. */
public enum NodeKind {
    /** ID or OR_KW */
    VAR,
    INT,
    FLOAT,
    URI,
    SEARCH_PATH,
    /** PATH or HPATH, then PATH_TEXT and INTERPOLATION */
    PATH,
    /** '"', STR_TEXT, STR_ESCAPE and INTERPOLATION, '"' */
    STRING,
    /** "''", STR_TEXT, STR_ESCAPE and INTERPOLATION, "''" */
    IND_STRING,
    /** '${' expr '}' */
    INTERPOLATION,
    /** '(' expr ')' */
    PAREN,
    /** ['rec' | 'let'] '{' (BINDING | INHERIT)* '}' */
    ATTR_SET,
    /** '[' expr* ']' */
    LIST,
    /** expr '.' ATTR_PATH ['or' expr] */
    SELECT,
    /** expr '?' ATTR_PATH */
    HAS_ATTR,
    /** expr expr+ */
    APPLY,
    /** (PARAM | FORMALS | PARAM '@' FORMALS | FORMALS '@' PARAM) ':' expr */
    LAMBDA,
    /** ID */
    PARAM,
    /** '{' (FORMAL ','?)* ['...'] '}' */
    FORMALS,
    /** ID ['?' expr] */
    FORMAL,
    /** 'let' (BINDING | INHERIT)* 'in' expr */
    LET,
    /** 'with' expr ';' expr */
    WITH,
    /** 'assert' expr ';' expr */
    ASSERT,
    /** 'if' expr 'then' expr 'else' expr */
    IF,
    /** expr operator expr */
    BINARY,
    /** '-' expr */
    NEGATE,
    /** '!' expr */
    NOT,
    /** (ATTR_NAME | STRING | DYNAMIC_ATTR) ('.' ...)* */
    ATTR_PATH,
    /** ID or OR_KW */
    ATTR_NAME,
    /** '${' expr '}' */
    DYNAMIC_ATTR,
    /** ATTR_PATH '=' expr ';' */
    BINDING,
    /** 'inherit' [PAREN] (ATTR_NAME | STRING | DYNAMIC_ATTR)* ';' */
    INHERIT
}
