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
    /** Self-identified, e.g. "The Wealth Builder" — stays unset for the skip-with-defaults path,
     *  same as age/occupation/salaryRange. */
    @Enumerated(EnumType.STRING)
    private InvestorPersona investorPersona;
    @Enumerated(EnumType.STRING)
    private InvestingTenure investingTenure;
    /** What the user already holds, today — drives starter-category seeding (see
     *  PersonaService.seedCategories). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_persona_instrument_types", joinColumns = @JoinColumn(name = "persona_id"))
    @Enumerated(EnumType.STRING) @Column(name = "instrument_type")
    private Set<InstrumentType> instrumentTypes = new LinkedHashSet<>();
    /** What the user wants to start investing in — collected alongside instrumentTypes but purely
     *  informational for now; doesn't seed categories (nothing to track yet). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_persona_interested_instrument_types", joinColumns = @JoinColumn(name = "persona_id"))
    @Enumerated(EnumType.STRING) @Column(name = "instrument_type")
    private Set<InstrumentType> interestedInstrumentTypes = new LinkedHashSet<>();
    /** Free-text broker/platform names the user says they use — same open-ended shape as
     *  Holding.broker, not a closed enum, since any brokerage is a valid answer. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_persona_platforms", joinColumns = @JoinColumn(name = "persona_id"))
    @Column(name = "platform", length = 96)
    private Set<String> platforms = new LinkedHashSet<>();
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
    public InvestorPersona getInvestorPersona() { return investorPersona; }
    public void setInvestorPersona(InvestorPersona investorPersona) { this.investorPersona = investorPersona; }
    public InvestingTenure getInvestingTenure() { return investingTenure; }
    public void setInvestingTenure(InvestingTenure investingTenure) { this.investingTenure = investingTenure; }
    public Set<InstrumentType> getInstrumentTypes() { return instrumentTypes; }
    public void setInstrumentTypes(Set<InstrumentType> instrumentTypes) { this.instrumentTypes = instrumentTypes; }
    public Set<InstrumentType> getInterestedInstrumentTypes() { return interestedInstrumentTypes; }
    public void setInterestedInstrumentTypes(Set<InstrumentType> interestedInstrumentTypes) { this.interestedInstrumentTypes = interestedInstrumentTypes; }
    public Set<String> getPlatforms() { return platforms; }
    public void setPlatforms(Set<String> platforms) { this.platforms = platforms; }
    public RiskProfile getRiskProfile() { return riskProfile; }
    public void setRiskProfile(RiskProfile riskProfile) { this.riskProfile = riskProfile; }
    public boolean isUsedDefaults() { return usedDefaults; }
    public void setUsedDefaults(boolean usedDefaults) { this.usedDefaults = usedDefaults; }
    public Instant getCompletedAt() { return completedAt; }
}
