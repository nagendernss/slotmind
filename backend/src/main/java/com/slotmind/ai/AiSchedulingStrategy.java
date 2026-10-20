package com.slotmind.ai;

import com.slotmind.domain.Provider;
import com.slotmind.domain.Slot;
import com.slotmind.scheduling.SchedulingContext;
import com.slotmind.scheduling.SchedulingStrategy;
import com.slotmind.scheduling.SlotRecommendation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** LLM-backed strategy (key "AI"). Throws {@link LlmException} on any failure so the engine falls back to rule-based. */
@Component
public class AiSchedulingStrategy implements SchedulingStrategy {
    private static final Logger log = LoggerFactory.getLogger(AiSchedulingStrategy.class);
    private static final Pattern INTERNAL_ID = Pattern.compile("\\b(SLT|PRV|APT|RM|CLN)-\\d+", Pattern.CASE_INSENSITIVE);

    private final LlmGateway gateway;
    private final PromptFactory prompts;
    private final AiResponseParser parser;

    public AiSchedulingStrategy(LlmGateway gateway, PromptFactory prompts, AiResponseParser parser) {
        this.gateway = gateway;
        this.prompts = prompts;
        this.parser = parser;
    }

    @Override
    public String key() { return "AI"; }

    @Override
    public List<SlotRecommendation> recommend(SchedulingContext ctx) {
        if (ctx.candidates() == null || ctx.candidates().isEmpty()) return List.of();
        PromptFactory.Prompts p = prompts.build(ctx);
        long t0 = System.currentTimeMillis();
        String raw = gateway.callLLM(p.system(), p.user());
        log.info("AI strategy: LLM answered in {} ms for appointment {}", System.currentTimeMillis() - t0,
                ctx.appointment().getId());
        SlotRecommendation rec = parser.parse(raw);

        Optional<Slot> chosen = ctx.candidates().stream().filter(s -> s.getId().equals(rec.slotId())).findFirst();
        if (chosen.isEmpty()) throw new LlmException("LLM chose a slot that is not a candidate: " + rec.slotId());
        if (INTERNAL_ID.matcher(rec.reasoning()).find()) {
            throw new LlmException("LLM reasoning contains internal ids");
        }
        // Derive alternateProviderId from the chosen slot rather than trusting the model's bookkeeping.
        Provider slotProvider = chosen.get().getProvider();
        Provider original = ctx.appointment().getProvider();
        String alt = slotProvider != null && (original == null || !Objects.equals(slotProvider.getId(), original.getId()))
                ? slotProvider.getId() : null;
        return List.of(new SlotRecommendation(rec.slotId(), alt, rec.confidence(), rec.reasoning()));
    }
}
