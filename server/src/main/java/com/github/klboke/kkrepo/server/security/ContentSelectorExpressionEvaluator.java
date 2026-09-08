package com.github.klboke.kkrepo.server.security;

import com.github.klboke.kkrepo.cache.LocalCache;
import com.github.klboke.kkrepo.cache.LocalCacheFactory;
import com.github.klboke.kkrepo.persistence.jdbc.api.AssetPathFilter;
import com.github.klboke.kkrepo.protocol.maven.path.Coordinates;
import com.github.klboke.kkrepo.protocol.maven.path.MavenPathParser;
import com.github.klboke.kkrepo.protocol.npm.NpmPath;
import com.github.klboke.kkrepo.protocol.npm.NpmPathParser;
import com.google.re2j.Pattern;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ContentSelectorExpressionEvaluator {
  private static final MavenPathParser MAVEN_PATH_PARSER = new MavenPathParser();
  private static final NpmPathParser NPM_PATH_PARSER = new NpmPathParser();

  private ContentSelectorExpressionEvaluator() {
  }

  // Expressions, never authorization decisions, are cached by their complete text. Changes on
  // another replica select a different key when its durable security catalog revision refreshes.
  private static final LocalCache<String, Node> COMPILED = LocalCacheFactory.standard()
      .<String, Node>builder("content-selector-expressions").maximumSize(512)
      .expireAfterWrite(Duration.ofMinutes(10)).build();
  private static final Set<String> PATH_VARIABLES = Set.of("path", "name", "asset.name", "asset.path", "content.path");
  private static final Set<String> CSEL_VARIABLES = Set.of("path", "format");
  private static final Set<TokenType> COMPARISONS = Set.of(TokenType.EQ, TokenType.NE, TokenType.REGEX, TokenType.STARTS_WITH);
  private static final Set<String> VARIABLES = Set.of("path", "name", "asset.name", "asset.path",
      "content.path", "format", "component.format", "asset.format", "repository",
      "repository.name", "repository.format", "coordinate.groupId", "coordinate.artifactId",
      "coordinate.version", "coordinate.baseVersion", "coordinate.extension", "coordinate.classifier",
      "coordinate.name", "coordinate.scope", "package.name", "package.scope");

  static boolean matches(String expression, String repository, String format, String path) {
    if (expression == null || expression.isBlank()) return true; // legacy repository targets
    try {
      return compiled(expression).evaluate(variables(repository, format, path));
    } catch (RuntimeException e) {
      return false;
    }
  }

  static void validate(String type, String expression) {
    if (expression == null || expression.isBlank()) {
      throw new SecurityValidationException("Content selector expression is required");
    }
    if (!"csel".equalsIgnoreCase(type) && !"jexl".equalsIgnoreCase(type)) {
      throw new SecurityValidationException("Unsupported selector type: " + type);
    }
    try {
      new Parser(tokens(expression), "csel".equalsIgnoreCase(type)).parse();
    } catch (IllegalArgumentException | com.google.re2j.PatternSyntaxException e) {
      throw new SecurityValidationException("Invalid " + type.toUpperCase(Locale.ROOT) + ": " + e.getMessage());
    }
  }

  static AssetPathFilter candidateFilter(String expression, String repository, String format) {
    if (expression == null || expression.isBlank()) return AssetPathFilter.ALL;
    try {
      Map<String, String> fixed = new HashMap<>();
      put(fixed, "format", nexusFormat(format));
      put(fixed, "component.format", nexusFormat(format));
      put(fixed, "asset.format", nexusFormat(format));
      put(fixed, "repository.format", nexusFormat(format));
      put(fixed, "repository", repository);
      put(fixed, "repository.name", repository);
      return compiled(expression).filter(fixed);
    } catch (RuntimeException e) {
      return AssetPathFilter.NONE;
    }
  }

  private static Node compiled(String expression) {
    return COMPILED.get(expression, text -> new Parser(tokens(text), false).parse());
  }

  private static List<Token> tokens(String expression) {
    if (expression.length() > 8192) throw new IllegalArgumentException("Expression exceeds 8192 characters");
    List<Token> tokens = new Lexer(expression).lex();
    if (tokens.size() > 1024) throw new IllegalArgumentException("Expression is too complex");
    return tokens;
  }

  static String nexusFormat(String format) {
    if (format == null || format.isBlank()) {
      return "*";
    }
    return format.trim().toLowerCase(Locale.ROOT);
  }

  private static Map<String, String> variables(String repository, String format, String path) {
    String normalizedPath = stripLeadingSlashes(path == null ? "" : path);
    String normalizedFormat = nexusFormat(format);
    Map<String, String> values = new HashMap<>();
    put(values, "path", normalizedPath);
    put(values, "name", normalizedPath);
    put(values, "asset.name", normalizedPath);
    put(values, "asset.path", normalizedPath);
    put(values, "content.path", normalizedPath);
    put(values, "format", normalizedFormat);
    put(values, "component.format", normalizedFormat);
    put(values, "asset.format", normalizedFormat);
    put(values, "repository", repository);
    put(values, "repository.name", repository);
    put(values, "repository.format", normalizedFormat);
    addFormatCoordinates(values, normalizedFormat, normalizedPath);
    return values;
  }

  private static void addFormatCoordinates(Map<String, String> values, String format, String path) {
    if ("maven2".equals(format)) {
      Coordinates coordinates = MAVEN_PATH_PARSER.parsePath(path).coordinates();
      if (coordinates != null) {
        put(values, "coordinate.groupId", coordinates.groupId());
        put(values, "coordinate.artifactId", coordinates.artifactId());
        put(values, "coordinate.version", coordinates.baseVersion());
        put(values, "coordinate.baseVersion", coordinates.baseVersion());
        put(values, "coordinate.extension", coordinates.extension());
        put(values, "coordinate.classifier", stringOrEmpty(coordinates.classifier()));
      }
    } else if ("npm".equals(format)) {
      NpmPath npmPath = NPM_PATH_PARSER.parse(path);
      if (npmPath.packageId() != null) {
        put(values, "coordinate.name", npmPath.packageId().id());
        put(values, "coordinate.scope", npmPath.packageId().scope());
        put(values, "coordinate.version", npmPath.packageVersion());
        put(values, "package.name", npmPath.packageId().id());
        put(values, "package.scope", npmPath.packageId().scope());
      }
    }
  }

  private static void put(Map<String, String> values, String key, String value) {
    if (value != null) {
      values.put(key, value);
    }
  }

  private static String stripLeadingSlashes(String value) {
    String result = value;
    while (result.startsWith("/")) {
      result = result.substring(1);
    }
    return result;
  }

  private static String stringOrEmpty(String value) {
    return value == null ? "" : value;
  }

  private static java.util.function.Predicate<String> regex(String expression) {
    if (expression.startsWith("(?!")) {
      int end = expression.indexOf(')', 3);
      if (end < 0) throw new IllegalArgumentException("Invalid negative lookahead");
      Pattern forbidden = Pattern.compile(expression.substring(3, end));
      String tail = expression.substring(end + 1);
      Pattern remaining = Pattern.compile(tail.isBlank() ? ".*" : tail);
      return value -> !forbidden.matcher(value).lookingAt() && remaining.matcher(value).matches();
    }
    Pattern pattern = Pattern.compile(expression);
    return value -> pattern.matcher(value).matches();
  }

  private record Token(TokenType type, String value) {
  }

  private enum TokenType {
    IDENTIFIER,
    STRING,
    TRUE,
    FALSE,
    EQ,
    NE,
    REGEX,
    STARTS_WITH,
    AND,
    OR,
    NOT,
    LPAREN,
    RPAREN,
    EOF
  }

  private static final class Lexer {
    private final String input;
    private int index;

    private Lexer(String input) {
      this.input = input;
    }

    private java.util.List<Token> lex() {
      java.util.List<Token> tokens = new java.util.ArrayList<>();
      while (index < input.length()) {
        char c = input.charAt(index);
        if (Character.isWhitespace(c)) {
          index++;
        } else if (c == '(') {
          tokens.add(new Token(TokenType.LPAREN, "("));
          index++;
        } else if (c == ')') {
          tokens.add(new Token(TokenType.RPAREN, ")"));
          index++;
        } else if (c == '\'' || c == '"') {
          tokens.add(new Token(TokenType.STRING, string(c)));
        } else if (startsWith("==")) {
          tokens.add(new Token(TokenType.EQ, "=="));
          index += 2;
        } else if (startsWith("!=")) {
          tokens.add(new Token(TokenType.NE, "!="));
          index += 2;
        } else if (startsWith("=~")) {
          tokens.add(new Token(TokenType.REGEX, "=~"));
          index += 2;
        } else if (startsWith("=^")) {
          tokens.add(new Token(TokenType.STARTS_WITH, "=^"));
          index += 2;
        } else if (startsWith("&&")) {
          tokens.add(new Token(TokenType.AND, "&&"));
          index += 2;
        } else if (startsWith("||")) {
          tokens.add(new Token(TokenType.OR, "||"));
          index += 2;
        } else if (c == '!') {
          tokens.add(new Token(TokenType.NOT, "!"));
          index++;
        } else if (isIdentifierStart(c)) {
          tokens.add(identifier());
        } else {
          throw new IllegalArgumentException("Unsupported CSEL token at " + index);
        }
      }
      tokens.add(new Token(TokenType.EOF, ""));
      return tokens;
    }

    private boolean startsWith(String token) {
      return input.startsWith(token, index);
    }

    private String string(char quote) {
      index++;
      StringBuilder result = new StringBuilder();
      while (index < input.length()) {
        char c = input.charAt(index++);
        if (c == quote) {
          return result.toString();
        }
        if (c == '\\' && index < input.length()) {
          char escaped = input.charAt(index++);
          result.append(switch (escaped) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> escaped;
          });
        } else {
          result.append(c);
        }
      }
      throw new IllegalArgumentException("Unterminated string literal");
    }

    private Token identifier() {
      int start = index;
      index++;
      while (index < input.length() && isIdentifierPart(input.charAt(index))) {
        index++;
      }
      String value = input.substring(start, index);
      return switch (value) {
        case "true" -> new Token(TokenType.TRUE, value);
        case "false" -> new Token(TokenType.FALSE, value);
        case "and" -> new Token(TokenType.AND, value);
        case "or" -> new Token(TokenType.OR, value);
        case "not" -> new Token(TokenType.NOT, value);
        default -> new Token(TokenType.IDENTIFIER, value);
      };
    }

    private boolean isIdentifierStart(char c) {
      return Character.isLetter(c) || c == '_' || c == '@';
    }

    private boolean isIdentifierPart(char c) {
      return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '@';
    }
  }

  private record Value(String text, Boolean bool, String variable) {
    private boolean truthy() {
      if (bool != null) {
        return bool;
      }
      return text != null && !text.isBlank();
    }
  }

  private interface Node {
    boolean evaluate(Map<String, String> variables);
    default AssetPathFilter filter(Map<String, String> fixed) { return AssetPathFilter.ALL; }
  }

  private record Operand(String literal, Boolean bool, String variable) {
    Value resolve(Map<String, String> values) {
      return new Value(variable == null ? literal : values.get(variable), bool, variable);
    }
    boolean fixed(Map<String, String> values) { return variable == null || values.containsKey(variable); }
    boolean path() { return PATH_VARIABLES.contains(variable == null ? "" : variable); }
  }

  private record Junction(Node left, Node right, boolean and) implements Node {
    public boolean evaluate(Map<String, String> values) {
      return and ? left.evaluate(values) && right.evaluate(values) : left.evaluate(values) || right.evaluate(values);
    }
    public AssetPathFilter filter(Map<String, String> values) {
      return and ? AssetPathFilter.and(left.filter(values), right.filter(values))
          : AssetPathFilter.or(left.filter(values), right.filter(values));
    }
  }

  private record Comparison(Operand left, Operand right, TokenType operator,
      java.util.function.Predicate<String> pattern) implements Node {
    public boolean evaluate(Map<String, String> values) {
      Value l = left.resolve(values), r = right.resolve(values);
      return switch (operator) {
        case EQ -> equal(l, r);
        case NE -> !equal(l, r);
        case STARTS_WITH -> normalize(l, left.path() || right.path()).startsWith(normalize(r, left.path() || right.path()));
        case REGEX -> pattern.test(normalize(l, false))
            || (left.path() && pattern.test("/" + normalize(l, true)));
        default -> false;
      };
    }
    public AssetPathFilter filter(Map<String, String> values) {
      if (left.fixed(values) && right.fixed(values)) {
        return evaluate(values) ? AssetPathFilter.ALL : AssetPathFilter.NONE;
      }
      if (left.path() && right.variable() == null && right.bool() == null) {
        String path = normalize(right.resolve(values), true);
        if (operator == TokenType.EQ) return AssetPathFilter.exact(path);
        if (operator == TokenType.STARTS_WITH) return AssetPathFilter.prefix(path);
      }
      if (right.path() && left.variable() == null && left.bool() == null && operator == TokenType.EQ) {
        return AssetPathFilter.exact(normalize(left.resolve(values), true));
      }
      return AssetPathFilter.ALL;
    }
    private static boolean equal(Value left, Value right) {
      if ("coordinate.extension".equals(left.variable()) || "coordinate.extension".equals(right.variable())) {
        return normalize(left, false).replaceFirst("^\\.+", "").equals(normalize(right, false).replaceFirst("^\\.+", ""));
      }
      // Legacy path comparisons use literal text even for Boolean tokens. Moving this
      // behind truthiness would turn `path == true` into a grant for every nonempty path.
      boolean path = new Operand(null, null, left.variable()).path() || new Operand(null, null, right.variable()).path();
      if (!path && (left.bool() != null || right.bool() != null)) return left.truthy() == right.truthy();
      if (left.text() == null || right.text() == null) return Objects.equals(left.text(), right.text());
      return normalize(left, path).equals(normalize(right, path));
    }
    private static String normalize(Value value, boolean path) {
      String text = value.text() == null ? "" : value.text();
      return path ? stripLeadingSlashes(text) : text;
    }
  }

  private static final class Parser {
    private final List<Token> tokens;
    private final boolean strict;
    private int index;
    private int depth;
    Parser(List<Token> tokens, boolean strict) { this.tokens = tokens; this.strict = strict; }
    Node parse() {
      Node result = or();
      expect(TokenType.EOF);
      return result;
    }
    private Node or() {
      Node result = and();
      while (match(TokenType.OR)) result = new Junction(result, and(), false);
      return result;
    }
    private Node and() {
      Node result = unary();
      while (match(TokenType.AND)) result = new Junction(result, unary(), true);
      return result;
    }
    private Node unary() {
      if (++depth > 64) throw new IllegalArgumentException("Expression nesting exceeds 64");
      try {
        if (match(TokenType.NOT)) {
          if (strict) throw new IllegalArgumentException("Negation is not supported in CSEL");
          Node child = unary();
          return values -> !child.evaluate(values);
        }
        if (match(TokenType.LPAREN)) {
          Node result = or();
          expect(TokenType.RPAREN);
          return result;
        }
        Operand left = operand();
        TokenType operator = peek().type();
        if (COMPARISONS.contains(operator)) {
          index++;
          Operand right = operand();
          java.util.function.Predicate<String> regex = null;
          if (operator == TokenType.REGEX) {
            if (right.variable() != null || right.bool() != null) throw new IllegalArgumentException("Regex must be a string literal");
            regex = regex(right.literal());
          }
          return new Comparison(left, right, operator, regex);
        }
        if (strict) throw new IllegalArgumentException("Expected a comparison operator");
        return values -> left.resolve(values).truthy();
      } finally { depth--; }
    }
    private Operand operand() {
      Token token = peek();
      if (match(TokenType.STRING)) {
        if (strict && (token.value().contains("\"") || token.value().contains("'"))) {
          throw new IllegalArgumentException("Embedded quotes are not supported in CSEL");
        }
        return new Operand(token.value(), null, null);
      }
      if (!strict && (match(TokenType.TRUE) || match(TokenType.FALSE))) {
        return new Operand(token.value(), Boolean.valueOf(token.value()), null);
      }
      if (match(TokenType.IDENTIFIER)) {
        if (!(strict ? CSEL_VARIABLES : VARIABLES).contains(token.value())) {
          throw new IllegalArgumentException("Unknown selector variable: " + token.value());
        }
        return new Operand(null, null, token.value());
      }
      throw new IllegalArgumentException("Expected a string or variable at token " + index);
    }
    private Token peek() { return tokens.get(index); }
    private boolean match(TokenType type) {
      if (peek().type() != type) return false;
      index++;
      return true;
    }
    private void expect(TokenType type) {
      if (!match(type)) throw new IllegalArgumentException("Expected " + type + " at token " + index);
    }
  }
}
