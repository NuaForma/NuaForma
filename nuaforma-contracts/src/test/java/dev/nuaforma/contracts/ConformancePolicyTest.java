package dev.nuaforma.contracts;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConformancePolicyTest {
    @Test void optionalMetadataIsImmutableAndDoesNotChangeExistingContracts() {
        var profiles = new HashSet<>(Set.of("pdfa-1b", "pdfa-2b", "pdfa-3b"));
        var policy = new ConformancePolicy(DocumentFormat.PDF, profiles, false);
        profiles.clear();
        assertEquals(3, policy.getProfiles().size());
        assertFalse(policy.getValidationEnabled());
        assertEquals(DocumentFormat.PDF, policy.getOutputFormat());
        assertThrows(UnsupportedOperationException.class, () -> policy.getProfiles().clear());
        ConformancePolicyProvider provider = () -> List.of(policy);
        assertSame(policy, provider.conformancePolicies().get(0));
        assertFalse(ConformancePolicyProvider.class.isAssignableFrom(DocumentProvider.class));
        assertDoesNotThrow(() -> new ProviderCapabilities(Set.of(DocumentFormat.DOCX), Set.of(DocumentFormat.PDF)));
        for (var invalid : List.of(Set.<String>of(), Set.of(""), Set.of("PDF/A-3B"), Set.of("private\nvalue"))) {
            assertThrows(IllegalArgumentException.class, () -> new ConformancePolicy(DocumentFormat.PDF, invalid, true));
        }
    }
}
