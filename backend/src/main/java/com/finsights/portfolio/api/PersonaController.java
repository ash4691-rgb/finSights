package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.PersonaRequest;
import com.finsights.portfolio.dto.PersonaResponse;
import com.finsights.portfolio.service.PersonaService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/persona")
public class PersonaController {
    private final PersonaService persona;

    public PersonaController(PersonaService persona) {
        this.persona = persona;
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
}
