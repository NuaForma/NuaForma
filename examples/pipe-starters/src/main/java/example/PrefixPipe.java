package example;

import dev.nuaforma.contracts.*;
import java.util.List;

/** Stateless, bounded string prefix with an explicit argument. */
public final class PrefixPipe implements TemplatePipe {
    private static final TemplatePipeDefinition DEFINITION = new TemplatePipeDefinition(
        "prefix", "Add a prefix to a string", List.of(
            new OptionDefinition("prefix", OptionType.STRING, "Text prefix", true)), "Null stays null");

    @Override public TemplatePipeDefinition getDefinition() { return DEFINITION; }

    @Override public Object apply(Object value, List<? extends Object> arguments, TemplatePipeContext context) throws GenerationException {
        context.checkActive();
        if (value == null) return null;
        if (!(value instanceof String text) || !(arguments.get(0) instanceof String prefix)
            || (long) text.length() + prefix.length() > 4096) {
            throw new IllegalArgumentException("Expected bounded string input and prefix");
        }
        return prefix + text;
    }
}
