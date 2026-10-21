package io.gluonify.plasma;

import java.util.Set;

/**
 * gDown Bolt opened to applications that declare {@code uses: gdown} (decision of 2026-10-13; design: gluonify-gdown/paas.md, "Bolt for applications").
 * Contract between the control-plane (which issues the certificate and announces the address) and the agent (which sets the variables, the authority file and the network rule).
 */
public final class GdownBolt {
    private GdownBolt() {}

    /** gDown Bolt port: declared additional port, with the same number on the node; TLS mandatory. */
    public static final int PORT = 7687;
    /** Key of the {@code services} table of the desired state: {@code bolt+s://<node address>:7687} of a ready gdown instance (the ":" is not allowed in an application name). */
    public static final String SERVICE_KEY = "gdown:bolt";
    /** Variable set in the applications that use gdown: encrypted Bolt address ({@code bolt+s://host:7687}). */
    public static final String URL_ENV = "GLUONIFY_GDOWN_BOLT_URL";
    /** Variable set in the applications that use gdown: path of the PEM file of the platform's internal authority (gDown Bolt and HTTPS; read by the Python SDK). */
    public static final String CA_FILE_ENV = "GLUONIFY_GDOWN_CA_FILE";
    /** Secret of the desired state (public, sent with the secrets because it only travels through the tunnel): PEM certificate of the internal authority, in base64 on one line. */
    public static final String CA_SECRET = "GLUONIFY_INTERNAL_CA_CERT";
    /** Host directory (tmpfs) where the agent writes the authority; bound read-only at the same path in the instances' sandbox. */
    public static final String CA_DIR = "/run/gluonify-ca";
    /** Authority file as seen by the instance. */
    public static final String CA_FILE = CA_DIR + "/gluonify-internal-ca.pem";

    /** Variables set by the platform: never set by an application. */
    public static final Set<String> RESERVED_ENV = Set.of(URL_ENV, CA_FILE_ENV);

    /** True if the application uses gdown (mandatory or optional). */
    public static boolean usesGdown(final java.util.List<String> uses) {
        return uses != null && (uses.contains("gdown") || uses.contains("?gdown"));
    }
}
