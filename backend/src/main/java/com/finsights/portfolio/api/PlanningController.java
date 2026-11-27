package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.AllocationResponse;
import com.finsights.portfolio.dto.AllocationTargetsRequest;
import com.finsights.portfolio.dto.GoalRequest;
import com.finsights.portfolio.dto.GoalResponse;
import com.finsights.portfolio.service.AllocationService;
import com.finsights.portfolio.service.GoalService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Target allocation with drift, and savings goals. Informational only: nothing here places a trade. */
@RestController
@RequestMapping("/api")
public class PlanningController {
    private final AllocationService allocation;
    private final GoalService goals;

    public PlanningController(AllocationService allocation, GoalService goals) {
        this.allocation = allocation;
        this.goals = goals;
    }

    @GetMapping("/allocation")
    AllocationResponse allocation(@RequestParam(required = false) String currency) {
        return allocation.allocation(currency);
    }

    @PutMapping("/allocation/targets")
    AllocationResponse saveTargets(@Valid @RequestBody AllocationTargetsRequest request,
                                   @RequestParam(required = false) String currency) {
        return allocation.saveTargets(request.targets(), currency);
    }

    @GetMapping("/goals")
    List<GoalResponse> goals() { return goals.list(); }

    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    GoalResponse createGoal(@Valid @RequestBody GoalRequest request) { return goals.create(request); }

    @PutMapping("/goals/{id}")
    GoalResponse updateGoal(@PathVariable String id, @Valid @RequestBody GoalRequest request) { return goals.update(id, request); }

    @DeleteMapping("/goals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteGoal(@PathVariable String id) { goals.delete(id); }
}
