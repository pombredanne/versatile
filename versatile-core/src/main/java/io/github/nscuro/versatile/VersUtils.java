/*
 * This file is part of versatile.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) Niklas Düster. All Rights Reserved.
 */
package io.github.nscuro.versatile;

import io.github.nscuro.versatile.spi.InvalidVersionException;
import io.github.nscuro.versatile.version.KnownVersioningSchemes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class VersUtils {

    private VersUtils() {
    }

    /**
     * Convert an ecosystem and version range as used by GitHub Security Advisories to a {@link Vers} range.
     * <p>
     * Ranges are composed of one or more constraints, separated by commas. Valid comparators for constraints
     * are {@code =}, {@code >=}, {@code >}, {@code <}, and {@code <=}. For example, a valid range is {@code >= 1.2.3, < 5.0.1}.
     *
     * @param ecosystem The ecosystem of the affected package
     * @param rangeExpr The affected version range expression
     * @return The resulting {@link Vers}
     * @throws IllegalArgumentException When the provided range expression is invalid
     * @throws VersException            When the produced {@link Vers} is invalid
     * @throws InvalidVersionException  When any version in the range is invalid according to the inferred scheme
     * @see <a href="https://docs.github.com/en/rest/security-advisories/global-advisories?apiVersion=2022-11-28#get-a-global-security-advisory">GitHub Security Advisories API documentation</a>
     */
    public static Vers versFromGhsaRange(final String ecosystem, final String rangeExpr) {
        final var versBuilder = Vers.builder(schemeFromGhsaEcosystem(ecosystem).orElse(ecosystem));

        final String[] constraintExprs = rangeExpr.split(",");

        for (int i = 0; i < constraintExprs.length; i++) {
            final String constraintExpr = constraintExprs[i].trim();

            if (constraintExpr.startsWith("<=")) {
                versBuilder.withConstraint(Comparator.LESS_THAN_OR_EQUAL, constraintExpr.replaceFirst("<=", "").trim());
            } else if (constraintExpr.startsWith("<")) {
                versBuilder.withConstraint(Comparator.LESS_THAN, constraintExpr.replaceFirst("<", "").trim());
            } else if (constraintExpr.startsWith(">=")) {
                versBuilder.withConstraint(Comparator.GREATER_THAN_OR_EQUAL, constraintExpr.replaceFirst(">=", "").trim());
            } else if (constraintExpr.startsWith(">")) {
                versBuilder.withConstraint(Comparator.GREATER_THAN, constraintExpr.replaceFirst(">", "").trim());
            } else if (constraintExpr.startsWith("=")) {
                versBuilder.withConstraint(Comparator.EQUAL, constraintExpr.replaceFirst("=", "").trim());
            } else {
                throw new IllegalArgumentException("Invalid constraint \"%s\" at position %d".formatted(constraintExpr, i));
            }
        }

        return versBuilder.build();
    }

    /**
     * Convert a range type, ecosystem, and range events as used by OSV to a {@link Vers} range.
     *
     * @param type      The type of the range, must be either {@code ECOSYSTEM} or {@code SEMVER}
     * @param ecosystem The ecosystem of the affected package
     * @param events    The events in the range
     * @return The resulting {@link Vers}
     * @throws IllegalArgumentException When the provided range type is not support supported,
     *                                  or the provided {@code events} contains an invalid event
     * @throws VersException            When the produced {@link Vers} is invalid
     * @throws InvalidVersionException  When any version in the range is invalid according to the inferred scheme
     */
    public static Vers versFromOsvRange(
            final String type, final String ecosystem,
            final List<Map.Entry<String, String>> events,
            final Map<String, Object> databaseSpecific
    ) {
        if (!"ecosystem".equalsIgnoreCase(type) && !"semver".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("Range type \"%s\" is not supported".formatted(type));
        }

        final var scheme = schemeFromOsvEcosystem(ecosystem).orElse(ecosystem);
        final var versBuilder = Vers.builder(scheme);

        for (int i = 0; i < events.size(); i++) {
            final Map.Entry<String, String> event = events.get(i);

            final Comparator comparator = switch (event.getKey()) {
                case "introduced" -> Comparator.GREATER_THAN_OR_EQUAL;
                case "fixed", "limit" -> Comparator.LESS_THAN;
                case "last_affected" -> Comparator.LESS_THAN_OR_EQUAL;
                default -> throw new IllegalArgumentException("Invalid event \"%s\" at position %d"
                        .formatted(event.getKey(), i));
            };

            if ("deb".equals(scheme)
                    && (comparator == Comparator.LESS_THAN || comparator == Comparator.LESS_THAN_OR_EQUAL)
                    && Set.of("<end-of-life>", "<unfixed>").contains(event.getValue())) {
                // Some ranges in the Debian ecosystem use these special values for their upper bound,
                // to signal that all versions are affected. As they are not valid versions, we skip them.
                //
                // introduced=0, fixed=<unfixed> is equivalent to >=0.
                continue;
            }

            versBuilder.withConstraint(comparator, event.getValue());
        }

        if (databaseSpecific != null && databaseSpecific.get("last_known_affected_version_range") instanceof String) {
            String lastKnownAffectedRange = (String) databaseSpecific.get("last_known_affected_version_range");
            if (lastKnownAffectedRange.startsWith("<=")) {
                versBuilder.withConstraint(Comparator.LESS_THAN_OR_EQUAL, lastKnownAffectedRange.replaceFirst("<=", "").trim());
            } else if (lastKnownAffectedRange.startsWith("<")) {
                versBuilder.withConstraint(Comparator.LESS_THAN, lastKnownAffectedRange.replaceFirst("<", "").trim());
            }
        }

        final Vers vers = versBuilder.build();

        // >=0 is equivalent to *
        if (vers.constraints().size() == 1
            && Comparator.GREATER_THAN_OR_EQUAL == vers.constraints().get(0).comparator()
            && "0".equals(vers.constraints().get(0).version().toString())) {
            return Vers.builder(vers.scheme())
                    .withConstraint(Comparator.WILDCARD, null)
                    .build();
        }

        // >=0|<X is equivalent to <X
        // >=0|<=X is equivalent to <=X
        if (vers.constraints().size() == 2
            && Comparator.GREATER_THAN_OR_EQUAL == vers.constraints().get(0).comparator()
            && "0".equals(vers.constraints().get(0).version().toString())
            && Set.of(Comparator.LESS_THAN, Comparator.LESS_THAN_OR_EQUAL).contains(vers.constraints().get(1).comparator())) {
            return Vers.builder(vers.scheme())
                    .withConstraint(vers.constraints().get(1))
                    .build();
        }

        return vers;
    }

    /**
     * Convert ranges or exact version as used by NVD to a {@link Vers} range.
     *
     * @param versionStartExcluding The versionStartExcluding in the range
     * @param versionStartIncluding The versionStartIncluding in the range
     * @param versionEndExcluding   The versionEndExcluding in the range
     * @param versionEndIncluding   The versionEndIncluding in the range
     * @param exactVersion          The exact version in CpeMatch
     * @return An {@link Optional} containing the resulting {@link Vers}, or {@link Optional#empty()}
     * when no constraints could be inferred from the given parameters
     * @throws IllegalArgumentException When the provided cpe match is invalid,
     *                                  or the provided {@code events} contains an invalid event
     * @throws VersException            When the produced {@link Vers} is invalid
     * @throws InvalidVersionException  When any version in the range is invalid according to the inferred scheme
     */
    public static Optional<Vers> versFromNvdRange(
            final String versionStartExcluding,
            final String versionStartIncluding,
            final String versionEndExcluding,
            final String versionEndIncluding,
            final String exactVersion
    ) {

        // Using 'generic' as versioning scheme for NVD due to lack of package data.
        final var versBuilder = Vers.builder("generic");

        if (versionStartExcluding != null && !versionStartExcluding.isBlank()) {
            versBuilder.withConstraint(Comparator.GREATER_THAN, versionStartExcluding);
        }
        if (versionStartIncluding != null && !versionStartIncluding.isBlank()) {
            versBuilder.withConstraint(Comparator.GREATER_THAN_OR_EQUAL, versionStartIncluding);
        }
        if (versionEndExcluding != null && !versionEndExcluding.isBlank()) {
            versBuilder.withConstraint(Comparator.LESS_THAN, versionEndExcluding);
        }
        if (versionEndIncluding != null && !versionEndIncluding.isBlank()) {
            versBuilder.withConstraint(Comparator.LESS_THAN_OR_EQUAL, versionEndIncluding);
        }
        // If CpeMatch does not define a version range, but the CPE itself can
        // still give us the information we need. The version field can either be:
        //   * an exact version (e.g. "1.0.0")
        //   * a wildcard matching all versions ("*")
        //   * a "not applicable", matching no version at all ("-")
        if (!versBuilder.hasConstraints() && exactVersion != null) {
            if (!"*".equals(exactVersion) && !"-".equals(exactVersion)) {
                // If we have neither upper, nor lower bound, and the CPE version
                // is not a wildcard, only a specific version is vulnerable.
                versBuilder.withConstraint(Comparator.EQUAL, exactVersion);
            } else if ("*".equals(exactVersion)) {
                // If we have neither upper, nor lower bound, and the CPE version
                // is a wildcard, all versions are vulnerable, and we can safely use a vers wildcard.
                versBuilder.withConstraint(Comparator.WILDCARD, null);
            }
        }

        if (!versBuilder.hasConstraints()) {
            // NB: This happens when the CPE's version is NA ("-").
            return Optional.empty();
        }

        return Optional.of(versBuilder.build());
    }

    public static Optional<String> schemeFromGhsaEcosystem(final String ecosystem) {
        // Can be one of: actions, composer, erlang, go, maven, npm, nuget, other, pip, pub, rubygems, rust.
        return switch (ecosystem.toLowerCase()) {
            case "go" -> Optional.of(KnownVersioningSchemes.SCHEME_GOLANG);
            case "maven" -> Optional.of(KnownVersioningSchemes.SCHEME_MAVEN);
            case "npm" -> Optional.of(KnownVersioningSchemes.SCHEME_NPM);
            case "nuget" -> Optional.of(KnownVersioningSchemes.SCHEME_NUGET);
            case "pip" -> Optional.of(KnownVersioningSchemes.SCHEME_PYPI);
            case "rubygems" -> Optional.of(KnownVersioningSchemes.SCHEME_GEM);
            default -> Optional.empty();
        };
    }

    public static Optional<String> schemeFromOsvEcosystem(final String ecosystem) {
        // https://github.com/ossf/osv-schema/blob/main/docs/schema.md#affectedpackage-field

        // NB: Linux distros can have an optional ":<RELEASE>" suffix.
        if (ecosystem.startsWith("AlmaLinux")) {
            return Optional.of(KnownVersioningSchemes.SCHEME_RPM);
        } else if (ecosystem.startsWith("Alpine")) {
            return Optional.of(KnownVersioningSchemes.SCHEME_ALPINE);
        } else if (ecosystem.startsWith("Debian")) {
            return Optional.of(KnownVersioningSchemes.SCHEME_DEBIAN);
        } else if (ecosystem.startsWith("Mageia")) {
            return Optional.of(KnownVersioningSchemes.SCHEME_RPM);
        } else if (ecosystem.startsWith("Photon OS")) {
            return Optional.of(KnownVersioningSchemes.SCHEME_RPM);
        } else if (ecosystem.startsWith("Rocky Linux")) {
            return Optional.of(KnownVersioningSchemes.SCHEME_RPM);
        } else if (ecosystem.startsWith("Ubuntu")) {
            return Optional.of(KnownVersioningSchemes.SCHEME_DEBIAN);
        }

        return switch (ecosystem.toLowerCase()) {
            case "go" -> Optional.of(KnownVersioningSchemes.SCHEME_GOLANG);
            case "maven" -> Optional.of(KnownVersioningSchemes.SCHEME_MAVEN);
            case "npm" -> Optional.of(KnownVersioningSchemes.SCHEME_NPM);
            case "nuget" -> Optional.of(KnownVersioningSchemes.SCHEME_NUGET);
            case "pypi" -> Optional.of(KnownVersioningSchemes.SCHEME_PYPI);
            case "rubygems" -> Optional.of(KnownVersioningSchemes.SCHEME_GEM);
            default -> Optional.empty();
        };
    }

}
