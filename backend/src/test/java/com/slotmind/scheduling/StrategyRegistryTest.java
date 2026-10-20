package com.slotmind.scheduling;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyRegistryTest {

    static SchedulingStrategy named(String key) {
        return new SchedulingStrategy() {
            public String key() { return key; }
            public List<SlotRecommendation> recommend(SchedulingContext ctx) { return List.of(); }
            public String toString() { return "fake " + key; }
        };
    }

    private final SchedulingStrategy ai = named("AI");
    private final SchedulingStrategy rule = named("RULE_BASED");
    private final SchedulingStrategy room = named("ROOM_CONSTRAINT");
    private final SchedulingStrategy custom = named("CUSTOM");

    @Test
    void availableListsCanonicalKeysFirstThenExtras() {
        StrategyRegistry r = new StrategyRegistry(List.of(custom, room, rule, ai), "AI");
        assertThat(r.available()).containsExactly("AI", "RULE_BASED", "ROOM_CONSTRAINT", "CUSTOM");
    }

    @Test
    void availableOmitsMissingCanonicalKeys() {
        StrategyRegistry r = new StrategyRegistry(List.of(rule), "RULE_BASED");
        assertThat(r.available()).containsExactly("RULE_BASED");
        assertThat(r.find("AI")).isEmpty();
    }

    @Test
    void availableIsACopy() {
        StrategyRegistry r = new StrategyRegistry(List.of(rule), "RULE_BASED");
        r.available().add("HACK");
        assertThat(r.available()).containsExactly("RULE_BASED");
    }

    @Test
    void duplicateKeysKeepFirstBean() {
        SchedulingStrategy rule2 = named("RULE_BASED");
        StrategyRegistry r = new StrategyRegistry(List.of(rule, rule2), "RULE_BASED");
        assertThat(r.ruleBased()).isSameAs(rule);
        SchedulingStrategy custom2 = named("CUSTOM");
        StrategyRegistry r2 = new StrategyRegistry(List.of(custom, custom2), "CUSTOM");
        assertThat(r2.find("CUSTOM")).containsSame(custom);
    }

    @Test
    void findNormalisesKey() {
        StrategyRegistry r = new StrategyRegistry(List.of(ai, rule, room), "AI");
        assertThat(r.find("AI")).containsSame(ai);
        assertThat(r.find(" room_constraint ")).containsSame(room);
        assertThat(r.find("Rule_Based")).containsSame(rule);
        assertThat(r.find("NOPE")).isEmpty();
        assertThat(r.find(null)).isEmpty();
        assertThat(r.find("")).isEmpty();
    }

    @Test
    void ruleBasedLookup() {
        assertThat(new StrategyRegistry(List.of(ai, rule), "AI").ruleBased()).isSameAs(rule);
        assertThat(new StrategyRegistry(List.of(ai), "AI").ruleBased()).isNull();
    }

    @Test
    void configuredKeyIsNormalised() {
        assertThat(new StrategyRegistry(List.of(ai, rule), " rule_based ").activeKey()).isEqualTo("RULE_BASED");
        assertThat(new StrategyRegistry(List.of(ai, rule), "AI").activeKey()).isEqualTo("AI");
    }

    @Test
    void missingOrBlankConfiguredKeyDefaultsToAi() {
        assertThat(new StrategyRegistry(List.of(ai, rule), null).activeKey()).isEqualTo("AI");
        assertThat(new StrategyRegistry(List.of(ai, rule), "").activeKey()).isEqualTo("AI");
        assertThat(new StrategyRegistry(List.of(ai, rule), "   ").activeKey()).isEqualTo("AI");
    }

    @Test
    void unknownConfiguredKeyIsKeptSoEngineCanWarnAndFallBack() {
        StrategyRegistry r = new StrategyRegistry(List.of(ai, rule), "magic");
        assertThat(r.activeKey()).isEqualTo("MAGIC");
        assertThat(r.find(r.activeKey())).isEmpty();
    }

    @Test
    void runtimeSwitch() {
        StrategyRegistry r = new StrategyRegistry(List.of(ai, rule, room), "AI");
        r.setActive("room_constraint");
        assertThat(r.activeKey()).isEqualTo("ROOM_CONSTRAINT");
        assertThat(r.find(r.activeKey())).containsSame(room);
        r.setActive(" RULE_BASED");
        assertThat(r.activeKey()).isEqualTo("RULE_BASED");
    }

    @Test
    void setActiveRejectsUnknownAndKeepsPrevious() {
        StrategyRegistry r = new StrategyRegistry(List.of(ai, rule), "AI");
        assertThatThrownBy(() -> r.setActive("ROOM_CONSTRAINT")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown strategy 'ROOM_CONSTRAINT'").hasMessageContaining("[AI, RULE_BASED]");
        assertThatThrownBy(() -> r.setActive(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.setActive(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(r.activeKey()).isEqualTo("AI");
    }

    @Test
    void normalize() {
        assertThat(StrategyRegistry.normalize(null)).isNull();
        assertThat(StrategyRegistry.normalize("  ai ")).isEqualTo("AI");
        assertThat(StrategyRegistry.RULE_BASED).isEqualTo("RULE_BASED");
    }
}
