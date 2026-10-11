package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Endroit d'un constat : chemin <strong>relatif au dépôt</strong> (jamais un chemin du builder), ligne facultative.
 * Un chemin absolu, avec {@code ..} ou des barres obliques inverses est refusé au constructeur.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Location(String file, Integer line) {
    public Location {
        if (file == null || file.isBlank()) throw new IllegalArgumentException("location.file absent");
        if (file.startsWith("/") || file.contains("\\") || file.matches("^[A-Za-z]:.*")) throw new IllegalArgumentException("location.file doit être relatif au dépôt : " + file);
        for (String part : file.split("/")) if (part.equals("..")) throw new IllegalArgumentException("location.file sort du dépôt : " + file);
        if (line != null && line < 1) throw new IllegalArgumentException("location.line < 1 : " + line);
    }

    public static Location of(String file) {
        return new Location(file, null);
    }

    /** Forme stable de l'identifiant : {@code fichier} ou {@code fichier:ligne}. */
    public String key() {
        return line == null ? file : file + ":" + line;
    }
}
