package com.fanzuixianchang.core;
import org.junit.jupiter.api.Test;
class CoreChecksTest {
    @Test void coreBehaviors(){CoreChecks.main(new String[0]);}
    @Test void policyRegressions(){PolicyChecks.main(new String[0]);}
    @Test void professionalNutrition(){ProfessionalChecks.main(new String[0]);}
    @Test void foodIdentification(){IdentificationChecks.main(new String[0]);}
}
