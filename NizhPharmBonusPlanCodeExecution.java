package ext.terralink.hr.bonus.plan;

import hr.bonus.plan.employee.BonusPlanEmployeeBean;
import hr.bonus.plan.employee.task.*;
import org.mirapolis.log.Log;
import org.mirapolis.log.LogFactory;

import java.util.HashSet;
import java.util.Set;

public class NizhPharmBonusPlanCodeExecution extends BonusPlanEmployeeCalculatorTemplate {
    private static final Log log = LogFactory.getLog(NizhPharmBonusPlanCodeExecution.class);

//    private static final RuntimeField<BonusPlanEmployeeBean, String> EMPLOYEE_NAME_RUNTIME = new RuntimeField<>(
//            new StringField("bnspefullname").setLength(500),
//            BonusPlanEmployeeBean.class
//    );
//    private static final RuntimeField<BonusPlanEmployeeBean, Integer> PLANNED_RISE_CODE_RUNTIME =
//            new RuntimeField<>(new DoubleField("userfield8bbc5878f3dc4f30a4ef"), BonusPlanEmployeeBean.class);
//    private static final RuntimeField<BonusPlanEmployeeBean, Integer> PLANNED_RISE_MANUAL_RUNTIME =
//            new RuntimeField<>(new IntegerField("userfield43b139384282469ebe73"), BonusPlanEmployeeBean.class);
//    private static final RuntimeField<BonusPlanEmployeeBean, Integer> HIRE_DATE_RUNTIME =
//            new RuntimeField<>(new IntegerField("userfield0570b88475bf4b3ca5e9"), BonusPlanEmployeeBean.class);
//    private static final RuntimeField<BonusPlanEmployeeBean, Integer> TO_BE_UP_SALARY_RUNTIME =
//            new RuntimeField<>(new DoubleField("userfield377a620d159c41909d63"), BonusPlanEmployeeBean.class);
//    private static final RuntimeField<BonusPlanEmployeeBean, Integer> TO_BE_UP_REVENUE_RUNTIME =
//            new RuntimeField<>(new DoubleField("userfield589d9838d67c4b67ae8e"), BonusPlanEmployeeBean.class);

    @Override
    protected BonusPlanEmployeeCalculator createNonEditableEmployeeCalculator() {
        return null;
    }

    @Override
    protected BonusPlanEmployeeEditableCalculator createEditableEmployeeCalculator() {
        boolean ignoreColumnsFilledByCode = isIgnoreColumnsFilledByCode();
        BonusPlanEmployeeEditableCalculator editableCalculator = new BonusPlanEmployeeEditableCalculator() {
            @Override
            protected BonusPlanEmployeeKindColumnValueCalculator createKindColumnCalculator() {
                BonusPlanEmployeeEditableKindColumnValueCalculator kindCalculator =
                        new BonusPlanEmployeeEditableKindColumnValueCalculator(commonData, data);
                if (ignoreColumnsFilledByCode) {
                    kindCalculator.ignoreColumnsFilledByCode();
                }
                kindCalculator.setPlanEmployeeFieldIdsToCalculate(getPlanEmployeeFieldIdsToCalculate());
                return kindCalculator;
            }
        };
        if (ignoreColumnsFilledByCode) {
            editableCalculator.ignoreColumnsFilledByCode();
        }
        editableCalculator.setPlanEmployeeFieldIdsToCalculate(getPlanEmployeeFieldIdsToCalculate());
        calculateChanges();

        return editableCalculator;
    }

    @Override
    public Set<String> getPlanEmployeeFieldIdsToCalculate() {
        Set<String> planEmployeeFieldIdsToCalculate = new HashSet<>();
        planEmployeeFieldIdsToCalculate.add("userfield8bbc5878f3dc4f30a4ef");
        return planEmployeeFieldIdsToCalculate;
    }

    @Override
    public void calculateChanges() {
        setPlannedRiseCode();
    }

    private void setPlannedRiseCode() {
        BonusPlanEmployeeBean employee = data.getEmployeeBean();
        if (employee.getFieldValueByName("userfield43b139384282469ebe73").equals("Нет") &&
                employee.getFieldValueByName("userfield94252376592e4aa7b3cd").toString().isEmpty() &&
                Integer.parseInt(employee.getFieldValueByName("userfielde79d2dfbd97346c1b3c3").toString()) > 0 &&
                Integer.parseInt(employee.getFieldValueByName("userfield18502b6ef4f4446ab3d2").toString()) > 0) {
            employee.setFieldValueByName("userfield8bbc5878f3dc4f30a4ef", 1549);
        } else {
            employee.setFieldValueByName("userfield8bbc5878f3dc4f30a4ef", 1548);
        }
        data.getEmployeeBean().setFieldValueByName("userfield8bbc5878f3dc4f30a4ef", 1548);
        saveChanges();
    }
}
