package lms.core.person;

import hr.development.DevelopmentMessage;
import hr.development.DevelopmentModule;
import lms.core.account.AccountFrame;
import lms.core.account.AccountMessage;
import lms.core.account.AccountModule;
import lms.core.ca.post.PostMessage;
import lms.core.newprocedure.ProcedureMessage;
import lms.core.newprocedure.ProcedureModule;
import lms.core.newprocedure.kpi.catalog.profile.ProfileKPIFrame;
import lms.core.person.adaptationteam.PersonAdaptationTeamBean;
import lms.core.person.contact.PersonContactBean;
import lms.core.person.location.PersonLocationDeterminationType;
import lms.core.person.profile.PersonBlogVisibilityLevel;
import lms.core.person.profile.PersonProfileBean;
import lms.core.person.tutor.PersonTutorBean;
import lms.core.person.vv.PersonCategoryRubImpl;
import lms.core.person.vv.PersonTypeRubricator;
import lms.core.person.vv.location.PersonLocationRubricator;
import lms.core.person.work.PersonManagerResolvMethod;
import lms.core.person.work.PersonWorkBean;
import lms.core.qua.QuaMessage;
import lms.core.qua.QuaModule;
import lms.core.qua.person.PersonQuaBean;
import lms.core.qua.profile.PersonProfileType;
import lms.core.qua.profile.ProfileQuaFrame;
import lms.service.settings.SettingsModule;
import lms.system.main.Service;
import mira.vv.VVMessage;
import mira.vv.rubricator.field.RSField;
import org.hibernate.validator.constraints.NotEmpty;
import org.mirapolis.core.ModuleStore;
import org.mirapolis.core.SystemMessages;
import org.mirapolis.data.DataSet;
import org.mirapolis.data.bean.NameBean;
import org.mirapolis.data.bean.reflect.Name;
import org.mirapolis.data.bean.reflect.ReflectDataBean;
import org.mirapolis.exception.CoreException;
import org.mirapolis.mvc.model.entity.datafields.LookupField;
import org.mirapolis.mvc.model.entity.fields.ComboFieldBuilder;
import org.mirapolis.mvc.view.clientscript.builders.ComboBoxExpressionBuilder;
import org.mirapolis.orm.DataObject;
import org.mirapolis.orm.fields.*;
import org.mirapolis.user.UserData;
import org.mirapolis.util.CollectionUtils;
import org.mirapolis.util.StringHelper;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Данные пользователя
 */
public class PersonBean extends ReflectDataBean implements UserData, PersonExtension {

	public static final String DATANAME = "PP$PERSON";
	public static final String ALIAS = "PP";

	// 30+ точно таких же констант было создано в проекте. Не порядок.
	public static final String PERSON_NAME = "personname";

	public static final int FIO_LENGTH = 500;

	//PP$Person
	public static final String ID = "personid";
	public static final String LAST_NAME = "plastname";
	public static final String FIRST_NAME = "pfirstname";
	public static final String SUR_NAME = "psurname";
	public static final String FIO = "pfio";
	public static final String FIO_LAST_NAME_INTIALS = "pfiolastnameinitials";
	public static final String CATEGORY_ID = "categoryid";
	public static final String STATUS = "pstatus";
	public static final String TYPE = "typersid";
	public static final String AUTONUMBER = "autonumber";
	public static final String PROFILE_QUA_ID = "indprofilequaid";
	public static final String PROFILE_QUA_TYPE = "profilequatype";
    public static final String PROFILE_KPI_ID = "indprofilekpiid";
    public static final String PROFILE_KPI_TYPE = "profilekpitype";
    public static final String ALLOW_CREATE_PLAN_DEVELOPMENT = "pcreateplandev";
	public static final String CODE_EXTERNAL_SYSTEM = "pextcode";
	public static final String ON_MATERNITY_LEAVE = "ponmaternityleave";
	public static final String ACTUAL_LOCATION_DET_TYPE = "actlocationdettype";
	public static final String ACTUAL_LOCATION = "actlocation";

	//PP$PERSONMAINWORK
	public static final String PERSON_MAIN_WORK = "pmainwork";
	//PP$PERSONCONTACT
	public static final String CONTACT_BEAN = "contactbean";
	//PP$PERSONPROFILE
	public static final String PROFILE = "personprofile";
	//PP$PERSONUSERDATA
	public static final String USERDATA = "userdata";
	//PP$PERSONPRIVATE
	public static final String PRIVATE = "personprivate";
    //PP$PERSONADAPTATIONTEAM
    public static final String ADAPTATION_TEAM = "adaptationteam";


	//Hidden
	public static final String QP_ID = "qpid";
	public static final String QC_ID = "qcid";
	public static final String ME_ID = "meid";
	public static final String DATA_ACCOUNT_ID = "daid";
	public static final String BALANCE = "dabalance";
	public static final String CONTANCT_VACANCY_ID = "contactvacancyid";
	public static final String TUTOR = "persontutor";

	private static final String HIDDEN = "Hidden";
	private static final String FIO_GIN_INDEX_NAME = "pfio_gin_idx";

	//indexes
	private static final String PMAINWORK_ID_INDEX = "pp$person_pmainwork_personid_idx";
	private static final String ID_PMAINWORK_FIO_INDEX = "pp$person_personid_pmainwork_pfio_idx";

	@Name(ID)
	private String id;
	@Name(LAST_NAME)
	private String lastName;
	@Name(FIRST_NAME)
	@NotEmpty
	private String firstName;
	@Name(SUR_NAME)  // отчество
	private String surName;
	/**
	 * Полное имя
	 */
	@Name(FIO)
	private String fio;
	/**
	 * Полное имя, в котором фамилия сокращена до инициала
	 */
	@Name(FIO_LAST_NAME_INTIALS)
	private String fioLastNameIntials;
	@Name(CATEGORY_ID)
	private NameBean category;
	@Name(STATUS)
	private PersonStatus status;
	@Name(AUTONUMBER)
	private String autonumber;
	@Name(TYPE)
	private NameBean type;
	/**
	 * Индивидуальный профиль компетенций
	 */
	@Name(PROFILE_QUA_ID)
	private NameBean profileQua;
	/**
	 * Тип профиля компетенций
	 */
	@Name(PROFILE_QUA_TYPE)
	private PersonProfileType profileQuaType;
    /**
     * Индивидуальный профиль kpi
     */
    @Name(PROFILE_KPI_ID)
    private NameBean profileKPI;
    /**
     * Тип профиля kpi
     */
    @Name(PROFILE_KPI_TYPE)
    private PersonProfileType profileKPIType;

	@Name(CONTACT_BEAN)
	private PersonContactBean contactBean;

	@Name(PERSON_MAIN_WORK)
	private PersonWorkBean mainWork;

	@Name(PROFILE)
	private PersonProfileBean profile;

	@Name(USERDATA)
	private PersonUserDataBean userData;

	@Name(PRIVATE)
	private PersonPrivateBean privateBean;

    @Name(ADAPTATION_TEAM)
    private PersonAdaptationTeamBean adaptationTeamBean;

	//Присвоенные компетенции
	private List<PersonQuaBean> quaList;
	@Name(QP_ID)
	private String qpId;
	@Name(QC_ID)
	private String qcId;
	@Name(ME_ID)
	private String meId;
	@Name(DATA_ACCOUNT_ID)
	private NameBean account;
	@Name(BALANCE)
	private Double balance;
	@Name(CONTANCT_VACANCY_ID)
	private String contactVacancyId;
    /**
     * Разрешить создавать планы развития
     */
    @Name(ALLOW_CREATE_PLAN_DEVELOPMENT)
    private Boolean allowCreatePlanDevelopment;
	/**
	 * Код внешней системы
	 */
	@Name(CODE_EXTERNAL_SYSTEM)
	private String codeExternalSystem;
	/**
	 * Находится в декретном отпуске
	 */
	@Name(ON_MATERNITY_LEAVE)
	private Boolean onMaternityLeave;

	@Name(TUTOR)
	private PersonTutorBean personTutorBean;
	@Name(ACTUAL_LOCATION_DET_TYPE)
	private PersonLocationDeterminationType personLocationDeterminationType;
	@Name(ACTUAL_LOCATION)
	private NameBean actualLocation;

	/**
	 * Список id ролей пользователя
	 * используется в web-сервисах
	 */
	private String roles;

	public NameBean getAccount() {
		return account;
	}

	public void setAccount(NameBean account) {
		this.account = account;
	}

	public Double getBalance() {
		return balance;
	}

	public void setBalance(Double balance) {
		this.balance = balance;
	}

	public PersonContactBean getContactBean() {
		return contactBean;
	}

	public void setContactBean(PersonContactBean contactBean) {
		this.contactBean = contactBean;
	}

	@Override
	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	@Override
	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	@Override
	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	@Override
	public String getSurName() {
		return surName;
	}

	public void setSurName(String surName) {
		this.surName = surName;
	}

	public String getNameWithoutSurname() {
		return PersonFullNameBuilder.create(this).withoutSurname().buildFullName();
	}

	@Override
	public String getLogin() {
		return userData.getLogin();
	}

	public String getPassword() {
		return userData.getPassword();
	}

	public String getClearPassword() {
		return userData.getClearPassword();
	}

	/**
	 * @return Возвращает последнюю локаль пользователя или локаль по-умолчанию из настроек, если у пользователя локаль пустая
	 */
	public Locale getLocale() {
		Locale locale = getUserData().getLocale();
		if (locale == null) {
			SettingsModule sm = ModuleStore.getInstance().getModule(SettingsModule.class);
			return sm.getDefaultLocale();
		} else {
			return locale;
		}
	}

	public String getPostRsid() {
		return getMainWork().getPostRs().getId();
	}

	public void setPostRsid(String postRsid) {
		getMainWork().getPostRs().setId(postRsid);
	}

	public NameBean getCa() {
		return getMainWork().getCa();
	}

	public void setCa(NameBean ca) {
		getMainWork().setCa(ca);
	}

	public String getCaId() {
		return getMainWork().getCa().getId();
	}

	public void setCaId(String caId) {
		getMainWork().getCa().setId(caId);
	}

	public String getCaName() {
		return getMainWork().getCa().getName();
	}

	public void setCaName(String caName) {
		getMainWork().getCa().setName(caName);
	}

	public NameBean getSpec() {
		return getMainWork().getSpec();
	}

	public void setSpec(NameBean spec) {
		getMainWork().setSpec(spec);
	}

	public String getSpecRsId() {
		return getMainWork().getSpec().getId();
	}

	public void setSpecRsId(String rsId) {
		getMainWork().getSpec().setId(rsId);
	}

	public String getSpecRsName() {
		return getMainWork().getSpec().getName();
	}

	public void setSpecRsName(String rsName) {
		getMainWork().getSpec().setName(rsName);
	}

	public String getPostName() {
		return getMainWork().getPostRs().getName();
	}

	public void setPostName(String postName) {
		getMainWork().getPostRs().setName(postName);
	}

	public PersonStatus getStatus() {
		return status;
	}

	public void setStatus(PersonStatus status) {
		this.status = status;
	}

	public NameBean getCategory() {
		return category;
	}

	public void setCategory(NameBean category) {
		this.category = category;
	}

	public String getQpId() {
		return qpId;
	}

	public void setQpId(String qpId) {
		this.qpId = qpId;
	}

	public String getQcId() {
		return qcId;
	}

	public void setQcId(String qcId) {
		this.qcId = qcId;
	}

	public String getCaPostId() {
		return getMainWork().getCaPost().getId();
	}

	public String getCaPostName() {
		return getMainWork().getCaPost().getName();
	}

	@Override
	public String getName() {
		return getFullName();
	}

	public String getMeId() {
		return meId;
	}

	public void setMeId(String meId) {
		this.meId = meId;
	}

	public PersonManagerResolvMethod getDirectorResolvMethod() {
		return getMainWork().getDirectorResolvMethod();
	}

	public void setDirectorResolvMethod(PersonManagerResolvMethod directorResolvMethod) {
		getMainWork().setDirectorResolvMethod(directorResolvMethod);
	}

	public PersonManagerResolvMethod getFuncDirectorResolvMethod() {
		return getMainWork().getFuncDirectorResolvMethod();
	}

	public void setFuncDirectorResolvMethod(PersonManagerResolvMethod funcDirectorResolvMethod) {
		getMainWork().setFuncDirectorResolvMethod(funcDirectorResolvMethod);
	}

	public NameBean getDirector() {
		return getMainWork().getDirector();
	}

	public void setDirector(NameBean director) {
		getMainWork().setDirector(director);
	}

	public NameBean getFuncDirector() {
		return getMainWork().getFuncDirector();
	}

	public void setFuncDirector(NameBean funcDirector) {
		getMainWork().setFuncDirector(funcDirector);
	}

	@Override
	public String getDataName() {
		return DATANAME;
	}

	public List<PersonQuaBean> getQuaList() {
		return quaList;
	}

	public void setQuaList(List<PersonQuaBean> quaList) {
		this.quaList = quaList;
	}

	public PersonWorkBean getMainWork() {
		return mainWork;
	}

	public void setMainWork(PersonWorkBean mainWork) {
		this.mainWork = mainWork;
	}

	public String getAutonumber() {
		return autonumber;
	}

	public void setAutonumber(String autonumber) {
		this.autonumber = autonumber;
	}

	public PersonProfileBean getProfile() {
		return profile;
	}

	public void setProfile(PersonProfileBean profile) {
		this.profile = profile;
	}

	public PersonUserDataBean getUserData() {
		return userData;
	}

	public void setUserData(PersonUserDataBean userData) {
		this.userData = userData;
	}

	public PersonPrivateBean getPrivateBean() {
		return privateBean;
	}

	public void setPrivateBean(PersonPrivateBean privateBean) {
		this.privateBean = privateBean;
	}

    public PersonAdaptationTeamBean getAdaptationTeamBean() {
        return adaptationTeamBean;
    }

    public void setAdaptationTeamBean(PersonAdaptationTeamBean adaptationTeamBean) {
        this.adaptationTeamBean = adaptationTeamBean;
    }

    public boolean isAnonym() {
		return PersonStatus.ANONYM.equals(status);
	}

    public NameBean getProfileKPI() {
        return profileKPI;
    }

    public void setProfileKPI(NameBean profileKPI) {
        this.profileKPI = profileKPI;
    }

    public PersonProfileType getProfileKPIType() {
        return profileKPIType;
    }

    public void setProfileKPIType(PersonProfileType profileKPIType) {
        this.profileKPIType = profileKPIType;
    }

    /**
	 * Активен пользователь или нет
	 */
	public boolean isActive() {
		return PersonStatus.ACTIVE.equals(status);
	}

    /**
     * Пользователь в архиве
     *
     * @return пользователь в архиве
     */
    public boolean isArchive() {
        return PersonStatus.ARCHIVE.equals(status);
    }

	public String getRoles() {
		return roles;
	}

	public void setRoles(String roles) {
		this.roles = roles;
	}

	public NameBean getType() {
		return type;
	}

	public void setType(NameBean type) {
		this.type = type;
	}

	public NameBean getProfileQua() {
		return profileQua;
	}

	public void setProfileQua(NameBean profileQua) {
		this.profileQua = profileQua;
	}

	public PersonProfileType getProfileQuaType() {
		return profileQuaType;
	}

	public void setProfileQuaType(PersonProfileType profileQuaType) {
		this.profileQuaType = profileQuaType;
	}

	public Boolean getIsUser() {
		if (userData != null) {
			return userData.getIsUser();
		}

		return false;
	}

	public String getContactVacancyId() {
		return contactVacancyId;
	}

	public void setContactVacancyId(String contactVacancyId) {
		this.contactVacancyId = contactVacancyId;
	}

    public Boolean getAllowCreatePlanDevelopment() {
        return allowCreatePlanDevelopment;
    }

    public void setAllowCreatePlanDevelopment(Boolean allowCreatePlanDevelopment) {
        this.allowCreatePlanDevelopment = allowCreatePlanDevelopment;
    }

	public String getCodeExternalSystem() {
		return codeExternalSystem;
	}

	public void setCodeExternalSystem(String codeExternalSystem) {
		this.codeExternalSystem = codeExternalSystem;
	}

	public Boolean getOnMaternityLeave() {
		return onMaternityLeave;
	}

	public void setOnMaternityLeave(Boolean onMaternityLeave) {
		this.onMaternityLeave = onMaternityLeave;
	}

	public PersonTutorBean getPersonTutorBean() {
		return personTutorBean;
	}

	public void setPersonTutorBean(PersonTutorBean personTutorBean) {
		this.personTutorBean = personTutorBean;
	}

	public PersonLocationDeterminationType getPersonLocationDeterminationType() {
		return personLocationDeterminationType;
	}

	public void setPersonLocationDeterminationType(PersonLocationDeterminationType personLocationDeterminationType) {
		this.personLocationDeterminationType = personLocationDeterminationType;
	}

	public NameBean getActualLocation() {
		return actualLocation;
	}

	public void setActualLocation(NameBean actualLocation) {
		this.actualLocation = actualLocation;
	}

	/**
	 * Возвращает строку с email пользователя в формате для отправки
	 */
	public String getFullEmailForSend() {
		String senderEmail = getContactBean().getNotEmptyEmail();
		return getFullName() + "<" + senderEmail + ">";
	}

	/**
	 * Пример: И.И.Иванов
	 * @return фамилия пользователя с инициалами
	 */
	public String getLastNameWithInitial() {
		return getFirstName().substring(0, 1) + "." + getSurName().substring(0, 1) + "." + getLastName();
	}

	/**
	 * Пример: Иванов И.И.
	 * @return фамилия пользователя с инициалами
	 */
	public String getNameWithInitial() {
		StringBuilder sb = new StringBuilder();
		if (StringHelper.isNotEmpty(lastName)) {
			sb.append(lastName).append(" ");
		}
		if (StringHelper.isNotEmpty(firstName)) {
			sb.append(firstName, 0, 1).append(".");
		}
		if (StringHelper.isNotEmpty(surName)) {
			sb.append(surName, 0, 1).append(".");
		}
		return sb.toString();
	}

    public static DataObject createDataObject() throws CoreException {
		DataObject dataObject = new DataObject(DATANAME, VVMessage.person, PersonBean.class).setFields(
				new KeyField(ID),
				new StringField(LAST_NAME, PersonMessage.last_name, 100),
				new StringField(FIRST_NAME, PersonMessage.first_name, 100),
				new StringField(SUR_NAME, PersonMessage.surname, 100),
				new StringField(FIO, PersonMessage.fullname, FIO_LENGTH).setIsName().setIndex(),
				new StringField(FIO_LAST_NAME_INTIALS, PersonMessage.f_name_middlename, FIO_LENGTH),
				new RSField(
					CATEGORY_ID,
					SystemMessages.category,
					PersonCategoryRubImpl.PERSON_CATEGORY,
					FKField.SET_NULL
				),
				//могут быть системы, на которых архивных пользователей в базе в разы больше, чем активных.
				//Индекс на данном поле призван ускорить запросы с условиями типа pstatus = 0 на таких системах.
				new ComboField(STATUS, PersonMessage.status, PersonStatus.getFullPersonStatus()).setIndex(),
				new StringField(QP_ID, PersonMessage.query, 255).setIsHidden(),
				new StringField(QC_ID, PersonMessage.query, 255).setIsHidden(),
				new StringField(ME_ID, PersonMessage.measure).setIsHidden(),
				new BeanField(PERSON_MAIN_WORK, PersonMessage.aw_work, PersonWorkBean.DATANAME, ""),
				new StringField(AUTONUMBER, VVMessage.autonumber).setIsHidden(),
				Service.hasModule(AccountModule.class) ?
						new LookupField(DATA_ACCOUNT_ID, PersonMessage.account, AccountFrame.NAME).setIsHidden() : null,
				Service.hasModule(AccountModule.class) ?
						new DoubleField(BALANCE, AccountMessage.balance).setIsHidden() : null,
				new RSField(TYPE, SystemMessages.type, PersonTypeRubricator.PERSON_TYPE, FKField.SET_NULL),
				Service.hasModule(QuaModule.class) ?
						new LookupField(
							PROFILE_QUA_ID,
							QuaMessage.individual_qua_profile,
							ProfileQuaFrame.NAME,
							FKField.SET_NULL
						) : null,
				Service.hasModule(QuaModule.class) ?
						new ComboFieldBuilder(new ComboField(PROFILE_QUA_TYPE, QuaMessage.profile_req, PersonProfileType.class))
								.setScript(ComboBoxExpressionBuilder.createExpressionForShowFields(PersonProfileType.PERSONAL.getValue(), PROFILE_QUA_ID)).getDataField() : null,
                Service.hasModule(ProcedureModule.class) ?
                        new LookupField(
                        	PROFILE_KPI_ID,
							ProcedureMessage.individual_kpi_profile,
							ProfileKPIFrame.NAME,
							FKField.SET_NULL
						) : null,
                Service.hasModule(ProcedureModule.class) ?
                        new ComboFieldBuilder(new ComboField(PROFILE_KPI_TYPE, PostMessage.profile_kpi, PersonProfileType.class))
                                .setScript(ComboBoxExpressionBuilder.createExpressionForShowFields(PersonProfileType.PERSONAL.getValue(), PROFILE_KPI_ID)).getDataField() : null,
				new StringField(CONTANCT_VACANCY_ID, VVMessage.vacancy).setIsHidden(),
				Service.hasModule(DevelopmentModule.class) ?
						new CheckField(ALLOW_CREATE_PLAN_DEVELOPMENT, DevelopmentMessage.allow_create_development_plans) : null,
				new StringField(CODE_EXTERNAL_SYSTEM, PersonMessage.code_external_system)
					.setIndex()
					.enableUpperCaseIndex(),
				new CheckField(ON_MATERNITY_LEAVE, PersonMessage.on_maternity_leave),
				createActualLocationDetTypeField(),
				new RSField(ACTUAL_LOCATION, PersonMessage.actual_location, PersonLocationRubricator.NAME, FKField.SET_NULL)
		);
		dataObject.addChildDataObject(PersonUserDataBean.createDataObject());
		dataObject.addChildDataObject(PersonPrivateBean.createDataObject());
		dataObject.addChildDataObject(PersonProfileBean.createDataObject());
		dataObject.addChildDataObject(PersonContactBean.createDataObject());
		dataObject.addChildDataObject(PersonTutorBean.createDataObject());
		dataObject.addChildDataObject(PersonAdaptationTeamBean.createDataObject());
		dataObject.addMultiColumnIndex("personfioidindex", FIO, ID);
		dataObject.addMultiColumnIndex(PMAINWORK_ID_INDEX, PERSON_MAIN_WORK, ID);
		dataObject.addMultiColumnIndex(ID_PMAINWORK_FIO_INDEX, ID, PERSON_MAIN_WORK, FIO);
		dataObject.addGINIndex(FIO_GIN_INDEX_NAME, FIO);
		return dataObject;
	}

	private static ComboField createActualLocationDetTypeField() {
		ComboField field = new ComboField(
				ACTUAL_LOCATION_DET_TYPE,
				PersonMessage.location_determination_type,
				PersonLocationDeterminationType.class
		);
		List<String> readOnlyValues = CollectionUtils.newArrayList(
				PersonLocationDeterminationType.WORK_CA_POST.getValue(),
				PersonLocationDeterminationType.WORK_CA.getValue()
		);
		return new ComboFieldBuilder(field)
				.setScript(ComboBoxExpressionBuilder.createExpressionForDisableFields(readOnlyValues, ACTUAL_LOCATION))
				.getDataField();
	}

	/**
	 * Заполняем значениями по-умолчанию
	 */
	@Override
	public void fillDefault() {
		super.fillDefault();
		PersonProfileBean profileBean = getProfile();
		profileBean.setIsEmailNotify(true);
		profileBean.setIsIgnoreDelivery(false);
		profileBean.setIsAutoSyncCalendars(false);
		profileBean.setBlog(PersonBlogVisibilityLevel.CUT_OFF);
		setProfile(profileBean);
        PersonWorkBean workBean = new PersonWorkBean();
        workBean.fillDefault();
        setMainWork(workBean);
	}

    public static class ByNameComparator implements Comparator<PersonBean> {
        @Override
        public int compare(PersonBean bean1, PersonBean bean2) {
            return bean1.getFullName().compareTo(bean2.getFullName());
        }
    }

	public String getFio() {
		return fio;
	}

	public void setFio(String fio) {
		this.fio = fio;
	}

	/**
	 * Заполняет ФИО(отдельные поля Фамилии, Имени и Отчества)
	 *
	 * @param firstName Имя
	 * @param surName Отчество
	 * @param lastName Фамилия
	 */
	public void setFullName(String firstName, String surName, String lastName){
		this.firstName = firstName;
		this.surName = surName;
		this.lastName = lastName;
	}

	public String getFioLastNameIntials() {
		return fioLastNameIntials;
	}

	public void setFioLastNameIntials(String fioLastNameIntials) {
		this.fioLastNameIntials = fioLastNameIntials;
	}

    @Override
    public String toString() {
        //Скрываем поле пароль в выводе
        DataSet dataSet = get();
        dataSet.put(PersonUserDataBean.PASS_WORD, HIDDEN);
        dataSet.put(PersonUserDataBean.CLEAR_PASSWORD, HIDDEN);
        return getClass() + " " + dataSet;
    }

}
