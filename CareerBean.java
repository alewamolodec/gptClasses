package lms.core.person.career;

import hr.bonus.BonusMessage;
import hr.vacancy.VacancyMessage;
import hr.vacancy.vv.workschedule.WorkScheduleRubricator;
import lms.core.ca.CAFrame;
import lms.core.ca.post.PostFrame;
import lms.core.ca.post.PostMessage;
import lms.core.person.PersonLookupFieldBuilder;
import lms.core.person.PersonMessage;
import lms.core.person.vv.ContractKindRubricator;
import lms.core.person.vv.EmploymentKindRubricator;
import lms.core.person.vv.GroundForDismissalsRubricator;
import lms.core.person.vv.PostRubricator;
import lms.core.person.work.PersonWorkBean;
import lms.core.person.work.PersonWorkFrame;
import mira.vv.VVMessage;
import mira.vv.rubricator.field.RSComboField;
import mira.vv.rubricator.field.RSField;
import mira.vv.rubricator.field.RSFieldBuilder;
import mira.vv.rubs.schedule.CustomScheduleFrame;
import org.mirapolis.data.bean.NameBean;
import org.mirapolis.data.bean.reflect.Name;
import org.mirapolis.data.bean.reflect.ReflectDataBean;
import org.mirapolis.exception.CoreException;
import org.mirapolis.mvc.model.entity.datafields.LookupField;
import org.mirapolis.mvc.model.entity.fields.CheckFieldBuilder;
import org.mirapolis.mvc.model.entity.fields.MemoFieldBuilder;
import org.mirapolis.mvc.view.clientscript.builders.CheckExpressionBuilder;
import org.mirapolis.mvc.view.clientscript.expressions.ActionClientCallExpression;
import org.mirapolis.orm.DataObject;
import org.mirapolis.orm.EntityManager;
import org.mirapolis.orm.fields.*;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.NotNull;
import java.util.Comparator;
import java.util.Date;

import static org.mirapolis.util.DateHelper.isNotNull;
import static org.mirapolis.util.DateHelper.isNull;
import static org.mirapolis.util.StringHelper.isEmpty;

/**
 * Запись карьеры
 *
 * @author artem
 */
public class CareerBean extends ReflectDataBean {

	public static final String DATANAME = "PP$CAREER";

	public static final String ID = "pcid";
	public static final String WORK_ID = PersonWorkBean.ID;
	public static final String CA_ID = "caid";
	public static final String CA_POST_ID = "capostid";
	public static final String POST_ID = "rspostid";
	public static final String EMPLOYMENT_DATE = "pcemploymentdate";
	public static final String DISMISSAL_DATE = "pcdismissaldate";
    public static final String DISMISSAL_REASON = "pcdismissalreason";
    public static final String ADDITIONAL_INFO = "pcadditionalinfo";
    public static final String ORDER_NUM = "pcordernum";
	public static final String GROUND_FOR_DISMISSALS = "groundfordismissals";
	public static final String CONTRACT_KIND = "pwcontracttype";
	public static final String EMPLOYMENT_KIND = "pwemploymenttype";
	public static final String SALARY_RATE = "pwsalaryrate";
	public static final String SCHEDULE = "newpschedule";

	public static final String CONTRACT_NUMBER = "pwcontractnumber";
	public static final String TAB_NUMBER = "pwtabnumber";
	public static final String CONTRACT_DATE = "pwcontractdate";
	public static final String FIXED_TERM_CONTRACT = "pwfixedtermcontract";
	public static final String WORK_SCHEDULE = "pwworkschedule";
	public static final String CONTRACT_TERM = "pwcontractterm";
	public static final String CODE = "pccode";
	static final String TRANSFER_ENDING_TYPE = "pctransferendtype";

	//Hidden
	public static final String NEW_POST_ID = "newrsid";
	public static final String NEW_CA_ID = "newcaid";
	public static final String IS_SAVE_PERSON = "issaveperson";

	@Name(ID)
	private String id;
	@Name(WORK_ID)
	private String workId;
	@Name(CA_ID)
	@NotNull
	private NameBean ca;
	@Name(CA_POST_ID)
	private NameBean caPost;
	@Name(POST_ID)
	private NameBean post;
	/**
     * Дата начала
     */
	@Name(EMPLOYMENT_DATE)
	@NotNull
	private Date employmentDate;
    /**
     * Дата увольнения/перевода
     */
	@Name(DISMISSAL_DATE)
	private Date dismissalDate;
    /**
     * Причина увольнения/перевода
     */
	@Name(DISMISSAL_REASON)
	private String dismissalReason;
    /**
     * Дополнительная информация
     */
	@Name(ADDITIONAL_INFO)
	private String additionalInfo;
    /**
     * Номер приказа
     */
	@Name(ORDER_NUM)
	private String orderNum;

	//Для логики
	@Name(NEW_CA_ID)
	private NameBean newCa;
	@Name(NEW_POST_ID)
	private NameBean newPost;
	@Name(IS_SAVE_PERSON)
	private Boolean isSavePerson;
	@Name(GROUND_FOR_DISMISSALS)
	private NameBean groundForDismissals;
	/**
	 * Табельный номер
	 */
	@Name(TAB_NUMBER)
	private String tabNumber;
	/**
	 * Номер договора
	 */
	@Name(CONTRACT_NUMBER)
	private String contractNumber;
	/**
	 * Дата договора
	 */
	@Name(CONTRACT_DATE)
	private Date contractDate;
	/**
	 * Срочный трудовой договор
	 */
	@Name(FIXED_TERM_CONTRACT)
	private Boolean fixedTermContract;
	/**
	 * График работы
	 */
	@Name(WORK_SCHEDULE)
	private NameBean workSchedule;
	/**
	 * Срок договора
	 */
	@Name(CONTRACT_TERM)
	private Date contractTerm;
	/**
	 * Вид договора
	 */
	@Name(CONTRACT_KIND)
	private NameBean contractKind;
	/**
	 * Вид занятости сотрудника
	 */
	@Name(EMPLOYMENT_KIND)
	private NameBean employmentKind;
	/**
	 * Размер ставки
	 */
	@Name(SALARY_RATE)
	private Double salaryRate;
	/**
	 * График работы
	 */
	@Name(SCHEDULE)
	private NameBean schedule;
	/**
	 * Код
	 */
	@Name(CODE)
	private String code;
	/**
	 * Тип окончания кадрового перемещения
	 */
	@Name(TRANSFER_ENDING_TYPE)
	private CareerTransferEndingType careerTransferEndingType;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public Date getEmploymentDate() {
		return employmentDate;
	}

	public void setEmploymentDate(Date employmentDate) {
		this.employmentDate = employmentDate;
	}

	public Date getDismissalDate() {
		return dismissalDate;
	}

	public void setDismissalDate(Date dismissalDate) {
		this.dismissalDate = dismissalDate;
	}

	public String getCaId() {
		return ca.getId();
	}

	public void setCaId(String caId) {
		ca.setId(caId);
	}

	public String getCaName() {
		return ca.getName();
	}

	public void setCaName(String caName) {
		ca.setName(caName);
	}

	public String getPostId() {
		return post.getId();
	}

	public void setPostId(String rsId) {
		post.setId(rsId);
	}

	public String getPostName() {
		return post.getName();
	}

	public void setPostName(String rsName) {
		post.setName(rsName);
	}

    public String getWorkId() {
        return workId;
    }

    public void setWorkId(String workId) {
        this.workId = workId;
    }

    public String getNewCaId() {
		return newCa.getId();
	}

	public void setNewCaId(String caId) {
		newCa.setId(caId);
	}

	public String getNewCaName() {
		return newCa.getName();
	}

	public void setNewCaName(String caName) {
		newCa.setName(caName);
	}

	public String getNewPostId() {
		return newPost.getId();
	}

	public void setNewPostId(String postId) {
		newPost.setId(postId);
	}

	public String getNewPostName() {
		return newPost.getName();
	}

	public void setNewPostName(String postName) {
		newPost.setName(postName);
	}

	public Boolean getIsSavePerson() {
		return isSavePerson == null || isSavePerson;
	}

	public void setIsSavePerson(Boolean isSavePerson) {
		this.isSavePerson = isSavePerson;
	}

	public String getDismissalReason() {
		return dismissalReason;
	}

	public void setDismissalReason(String dismissalReason) {
		this.dismissalReason = dismissalReason;
	}

	public Date getContractTerm() {
		return contractTerm;
	}

	public void setContractTerm(Date contractTerm) {
		this.contractTerm = contractTerm;
	}

	public String getAdditionalInfo() {
		return additionalInfo;
	}

	public void setAdditionalInfo(String additionalInfo) {
		this.additionalInfo = additionalInfo;
	}

	public String getOrderNum() {
		return orderNum;
	}

	public void setOrderNum(String orderNum) {
		this.orderNum = orderNum;
	}

	public NameBean getCa() {
		return ca;
	}

	public void setCa(NameBean ca) {
		this.ca = ca;
	}

	public NameBean getNewPost() {
		return newPost;
	}

	public void setNewPost(NameBean newPost) {
		this.newPost = newPost;
	}

	public NameBean getNewCa() {
		return newCa;
	}

	public void setNewCa(NameBean newCa) {
		this.newCa = newCa;
	}

	public NameBean getPost() {
		return post;
	}

	public void setPost(NameBean post) {
		this.post = post;
	}

	public NameBean getCaPost() {
		return caPost;
	}

	public void setCaPost(NameBean caPost) {
		this.caPost = caPost;
	}

	public void setCaPostId(String postId) {
		caPost.setId(postId);
	}

	public NameBean getGroundForDismissals() {
		return groundForDismissals;
	}

	public void setGroundForDismissals(NameBean groundForDismissals) {
		this.groundForDismissals = groundForDismissals;
	}

	public String getTabNumber() {
		return tabNumber;
	}

	public void setTabNumber(String tabNumber) {
		this.tabNumber = tabNumber;
	}

	public String getContractNumber() {
		return contractNumber;
	}

	public void setContractNumber(String contractNumber) {
		this.contractNumber = contractNumber;
	}

	public Date getContractDate() {
		return contractDate;
	}

	public void setContractDate(Date contractDate) {
		this.contractDate = contractDate;
	}

	public Boolean getFixedTermContract() {
		return fixedTermContract;
	}

	public void setFixedTermContract(Boolean fixedTermContract) {
		this.fixedTermContract = fixedTermContract;
	}

	public NameBean getWorkSchedule() {
		return workSchedule;
	}

	public void setWorkSchedule(NameBean workSchedule) {
		this.workSchedule = workSchedule;
	}

	public NameBean getContractKind() {
		return contractKind;
	}

	public CareerBean setContractKind(NameBean contractKind) {
		this.contractKind = contractKind;
		return this;
	}

	public NameBean getEmploymentKind() {
		return employmentKind;
	}

	public CareerBean setEmploymentKind(NameBean employmentKind) {
		this.employmentKind = employmentKind;
		return this;
	}

	public Double getSalaryRate() {
		return salaryRate;
	}

	public void setSalaryRate(Double salaryRate) {
		this.salaryRate = salaryRate;
	}

    public NameBean getSchedule() {
        return schedule;
    }

    public void setSchedule(NameBean schedule) {
		this.schedule = schedule;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public CareerTransferEndingType getCareerTransferEndingType() {
		return careerTransferEndingType;
	}

	public void setCareerTransferEndingType(CareerTransferEndingType careerTransferEndingType) {
		this.careerTransferEndingType = careerTransferEndingType;
	}

	@AssertTrue(message = "{lms.core.person.PersonMessage.no_date_of_dismissal_from_position}")
	public boolean hasOnlyOneCareerWithEmptyDismissalDate() {
		CareerBean filter = new CareerBean();
		filter.setWorkId(workId);
		return isNotNull(getDismissalDate())
			|| EntityManager.stream(filter).noneMatch(careerBean ->
			isNull(careerBean.getDismissalDate()) && (isEmpty(this.id) || !careerBean.id.equals(this.id))
		);
	}

	@AssertTrue(message = "{lms.core.person.PersonMessage.dismissal_date}")
	public boolean isHasDismissalReason() {
		return isEmpty(getDismissalReason()) || isNotNull(getDismissalDate());
	}

	@Override
	public String getDataName() {
		return DATANAME;
	}

	public static DataObject createDataObject() throws CoreException {
		return new DataObject(DATANAME, PersonMessage.career, CareerBean.class).setFields(
				new KeyField(ID),
				new PersonLookupFieldBuilder(new LookupField(WORK_ID, PersonMessage.aw_work, PersonWorkFrame.NAME))
						.getDataField(),
				new LookupField(CA_ID, VVMessage.ca, CAFrame.NAME, LookupField.RESTRICT),
				new RSField(POST_ID, PersonMessage.post, PostRubricator.PERSON_POST, LookupField.RESTRICT),
				new DateField(EMPLOYMENT_DATE, PersonMessage.beg_date).setIsName(),
				new DateField(DISMISSAL_DATE, PersonMessage.dismissal_date),
				new LookupField(NEW_CA_ID, PersonMessage.new_organization, CAFrame.NAME).setIsHidden(),
				new RSField(NEW_POST_ID, PersonMessage.new_post, PostRubricator.PERSON_POST, RSField.RESTRICT)
						.setIsHidden(),
				new StringField(DISMISSAL_REASON, PersonMessage.dismissal_reason),
				new MemoFieldBuilder(new StringField(ADDITIONAL_INFO, PersonMessage.career_additional_info, 2000))
						.getDataField(),
				new StringField(ORDER_NUM, PersonMessage.order_num),
				new LookupField(CA_POST_ID, PersonMessage.state_post, PostFrame.NAME, LookupField.RESTRICT),
				new RSFieldBuilder(new RSField(GROUND_FOR_DISMISSALS, PersonMessage.ground_of_dismissal,
						GroundForDismissalsRubricator.GROUND_FOR_DISMISSALS, FKField.SET_NULL))
						.setScriptOnChange(new ActionClientCallExpression(
								new ChooseGroundOfDismissalsUpdateComponentAction()))
						.getDataField(),
				new StringField(TAB_NUMBER, PersonMessage.tabnumber),
				new StringField(CONTRACT_NUMBER, PersonMessage.contractNumber),
				new DateField(CONTRACT_DATE, PersonMessage.contract_date),
				new CheckFieldBuilder(new CheckField(FIXED_TERM_CONTRACT, PersonMessage.fixedTermEmploymentContract))
						.setScript(CheckExpressionBuilder.createExpressionForShowFields(CONTRACT_TERM)).getDataField(),
				new DateField(CONTRACT_TERM, PersonMessage.contract_term),
				new RSComboField(WORK_SCHEDULE, BonusMessage.work_schedule_kind, WorkScheduleRubricator.SCHEDULE,
						RSComboField.SET_NULL),
				new RSComboField(CONTRACT_KIND, PersonMessage.contract_kind, ContractKindRubricator.CONTRACT_TYPE,
						RSComboField.SET_NULL),
				new RSComboField(EMPLOYMENT_KIND, PersonMessage.employmentKind, EmploymentKindRubricator.EMPLOYMENT_TYPE,
						RSComboField.SET_NULL),
				new DoubleField(SALARY_RATE, PostMessage.salary_rate),
				new LookupField(SCHEDULE, VacancyMessage.schedule, CustomScheduleFrame.NAME, LookupField.SET_NULL),
				new StringField(CODE, VVMessage.code),
				new ComboField(TRANSFER_ENDING_TYPE, PersonMessage.career_transfer_ending_type,
						CareerTransferEndingType.class)
		);
	}

    public static class ByStartDateDescComparator implements Comparator<CareerBean> {
        public int compare(CareerBean bean1, CareerBean bean2) {
            return bean2.getEmploymentDate().compareTo(bean1.getEmploymentDate());
        }
    }
}
