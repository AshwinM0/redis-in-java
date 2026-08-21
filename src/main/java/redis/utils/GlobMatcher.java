package redis.utils;

/**
 * Redis glob-style pattern matching, as used by KEYS.
 *
 * <p>Supports {@code *} (any run of characters), {@code ?} (exactly one), {@code [...]}
 * character classes with ranges and {@code ^} negation, and {@code \} escapes. Every other
 * character is a literal, so regex metacharacters in a key or pattern carry no special
 * meaning and cannot throw.
 *
 * <p>The semantics follow Redis's {@code stringmatchlen}, including its tolerance of an
 * unterminated character class.
 */
public final class GlobMatcher {

    private GlobMatcher() {
    }

    public static boolean matches(String pattern, String text) {
        return match(pattern, 0, text, 0);
    }

    private static boolean match(String p, int pi, String s, int si) {
        while (pi < p.length()) {
            switch (p.charAt(pi)) {
                case '*':
                    while (pi + 1 < p.length() && p.charAt(pi + 1) == '*') {
                        pi++;
                    }
                    if (pi + 1 == p.length()) {
                        return true; // trailing star swallows the rest
                    }
                    for (int split = si; split <= s.length(); split++) {
                        if (match(p, pi + 1, s, split)) {
                            return true;
                        }
                    }
                    return false;

                case '?':
                    if (si == s.length()) {
                        return false;
                    }
                    si++;
                    break;

                case '[': {
                    if (si == s.length()) {
                        return false;
                    }
                    pi++;
                    boolean negate = pi < p.length() && p.charAt(pi) == '^';
                    if (negate) {
                        pi++;
                    }
                    boolean found = false;
                    while (true) {
                        if (pi == p.length()) {
                            pi--; // unterminated class: treat '[' as the last literal
                            break;
                        }
                        char pc = p.charAt(pi);
                        if (pc == '\\' && pi + 1 < p.length()) {
                            pi++;
                            if (p.charAt(pi) == s.charAt(si)) {
                                found = true;
                            }
                        } else if (pc == ']') {
                            break;
                        } else if (pi + 2 < p.length() && p.charAt(pi + 1) == '-') {
                            char lo = pc;
                            char hi = p.charAt(pi + 2);
                            if (lo > hi) {
                                char swap = lo;
                                lo = hi;
                                hi = swap;
                            }
                            pi += 2;
                            if (s.charAt(si) >= lo && s.charAt(si) <= hi) {
                                found = true;
                            }
                        } else if (pc == s.charAt(si)) {
                            found = true;
                        }
                        pi++;
                    }
                    if (negate) {
                        found = !found;
                    }
                    if (!found) {
                        return false;
                    }
                    si++;
                    break;
                }

                case '\\':
                    if (pi + 1 < p.length()) {
                        pi++;
                    }
                    // fall through: the escaped character is matched literally
                default:
                    if (si == s.length() || p.charAt(pi) != s.charAt(si)) {
                        return false;
                    }
                    si++;
                    break;
            }

            pi++;
            if (si == s.length()) {
                while (pi < p.length() && p.charAt(pi) == '*') {
                    pi++;
                }
                break;
            }
        }
        return pi == p.length() && si == s.length();
    }
}
