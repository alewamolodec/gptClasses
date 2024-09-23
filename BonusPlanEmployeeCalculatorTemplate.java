package hr.bonus.plan.employee.task;

/**
 * Нужно наследоваться от этого класса при создании выполняемого кода для вида премии
 *
 * @author Zlata Zanina
 * @since 10.01.2019
 */
public abstract class BonusPlanEmployeeCalculatorTemplate extends BonusPlanEmployeeCalculator {

    protected BonusPlanEmployeeCalculator properCalculator;

    @Override
    public void calculateChanges() {
        validateData();
        properCalculator = createProperCalculator();
        properCalculator.calculateChanges();
        properCalculator.getErrors().forEach(this::addError);
        data.setEmployeeBean(properCalculator.getEmployeeBean());
    }

    @Override
    public void saveChanges() {
        properCalculator.saveChanges();
    }

    private BonusPlanEmployeeCalculator createProperCalculator() {
        BonusPlanEmployeeCalculator calculator = data.isChangeable() ?
                createEditableEmployeeCalculator() :
                createNonEditableEmployeeCalculator();
        calculator.initialize(commonData, data, fieldFiller, kpiCalculatorCreator, statusCalculator);
        return calculator;
    }

    /**
     * @return калькулятор для участников, которых нельзя изменять
     */
    protected abstract BonusPlanEmployeeCalculator createNonEditableEmployeeCalculator();

    /**
     * @return калькулятор для участников, которых можно изменять
     */
    protected abstract BonusPlanEmployeeEditableCalculator createEditableEmployeeCalculator();
}
