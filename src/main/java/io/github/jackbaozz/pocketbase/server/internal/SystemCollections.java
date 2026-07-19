package io.github.jackbaozz.pocketbase.server.internal;

import java.util.List;

/**
 * Stable PocketBase system collection identifiers and their legacy Java aliases.
 */
public final class SystemCollections {
    public static final String SUPERUSERS = "_superusers";
    public static final String MFAS = "_mfas";
    public static final String OTPS = "_otps";
    public static final String EXTERNAL_AUTHS = "_externalAuths";
    public static final String AUTH_ORIGINS = "_authOrigins";

    public static final String SUPERUSERS_ID = "pbc_3142635823";
    public static final String MFAS_ID = "pbc_2279338944";
    public static final String OTPS_ID = "pbc_1638494021";
    public static final String EXTERNAL_AUTHS_ID = "pbc_2281828961";
    public static final String AUTH_ORIGINS_ID = "pbc_4275539003";

    public static final String LEGACY_SUPERUSERS_ID = "pbc_superusers";
    public static final String LEGACY_MFAS_ID = "pbc_mfas";
    public static final String LEGACY_OTPS_ID = "pbc_otps";
    public static final String LEGACY_EXTERNAL_AUTHS_ID = "pbc_externalAuths";
    public static final String LEGACY_AUTH_ORIGINS_ID = "pbc_authOrigins";

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition(SUPERUSERS, SUPERUSERS_ID, LEGACY_SUPERUSERS_ID),
            new Definition(MFAS, MFAS_ID, LEGACY_MFAS_ID),
            new Definition(OTPS, OTPS_ID, LEGACY_OTPS_ID),
            new Definition(EXTERNAL_AUTHS, EXTERNAL_AUTHS_ID, LEGACY_EXTERNAL_AUTHS_ID),
            new Definition(AUTH_ORIGINS, AUTH_ORIGINS_ID, LEGACY_AUTH_ORIGINS_ID)
    );

    private SystemCollections() {
    }

    public static List<Definition> definitions() {
        return DEFINITIONS;
    }

    public static boolean isSuperuserIdentifier(String value) {
        return SUPERUSERS.equals(value)
                || SUPERUSERS_ID.equals(value)
                || LEGACY_SUPERUSERS_ID.equals(value);
    }

    public static String canonicalIdentifier(String value) {
        for (Definition definition : DEFINITIONS) {
            if (definition.legacyId().equals(value)) {
                return definition.officialId();
            }
        }
        return value;
    }

    public record Definition(String name, String officialId, String legacyId) {
    }
}
