package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Place of a finding: path <strong>relative to the repository</strong> (never a builder path), optional line.
 * An absolute path, with {@code ..} or backslashes, is rejected by the constructor.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Location(String file, Integer line) {
    public Location {
        if (file == null || file.isBlank()) throw new IllegalArgumentException("location.file missing");
        if (file.startsWith("/") || file.contains("\\") || file.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("location.file must be relative to the repository: " + file);
        for (String part : file.split("/")) if (part.equals("..")) throw new IllegalArgumentException("location.file leaves the repository: " + file);
        if (line != null && line < 1) throw new IllegalArgumentException("location.line < 1 : " + line);
    }

    public static Location of(final String file) {
        return new Location(file, null);
    }

    /** Stable form of the identifier: {@code file} or {@code file:line}. */
    public String key() {
        return line == null ? file : file + ":" + line;
    }
}
