package example;

import dev.nuaforma.contracts.*;
import java.util.List;

/** Uses the explicit generation locale instead of the machine's default locale. */
public final class UppercasePipe implements TemplatePipe {
    private static final TemplatePipeDefinition DEFINITION = new TemplatePipeDefinition(
        "uppercase", "Uppercase a string using the generation locale", List.of(), "Null stays null");

    @Override public TemplatePipeDefinition getDefinition() { return DEFINITION; }

    @Override public Object apply(Object value, List<? extends Object> arguments, TemplatePipeContext context) throws GenerationException {
        context.checkActive();
        if (value == null) return null;
        if (!(value instanceof String text) || text.length() > 4096) {
            throw new IllegalArgumentException("Expected a string of at most 4096 characters");
        }
        String result = text.toUpperCase(context.getLocale());
        if (result.length() > 4096) throw new IllegalArgumentException("Uppercase result exceeds 4096 characters");
        return result;
    }
}
