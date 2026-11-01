package com.dypcet.vayora.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every notification template the server writes is one the web app can translate. The app
 * lists them in web/src/lib/notificationText.ts; one missing there would still be shown,
 * but only in English.
 */
class NotificationTemplatesTest {

    // Text.of( followed by one or more string literals joined by +.
    private static final Pattern TEMPLATE = Pattern.compile(
            "Text\\.of\\(\\s*((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+)");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void everyTemplateTheServerWritesIsListedByTheWebApp() throws IOException {
        Set<String> used = serverTemplates();
        String web = Files.readString(Path.of("../web/src/lib/notificationText.ts"));

        assertThat(used).hasSizeGreaterThan(20);
        assertThat(used).allSatisfy(template -> assertThat(web)
                .as("web/src/lib/notificationText.ts should list: %s", template)
                .contains(webLiteral(template)));
    }

    private static Set<String> serverTemplates() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher call = TEMPLATE.matcher(Files.readString(file));
                while (call.find()) {
                    StringBuilder template = new StringBuilder();
                    Matcher literal = LITERAL.matcher(call.group(1));
                    while (literal.find()) {
                        template.append(literal.group(1).replace("\\\"", "\""));
                    }
                    found.add(template.toString());
                }
            }
        }
        return found;
    }

    /** The template as the web file writes it: in single quotes, or in double quotes if it has an apostrophe. */
    private static String webLiteral(String template) {
        return template.contains("'") ? "\"" + template + "\"" : "'" + template + "'";
    }
}
