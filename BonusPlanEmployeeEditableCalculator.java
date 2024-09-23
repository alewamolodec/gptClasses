package hr.bonus.plan.employee.task;

import hr.bonus.plan.employee.BonusPlanEmployeeBean;
import hr.bonus.plan.employee.BonusPlanEmployeeParentCABean;
import org.mirapolis.data.bean.BeanHelper;
import org.mirapolis.mvc.model.entity.EntityListenerService;
import org.mirapolis.orm.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * Рассчитывает и сохраняет все изменения, которые должны произойти с участником плана премирования
 * Изменяет участника плана
 *
 * @author Zlata Zanina
 * @since 22.10.2018
 */
public class BonusPlanEmployeeEditableCalculator extends BonusPlanEmployeeCalculator {

    @Autowired
    private EntityListenerService entityListenerService;

    public BonusPlanEmployeeEditableCalculator() {
    }

    @Override
    protected void processAfterCalculatingBaseFields() {
        saveEmployee();
    }

    @Override
    protected void processEmployeeCaTypeDivisions(
            List<BonusPlanEmployeeParentCABean> oldValues, List<BonusPlanEmployeeParentCABean> newValues) {
        EntityManager.delete(BonusPlanEmployeeParentCABean.class,
                BeanHelper.getValueSet(oldValues, BonusPlanEmployeeParentCABean.ID));
        newValues.forEach(EntityManager::insert);
    }

    @Override
    protected void processAfterCalculatingBonuses() {
        applyKindCauseChangesAndSave();
    }

    @Override
    protected void processAfterCalculatingBonusDifference() {
        applyKindCauseChangesAndSave();
    }

    private void applyKindCauseChangesAndSave() {
        data.applyKindColCauseChanges(data.employeeBean);
        saveEmployee();
    }

    protected void saveEmployee() {
        String employeeId = entityListenerService.getSaveListener(BonusPlanEmployeeBean.class)
                .save(data.employeeBean)
                .getId();
        data.employeeBean = EntityManager.find(employeeId, BonusPlanEmployeeBean.class);
    }

    @Override
    protected BonusPlanEmployeeKPICalculator createKpiCalculator() {
        return kpiCalculatorCreator.createEditableCalculator(commonData, data);
    }

    @Override
    protected BonusPlanEmployeeKindColumnValueCalculator createKindColumnCalculator() {
        return new BonusPlanEmployeeEditableKindColumnValueCalculator(commonData, data);
    }
}
