package example;

import dev.nuaforma.contracts.*;
import dev.nuaforma.template.Templates;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises ServiceLoader registration from the packaged JAR, not loose project output. */
class StarterTest {
    @Test void installedJarDiscoversAllThreePipesAndRendersTheExample() throws GenerationException {
        assertTrue(PrefixPipe.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar"));
        assertEquals("Customer: ADA", Templates.compile(
            "{customer | select(\"name\") | uppercase() | prefix(\"Customer: \")}")
            .render(Map.of("customer", Map.of("name", "Ada"))));
        assertEquals("", Templates.compile("{null | uppercase() | prefix(\"Hello \")}").render(Map.of()));
        assertEquals("", Templates.compile("{customer | select(\"absent\")}").render(Map.of("customer", Map.of())));
    }

    @Test void invalidTypesArgumentsAndOversizedValuesFail() {
        for (String source : List.of("{42 | uppercase()}", "{42 | prefix(\"A\")}",
            "{42 | select(\"name\")}", "{\"Ada\" | prefix()}", "{\"Ada\" | prefix(42)}")) {
            assertThrows(GenerationException.class, () -> Templates.compile(source).render(Map.of()), source);
        }
        assertThrows(GenerationException.class, () -> Templates.compile("{value | uppercase()}").render(Map.of("value", "x".repeat(4097))));
        assertThrows(GenerationException.class, () -> Templates.compile("{value | uppercase()}").render(Map.of("value", "ß".repeat(2049))));
        assertThrows(GenerationException.class, () -> Templates.compile("{value | prefix(\"a\")}").render(Map.of("value", "x".repeat(4096))));
        assertThrows(GenerationException.class, () -> Templates.compile("{value | select(key)}").render(Map.of("value", Map.of(), "key", "x".repeat(257))));
    }

    @Test void localeAndCancellationAreExplicit() throws GenerationException {
        assertEquals("İ", Templates.compile("{value | uppercase()}").render(Map.of("value", "i"),
            new GenerationOptions(null, Locale.forLanguageTag("tr-TR")), new GenerationContext()));
        var context = new TemplatePipeContext(Locale.ROOT, java.time.ZoneId.of("UTC"),
            new GenerationContext(new GenerationLimits(), () -> true));
        assertThrows(GenerationException.class, () -> new UppercasePipe().apply("Ada", List.of(), context));
    }

    @Test void concurrentRendersKeepRequestDataSeparate() throws Exception {
        var compiled = Templates.compile("{name | uppercase() | prefix(\"Hello \")}");
        var executor = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Callable<String>>();
            for (int i = 0; i < 24; i++) { int id = i; jobs.add(() -> compiled.render(Map.of("name", "user" + id))); }
            var results = executor.invokeAll(jobs);
            for (int i = 0; i < results.size(); i++) assertEquals("Hello USER" + i, results.get(i).get());
        } finally { executor.shutdownNow(); }
    }
}
