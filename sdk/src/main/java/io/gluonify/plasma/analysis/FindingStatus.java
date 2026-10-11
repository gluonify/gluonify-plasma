package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Cycle de vie d'un constat : ouvert, accepté (exception datée), corrigé. */
public enum FindingStatus {
    @JsonProperty("open") OPEN,
    @JsonProperty("accepted") ACCEPTED,
    @JsonProperty("fixed") FIXED
}
