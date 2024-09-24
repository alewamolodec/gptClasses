package ext.terralink.lms.core.person.career;

import ext.terralink.TerraMessage;
import ext.terralink.lms.core.ca.post.PostTerraBean;
import ext.terralink.lms.core.ca.post.rubricator.BonusPlanTerraRubricator;
import ext.terralink.lms.core.person.career.rubricator.EmployeeStatusTerraRubricator;
import ext.terralink.lms.core.person.career.rubricator.EventTerraRubricator;
import lms.core.newprocedure.ProcedureMessage;
import mira.event.EventMessage;
import mira.vv.rubricator.field.RSField;
import org.mirapolis.data.bean.NameBean;
import org.mirapolis.data.bean.reflect.Name;
import org.mirapolis.data.bean.reflect.ReflectDataBean;
import org.mirapolis.mvc.model.entity.datafields.LookupField;
import org.mirapolis.orm.DataObject;
import org.mirapolis.orm.fields.DoubleField;

/**
 * Доп поля для этапа карьеры
 *
 * @author Anatoliy Korbasov
 * @since 24.06.2024
 */
public class CareerTerraBean extends ReflectDataBean {
	public static final String DATANAME = "PP$CAREERTERRA";

	public static final String EXT_FIELD_NAME = "careerterra";

	public static final String EVENT = "event";
	public static final String EMPLOYEE_STATUS = "employeestatus";
	public static final String FINAL_SALARY = "finalsalary";
	public static final String BONUS_PLAN = PostTerraBean.POST_BONUS_PLAN;

	/**
	 * Событие
	 */
	@Name(EVENT)
	private NameBean event;
	/**
	 * Статус сотрудника
	 */
	@Name(EMPLOYEE_STATUS)
	private NameBean employeeStatus;
	/**
	 * Итоговый оклад
	 */
	@Name(FINAL_SALARY)
	private Double finalSalary;
	/**
	 * Бонусный план
	 */
	@Name(BONUS_PLAN)
	private NameBean postBonusPlan;

	public static DataObject createDataObject() {
		return new DataObject(DATANAME, TerraMessage.career_add_fields, CareerTerraBean.class)
			.setFields(
				new RSField(EVENT, EventMessage.event,
					EventTerraRubricator.NAME, LookupField.SET_NULL),
				new RSField(EMPLOYEE_STATUS, ProcedureMessage.status_employee,
					EmployeeStatusTerraRubricator.NAME, LookupField.SET_NULL),
				new DoubleField(FINAL_SALARY, TerraMessage.final_salary),
				new RSField(BONUS_PLAN, TerraMessage.post_bonus_plan,
					BonusPlanTerraRubricator.NAME, LookupField.SET_NULL)
			);
	}

	public NameBean getPostBonusPlan() {
		return postBonusPlan;
	}

	public void setPostBonusPlan(NameBean postBonusPlan) {
		this.postBonusPlan = postBonusPlan;
	}

	public NameBean getEvent() {
		return event;
	}

	public void setEvent(NameBean event) {
		this.event = event;
	}

	public NameBean getEmployeeStatus() {
		return employeeStatus;
	}

	public void setEmployeeStatus(NameBean employeeStatus) {
		this.employeeStatus = employeeStatus;
	}

	public Double getFinalSalary() {
		return finalSalary;
	}

	public void setFinalSalary(Double finalSalary) {
		this.finalSalary = finalSalary;
	}

	@Override
	public String getDataName() {
		return DATANAME;
	}
}
