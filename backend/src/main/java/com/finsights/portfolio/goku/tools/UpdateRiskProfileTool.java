package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.dto.PersonaRiskRequest;
import com.finsights.portfolio.goku.GokuWriteTool;
import com.finsights.portfolio.service.PersonaService;
import jakarta.validation.Validator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Platform-owned write — {@link PersonaService#updateRisk}, the same five-question assessment as
 * Settings' "Reassess risk profile". The questions and options below mirror risk-assessment.tsx
 * exactly (answer = option index), so the score means the same whichever way it was taken.
 */
@Component
class UpdateRiskProfileTool implements GokuWriteTool {
    static final List<Question> QUESTIONS = List.of(
            new Question("timeHorizonAnswer", "When do you expect to start withdrawing a major portion of your investments?",
                    List.of("Under 3 years", "3–5 years", "6–10 years", "More than 10 years")),
            new Question("riskCapacityAnswer", "Over the next few years, how do you expect your annual income to change?",
                    List.of("Decrease substantially", "Decrease moderately", "Stay the same", "Grow moderately", "Grow substantially")),
            new Question("riskToleranceAnswer", "If the performance of your investment dropped by 20% over a short period, how would you feel?",
                    List.of("Highly panicked and sell immediately", "Uneasy but hold", "View it as an opportunity to buy more")),
            new Question("investmentObjectivesAnswer", "Which statement best describes your overall investment philosophy?",
                    List.of("Seeking stable, low-risk capital preservation", "Balancing moderate growth and safety",
                            "Seeking high/aggressive capital growth despite major fluctuations")),
            new Question("liquidityNeedsAnswer",
                    "How much of your total portfolio might you need to access in an emergency within the next 12 months?",
                    List.of("None", "Less than 10%", "10%–30%", "More than 30%")));

    record Question(String field, String text, List<String> options) {}

    private final PersonaService persona;
    private final ObjectMapper json;
    private final Validator validator;

    UpdateRiskProfileTool(PersonaService persona, ObjectMapper sharedJson, Validator validator) {
        this.persona = persona;
        this.json = sharedJson;
        this.validator = validator;
    }

    @Override public String name() { return "update_risk_profile"; }

    @Override public String description() {
        StringBuilder text = new StringBuilder("Re-runs the signed-in user's five-question risk assessment and recomputes their "
                + "risk profile. Ask the user each question below, one at a time, offering exactly its listed options; "
                + "never answer for them. Each answer is the 0-based index of the option they picked.");
        for (Question q : QUESTIONS) {
            text.append("\n- ").append(q.field()).append(": ").append(q.text()).append(" Options: ");
            for (int i = 0; i < q.options().size(); i++) text.append(i == 0 ? "" : "; ").append(i).append("=").append(q.options().get(i));
        }
        return text.append("\nThis only proposes the change: the user confirms it with a button.").toString();
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        for (Question q : QUESTIONS) {
            props.put(q.field(), Map.of("type", "integer", "minimum", 0, "maximum", q.options().size() - 1));
        }
        return Map.of("type", "object", "properties", props, "required", QUESTIONS.stream().map(Question::field).toList());
    }

    @Override public String preview(JsonNode input) {
        GokuArgs.parseAndValidate(json, validator, input, PersonaRiskRequest.class);
        StringBuilder text = new StringBuilder("Recompute your risk profile from these answers:");
        for (Question q : QUESTIONS) {
            JsonNode answer = input.get(q.field());
            if (answer == null || !answer.canConvertToInt() || answer.asInt() < 0 || answer.asInt() >= q.options().size()) {
                throw new IllegalArgumentException(q.field() + " must be an option index from 0 to " + (q.options().size() - 1));
            }
            text.append(" ").append(q.options().get(answer.asInt())).append(";");
        }
        return text.substring(0, text.length() - 1);
    }

    @Override public Object apply(JsonNode input) {
        preview(input);
        return persona.updateRisk(GokuArgs.parseAndValidate(json, validator, input, PersonaRiskRequest.class));
    }
}
