package com.finsights.portfolio.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Answers collected once, during persona onboarding — used to seed a few starter categories and
 * (eventually, maybe) to steer Goku. One per user; {@code usedDefaults} distinguishes a real,
 * completed questionnaire from the fallback saved when the user dismisses without finishing it.
 */
@Entity
@Table(name = "user_personas")
public class UserPersona {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private UserAccount user;
    private Integer age;
    @Column(length = 128)
    private String occupation;
    @Enumerated(EnumType.STRING)
    private SalaryRange salaryRange;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_persona_instrument_types", joinColumns = @JoinColumn(name = "persona_id"))
    @Enumerated(EnumType.STRING) @Column(name = "instrument_type")
    private Set<InstrumentType> instrumentTypes = new LinkedHashSet<>();
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private RiskProfile riskProfile;
    /** True when this record is the fallback saved on "don't show again" without completing the
     *  questionnaire — age/occupation/salaryRange/instrumentTypes stay unset in that case. */
    @Column(nullable = false)
    private boolean usedDefaults;
    private Instant completedAt = Instant.now();

    public UserPersona() { }

    public String getId() { return id; }
    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
    public String getOccupation() { return occupation; }
    public void setOccupation(String occupation) { this.occupation = occupation; }
    public SalaryRange getSalaryRange() { return salaryRange; }
    public void setSalaryRange(SalaryRange salaryRange) { this.salaryRange = salaryRange; }
    public Set<InstrumentType> getInstrumentTypes() { return instrumentTypes; }
    public void setInstrumentTypes(Set<InstrumentType> instrumentTypes) { this.instrumentTypes = instrumentTypes; }
    public RiskProfile getRiskProfile() { return riskProfile; }
    public void setRiskProfile(RiskProfile riskProfile) { this.riskProfile = riskProfile; }
    public boolean isUsedDefaults() { return usedDefaults; }
    public void setUsedDefaults(boolean usedDefaults) { this.usedDefaults = usedDefaults; }
    public Instant getCompletedAt() { return completedAt; }
}
