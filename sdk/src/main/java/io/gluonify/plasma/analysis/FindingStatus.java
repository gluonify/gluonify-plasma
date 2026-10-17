package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Life cycle of a finding: open, accepted (dated exception), fixed. */
public enum FindingStatus {
    @JsonProperty("open") OPEN,
    @JsonProperty("accepted") ACCEPTED,
    @JsonProperty("fixed") FIXED
}
