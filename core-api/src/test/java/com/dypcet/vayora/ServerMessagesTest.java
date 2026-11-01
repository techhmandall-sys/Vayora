package com.dypcet.vayora;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every error message the server can send is one the web app can translate. The app lists
 * them in web/src/lib/serverMessages.ts, fixed messages as they are and messages built from
 * values as templates; one missing there would still be shown, but only in English.
 */
class ServerMessagesTest {

    // An error built with only string literals: new ResponseStatusException(STATUS, "..." + "..."),
    // super(STATUS, ...) in a subclass of it, or one of the helpers conflict, notFound, bad and
    // badRequest.
    private static final Pattern CALL = Pattern.compile(
            "(?:(?:ResponseStatusException|\\bsuper)\\(\\s*HttpStatus\\.[A-Z_]+\\s*,"
                    + "|\\b(?:conflict|notFound|badRequest|bad)\\()"
                    + "\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+)\\)");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    // Where any such error's message begins, whatever it is built from.
    private static final Pattern START = Pattern.compile(
            "(?:ResponseStatusException|\\bsuper)\\(\\s*HttpStatus\\.[A-Z_]+\\s*,"
                    + "|\\b(?:conflict|notFound|badRequest|bad)\\(");

    @Test
    void everyFixedErrorMessageIsListedByTheWebApp() throws IOException {
        Set<String> used = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher call = CALL.matcher(Files.readString(file));
                while (call.find()) {
                    StringBuilder message = new StringBuilder();
                    Matcher literal = LITERAL.matcher(call.group(1));
                    while (literal.find()) {
                        message.append(literal.group(1).replace("\\\"", "\""));
                    }
                    used.add(message.toString());
                }
            }
        }
        String web = Files.readString(Path.of("../web/src/lib/serverMessages.ts"));

        assertThat(used).hasSizeGreaterThan(70);
        assertThat(used).allSatisfy(message -> assertThat(web)
                .as("web/src/lib/serverMessages.ts should list: %s", message)
                .contains(quoted(message)));
    }

    @Test
    void everyErrorMessageBuiltFromValuesHasATemplateInTheWebApp() throws IOException {
        Set<String> built = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher start = START.matcher(source);
                while (start.find()) {
                    List<String> parts = parts(argument(source, start.end()));
                    // Literals and values together; a value passed on alone is checked where it is made.
                    if (parts.stream().anyMatch(ServerMessagesTest::literal)
                            && !parts.stream().allMatch(ServerMessagesTest::literal)) {
                        built.add(parts.stream()
                                .map(part -> literal(part)
                                        ? part.substring(1, part.length() - 1).replace("\\\"", "\"")
                                        : "{}")
                                .collect(Collectors.joining()));
                    }
                }
            }
        }
        // A template's placeholders stand for one value each, whatever they are called.
        String web = Files.readString(Path.of("../web/src/lib/serverMessages.ts")).replaceAll("\\{\\w+\\}", "{}");

        assertThat(built).contains("You can have at most {} emergency contacts", "A {} booking cannot become {}",
                "Too many wrong passwords. Try again in {} min.");
        assertThat(built).allSatisfy(message -> assertThat(web)
                .as("web/src/lib/serverMessages.ts should have a template for: %s", message)
                .contains(quoted(message)));
    }

    private static String quoted(String message) {
        return message.contains("'") ? "\"" + message + "\"" : "'" + message + "'";
    }

    private static boolean literal(String part) {
        return part.length() >= 2 && part.startsWith("\"") && part.endsWith("\"");
    }

    /** A call's argument: from just after its opening bracket to the bracket that closes it. */
    private static String argument(String source, int from) {
        int depth = 1;
        boolean inString = false;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return source.substring(from, i);
            }
        }
        throw new IllegalStateException("A call opened at " + from + " is never closed");
    }

    /** What an argument joins with "+", outside brackets and strings, each trimmed. */
    private static List<String> parts(String argument) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        int begin = 0;
        for (int i = 0; i < argument.length(); i++) {
            char c = argument.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '+' && depth == 0) {
                parts.add(argument.substring(begin, i).trim());
                begin = i + 1;
            }
        }
        parts.add(argument.substring(begin).trim());
        return parts;
    }
}
