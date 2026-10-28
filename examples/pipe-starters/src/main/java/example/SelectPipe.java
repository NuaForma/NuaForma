package example;

import dev.nuaforma.contracts.*;
import java.util.List;
import java.util.Map;

/** Selects a JSON object member; an absent member deliberately returns null. */
public final class SelectPipe implements TemplatePipe {
    private static final TemplatePipeDefinition DEFINITION = new TemplatePipeDefinition(
        "select", "Select an object member", List.of(
            new OptionDefinition("key", OptionType.STRING, "Member name", true)), "Null stays null; absent members return null");

    @Override public TemplatePipeDefinition getDefinition() { return DEFINITION; }

    @Override public Object apply(Object value, List<? extends Object> arguments, TemplatePipeContext context) throws GenerationException {
        context.checkActive();
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> object) || !(arguments.get(0) instanceof String key) || key.length() > 256) {
            throw new IllegalArgumentException("Expected a JSON object and bounded member name");
        }
        return object.get(key);
    }
}
