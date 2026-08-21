package redis.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Redis glob semantics for KEYS.
 *
 * <p>The regex-translation approach this replaced gave regex meaning to characters that
 * are literals in a glob, so patterns matched the wrong keys and some threw outright.
 */
class GlobMatcherTest {

    private static void matches(String pattern, String text) {
        assertTrue(GlobMatcher.matches(pattern, text),
                "expected '" + pattern + "' to match '" + text + "'");
    }

    private static void doesNotMatch(String pattern, String text) {
        assertFalse(GlobMatcher.matches(pattern, text),
                "expected '" + pattern + "' NOT to match '" + text + "'");
    }

    @Nested
    @DisplayName("literals")
    class Literals {

        @Test
        void exactMatch() {
            matches("user", "user");
            doesNotMatch("user", "users");
            doesNotMatch("user", "use");
        }

        @Test
        @DisplayName("a dot is a literal dot, not any-character")
        void dotIsLiteral() {
            matches("user.1", "user.1");
            doesNotMatch("user.1", "userX1");
            doesNotMatch("user.1", "user_1");
        }

        @Test
        @DisplayName("regex metacharacters are literals")
        void regexMetacharactersAreLiteral() {
            matches("a+b", "a+b");
            doesNotMatch("a+b", "ab");
            doesNotMatch("a+b", "aaab");

            matches("price$", "price$");
            matches("^start", "^start");
            matches("a(b)c", "a(b)c");
            matches("a|b", "a|b");
            matches("x{2}", "x{2}");
        }

        @Test
        void emptyPatternMatchesOnlyEmptyText() {
            matches("", "");
            doesNotMatch("", "a");
        }
    }

    @Nested
    @DisplayName("star")
    class Star {

        @Test
        void matchesEverything() {
            matches("*", "");
            matches("*", "anything");
            matches("*", "user:1:session");
        }

        @Test
        void prefixAndSuffix() {
            matches("user:*", "user:1");
            matches("user:*", "user:");
            doesNotMatch("user:*", "users:1");

            matches("*:session", "user:1:session");
            doesNotMatch("*:session", "user:1:sessions");
        }

        @Test
        void inTheMiddle() {
            matches("user:*:session", "user:42:session");
            matches("user:*:session", "user::session");
            doesNotMatch("user:*:session", "user:42:token");
        }

        @Test
        void severalStars() {
            matches("*a*b*", "xaybz");
            matches("**", "anything");
            doesNotMatch("*a*b*", "ba");
        }
    }

    @Nested
    @DisplayName("question mark")
    class QuestionMark {

        @Test
        void matchesExactlyOneCharacter() {
            matches("user?", "user1");
            doesNotMatch("user?", "user");
            doesNotMatch("user?", "user12");
        }

        @Test
        void combinesWithStar() {
            matches("user:?:*", "user:1:session");
            doesNotMatch("user:?:*", "user:42:session");
        }
    }

    @Nested
    @DisplayName("character classes")
    class CharacterClasses {

        @Test
        void explicitSet() {
            matches("user.[12]", "user.1");
            matches("user.[12]", "user.2");
            doesNotMatch("user.[12]", "user.3");
        }

        @Test
        void ranges() {
            matches("key[a-z]", "keym");
            doesNotMatch("key[a-z]", "keyM");
            matches("key[0-9]", "key7");
            doesNotMatch("key[0-9]", "keya");
        }

        @Test
        void negation() {
            matches("key[^0-9]", "keya");
            doesNotMatch("key[^0-9]", "key7");
        }

        @Test
        @DisplayName("an unterminated class does not throw")
        void unterminatedClassIsTolerated() {
            assertDoesNotThrow(() -> GlobMatcher.matches("[abc", "a"));
            assertDoesNotThrow(() -> GlobMatcher.matches("[", "["));
            assertDoesNotThrow(() -> GlobMatcher.matches("[a-", "a"));
        }

        @Test
        @DisplayName("a malformed pattern does not throw")
        void malformedPatternsAreTolerated() {
            assertDoesNotThrow(() -> GlobMatcher.matches("[[[", "x"));
            assertDoesNotThrow(() -> GlobMatcher.matches("*[", "x"));
            assertDoesNotThrow(() -> GlobMatcher.matches("\\", "x"));
        }
    }

    @Nested
    @DisplayName("escapes")
    class Escapes {

        @Test
        void escapedStarIsLiteral() {
            matches("a\\*b", "a*b");
            doesNotMatch("a\\*b", "axb");
        }

        @Test
        void escapedQuestionMarkIsLiteral() {
            matches("a\\?b", "a?b");
            doesNotMatch("a\\?b", "axb");
        }

        @Test
        void escapedBracketIsLiteral() {
            matches("a\\[b", "a[b");
        }
    }
}
