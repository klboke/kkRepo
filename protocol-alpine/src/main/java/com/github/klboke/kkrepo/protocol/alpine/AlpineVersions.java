package com.github.klboke.kkrepo.protocol.alpine;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Map;

/** Byte-for-byte Java port of the apk-tools v2/v3 version token ordering. */
public final class AlpineVersions {
  public static final Comparator<String> COMPARATOR = AlpineVersions::compare;

  private static final int INITIAL_DIGIT = 0;
  private static final int DIGIT = 1;
  private static final int LETTER = 2;
  private static final int SUFFIX = 3;
  private static final int SUFFIX_NUMBER = 4;
  private static final int COMMIT_HASH = 5;
  private static final int REVISION_NUMBER = 6;
  private static final int END = 7;
  private static final int INVALID = 8;

  private static final Map<String, Integer> SUFFIXES = Map.ofEntries(
      Map.entry("alpha", 1),
      Map.entry("beta", 2),
      Map.entry("pre", 3),
      Map.entry("rc", 4),
      Map.entry("cvs", 6),
      Map.entry("svn", 7),
      Map.entry("git", 8),
      Map.entry("hg", 9),
      Map.entry("p", 10));
  private static final int SUFFIX_NONE = 5;

  private AlpineVersions() {
  }

  public static int compare(String left, String right) {
    if (left == right) return 0;
    if (left == null) return -1;
    if (right == null) return 1;
    Cursor a = new Cursor(left);
    Cursor b = new Cursor(right);
    Token ta = a.first();
    Token tb = b.first();
    while (ta.kind == tb.kind && ta.kind < END) {
      int compared = compareToken(ta, tb);
      if (compared != 0) return compared;
      ta = a.next(ta);
      tb = b.next(tb);
    }
    if (ta.kind == tb.kind) return 0;
    if (ta.kind == SUFFIX && ta.suffix < SUFFIX_NONE) return -1;
    if (tb.kind == SUFFIX && tb.suffix < SUFFIX_NONE) return 1;
    if (ta.kind > tb.kind) return -1;
    if (tb.kind > ta.kind) return 1;
    return 0;
  }

  public static boolean isValid(String value) {
    if (value == null || value.isEmpty() || value.length() > 255) return false;
    Cursor cursor = new Cursor(value);
    Token token = cursor.first();
    while (token.kind < END) token = cursor.next(token);
    return token.kind == END;
  }

  public static String require(String value) {
    if (!isValid(value)) throw new IllegalArgumentException("Invalid Alpine version: " + value);
    return value;
  }

  private static int compareToken(Token a, Token b) {
    return switch (a.kind) {
      case INITIAL_DIGIT, SUFFIX_NUMBER, REVISION_NUMBER -> a.number.compareTo(b.number);
      case DIGIT -> a.value.startsWith("0") || b.value.startsWith("0")
          ? a.value.compareTo(b.value) : a.number.compareTo(b.number);
      case LETTER -> Character.compare(a.value.charAt(0), b.value.charAt(0));
      case SUFFIX -> Integer.compare(a.suffix, b.suffix);
      default -> a.value.compareTo(b.value);
    };
  }

  private record Token(int kind, String value, BigInteger number, int suffix) {
    private static Token end() {
      return new Token(END, "", BigInteger.ZERO, 0);
    }

    private static Token invalid(String value) {
      return new Token(INVALID, value, BigInteger.ZERO, 0);
    }
  }

  private static final class Cursor {
    private final String value;
    private int offset;

    private Cursor(String value) {
      this.value = value;
    }

    private Token first() {
      return digits(INITIAL_DIGIT);
    }

    private Token next(Token previous) {
      if (offset == value.length()) return Token.end();
      char current = value.charAt(offset);
      if (current >= 'a' && current <= 'z') {
        if (previous.kind > DIGIT) return Token.invalid(value.substring(offset));
        offset++;
        return new Token(LETTER, Character.toString(current), BigInteger.ZERO, 0);
      }
      if (current == '.') {
        if (previous.kind > DIGIT) return Token.invalid(value.substring(offset));
        offset++;
        return digits(DIGIT);
      }
      if (current >= '0' && current <= '9') {
        int kind = switch (previous.kind) {
          case INITIAL_DIGIT, DIGIT -> DIGIT;
          case SUFFIX -> SUFFIX_NUMBER;
          default -> INVALID;
        };
        return kind == INVALID ? Token.invalid(value.substring(offset)) : digits(kind);
      }
      if (current == '_') {
        if (previous.kind > SUFFIX_NUMBER) return Token.invalid(value.substring(offset));
        int start = ++offset;
        while (offset < value.length()) {
          char character = value.charAt(offset);
          if (character < 'a' || character > 'z') break;
          offset++;
        }
        String suffix = value.substring(start, offset);
        Integer ordinal = SUFFIXES.get(suffix);
        return ordinal == null
            ? Token.invalid(value.substring(start))
            : new Token(SUFFIX, suffix, BigInteger.ZERO, ordinal);
      }
      if (current == '~') {
        if (previous.kind >= COMMIT_HASH) return Token.invalid(value.substring(offset));
        int start = ++offset;
        while (offset < value.length() && hex(value.charAt(offset))) offset++;
        if (start == offset) return Token.invalid(value.substring(start));
        return new Token(COMMIT_HASH, value.substring(start, offset), BigInteger.ZERO, 0);
      }
      if (current == '-') {
        if (previous.kind >= REVISION_NUMBER || offset + 2 > value.length()
            || value.charAt(offset + 1) != 'r') {
          return Token.invalid(value.substring(offset));
        }
        offset += 2;
        return digits(REVISION_NUMBER);
      }
      return Token.invalid(value.substring(offset));
    }

    private Token digits(int kind) {
      int start = offset;
      while (offset < value.length()) {
        char character = value.charAt(offset);
        if (character < '0' || character > '9') break;
        offset++;
      }
      if (start == offset) return Token.invalid(value.substring(start));
      String digits = value.substring(start, offset);
      return new Token(kind, digits, new BigInteger(digits), 0);
    }

    private static boolean hex(char value) {
      return value >= '0' && value <= '9'
          || value >= 'a' && value <= 'f'
          || value >= 'A' && value <= 'F';
    }
  }
}
