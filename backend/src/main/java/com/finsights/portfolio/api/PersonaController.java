package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.PersonaDetailsRequest;
import com.finsights.portfolio.dto.PersonaRequest;
import com.finsights.portfolio.dto.PersonaResponse;
import com.finsights.portfolio.dto.PersonaRiskRequest;
import com.finsights.portfolio.service.PersonaService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/persona")
public class PersonaController {
    private final PersonaService persona;

    public PersonaController(PersonaService persona) {
        this.persona = persona;
    }

    // 204 (no body) when the user hasn't completed or skipped the questionnaire yet — the
    // Settings page's persona/risk display treats that as "nothing to show".
    @GetMapping
    ResponseEntity<PersonaResponse> current() {
        return persona.current().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping
    PersonaResponse submit(@RequestBody PersonaRequest request) {
        return persona.submit(request);
    }

    @PostMapping("/skip")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void skip() {
        persona.skip();
    }

    // Settings' inline edit for age/occupation/salary/investing-since — never touches
    // riskProfile/investorPersona/instrumentTypes. See PersonaService.updateDetails.
    @PutMapping("/details")
    PersonaResponse updateDetails(@RequestBody PersonaDetailsRequest request) {
        return persona.updateDetails(request);
    }

    // The five-question risk assessment on its own — shown on a later login once the basic
    // onboarding is done, or re-run anytime from Settings' "Reassess risk profile". See
    // PersonaService.updateRisk.
    @PutMapping("/risk")
    PersonaResponse updateRisk(@RequestBody PersonaRiskRequest request) {
        return persona.updateRisk(request);
    }
}
