package ext.philipmorris.bonus.plan;

import ext.philipmorris.PhilipMorrisModule;
import ext.philipmorris.bonus.model.PhilipMorrisBonusModelWorkTimeBonusPercentArrayBean;
import ext.philipmorris.bonus.model.PhilipMorrisRangeOfBonusPercentArrayBean;
import ext.philipmorris.bonus.model.PhillipMorrisPercentageBonusByGradeBean;
import hr.bonus.bonusemployeeaccrual.value.BonusKindColType;
import hr.bonus.bonusemployeeaccrual.value.processor.BonusKindAddColValueProcessor;
import hr.bonus.model.BonusModelBean;
import hr.bonus.plan.BonusPlanBean;
import hr.bonus.plan.employee.BonusPlanEmployeeBean;
import hr.bonus.plan.employee.BonusPlanEmployeeParentCABean;
import hr.bonus.plan.employee.PeriodPhaseMethod;
import hr.bonus.plan.employee.changes.BonusPlanEmployeeChangesService;
import hr.bonus.plan.employee.colvalue.BonusPlanEmployeeAddColValueBean;
import hr.bonus.plan.employee.task.*;
import hr.bonus.vv.bonuskindcol.BonusKindColVirtualBean;
import hr.careerplanning.vv.matrixpotential.LevelDevelopmentPotentialMemberMatrixPotentialRubricator;
import lms.core.ca.CAAttBean;
import lms.core.ca.CABean;
import lms.core.ca.post.grade.GradeBean;
import lms.core.newprocedure.ProcedureAddBean;
import lms.core.newprocedure.ProcedureBean;
import lms.core.newprocedure.member.PRMemberBean;
import lms.core.person.PersonBean;
import lms.core.person.PersonModule;
import lms.core.person.PersonPrivateBean;
import lms.core.person.profile.PersonProfileBean;
import lms.core.qua.scale.AbstractScaleNumberBean;
import lms.core.qua.scale.ScaleService;
import lms.core.qua.scale.number.ScaleNumberBean;
import mira.constructor.datafield.ConstructorDataFieldService;
import mira.constructor.datafield.type.AbstractMultipleLookupDataFieldType;
import mira.fieldlocalization.FieldLocalizationService;
import mira.fieldlocalization.ValueInLocale;
import mira.vv.rubricator.field.RSField;
import mira.vv.rubricator.standard.RSBean;
import mira.vv.rubricator.standard.RSService;
import mira.vv.rubricatorcategory.RubricatorBean;
import mira.vv.rubs.currency.CurrencyBean;
import mira.vv.rubs.currency.CurrencyService;
import org.mirapolis.compiler.ClassVersion;
import org.mirapolis.core.Module;
import org.mirapolis.core.SystemMessages;
import org.mirapolis.data.bean.DoubleValue;
import org.mirapolis.data.bean.IntValue;
import org.mirapolis.data.bean.NameBean;
import org.mirapolis.data.bean.reflect.Name;
import org.mirapolis.data.bean.reflect.repository.Select;
import org.mirapolis.data.bean.reflect.virtual.QueryVirtualBean;
import org.mirapolis.log.Log;
import org.mirapolis.log.LogFactory;
import org.mirapolis.mvc.model.entity.EntityListenerService;
import org.mirapolis.mvc.model.entity.RuntimeField;
import org.mirapolis.orm.EntityManager;
import org.mirapolis.orm.ORM;
import org.mirapolis.orm.fields.*;
import org.mirapolis.sql.SelectQueryData;
import org.mirapolis.sql.fragment.SelectQuery;
import org.mirapolis.util.DateHelper;
import org.mirapolis.util.IntHelper;
import org.mirapolis.util.StringHelper;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.mirapolis.data.bean.BeanHelper.createMapByNameBeanId;
import static org.mirapolis.data.bean.BeanHelper.getValueSet;
import static org.mirapolis.data.bean.DoubleValue.*;
import static org.mirapolis.sql.fragment.Column.column;
import static org.mirapolis.sql.fragment.NamedParameter.namedParameter;
import static org.mirapolis.util.CollectionUtils.newUnorderedSet;
import static org.mirapolis.util.StringHelper.isEquals;
import static org.mirapolis.util.StringHelper.*;

/**
 * Выполняемый код для заполнения пользовательских колонок в годовом плане премирования PhilipMorris.
 * @author Artem Zakharov
 * @since 08.09.2023
 */
@ClassVersion(24)
public class PhilipMorrisYearBonusPlanEmployeeCalculator extends BonusPlanEmployeeCalculatorWithFileTemplate {
    private static final Log log = LogFactory.getLog(PhilipMorrisYearBonusPlanEmployeeCalculator.class);

    private static final RuntimeField<BonusPlanEmployeeBean, String> EMPLOYEE_NAME_RUNTIME = new RuntimeField<>(
            new StringField("userfield015e0a9b826d4100a041").setLength(500),
            BonusPlanEmployeeBean.class
    );
    private static final String GRADES_1 = "userfieldc1b2b8db82204a4a9a87";
    private static final String GROUPING_1 = "userfielddfa21099621a46ae95ef";
    private static final String GRADES_2 = "userfield64168d9d6e054d138e32";
    private static final String GROUPING_2 = "userfield85418dcd063b47a2a096";
    /**
     * Поле справочник, но рубрикатор заранее неизвестен
     */
    private static final String DEFAULT_PRORATION_PERCENT = "userfield23381539d7734392ad8d";
    /**
     * Поле справочник, но рубрикатор заранее неизвестен
     */
    private static final String PRORATION_PERCENT = "userfield4bcc488b434c4f8a87ef";
    private static final RuntimeField<BonusPlanEmployeeBean, Integer> IC_PROPOSAL_RUNTIME =
            new RuntimeField<>(new IntegerField("userfield59aa034254414b62b6a6"), BonusPlanEmployeeBean.class);
    private static final RuntimeField<BonusPlanEmployeeBean, Integer> STOCK_PROPOSAL_PERCENT_RUNTIME =
            new RuntimeField<>(new IntegerField("userfield0570b88475bf4b3ca5e9"), BonusPlanEmployeeBean.class);
    private static final RuntimeField<PersonProfileBean, Double> YEARLY_LABOUR_REWARD_RUNTIME =
            new RuntimeField<>(new DoubleField("userfield377a620d159c41909d63"), PersonProfileBean.class);
    private static final RuntimeField<BonusPlanEmployeeBean, Double> ABS_IC_STOCK_CALCULATION_BASE_RUNTIME =
            new RuntimeField<>(new DoubleField("userfield589d9838d67c4b67ae8e"), BonusPlanEmployeeBean.class);
    protected static final RuntimeField<BonusPlanEmployeeBean, NameBean> STOCK_ELIGIBILITY_RUNTIME =
            new RuntimeField<>(
                    new RSField(
                            "userfield43f0e38127344786bda7",
                            SystemMessages.empty,
                            LevelDevelopmentPotentialMemberMatrixPotentialRubricator.LEVEL_DEVELOPMENT_POTENTIAL,
                            FKField.SET_NULL
                    ),
                    BonusPlanEmployeeBean.class
            );
    private static final String WHAT_16_FIELD = "userfield59aed0174fa646c48b9d";
    private static final String HOW_16_FIELD = "userfieldc4097f8169184e378c6f";
    private static final String FINALIZATION_DATE_FIELD = "userfield3e026c133a9249b69da5";
    private static final String BUSINESS_RATING_LEVEL_FIELD = "userfieldeedb00fc6b7e4a8fafe3";
    private static final String IS_SMT1_FIELD = "userfield94c6a17f43bd4994bc9c";
    private static final String IS_SMT_FIELD = "userfield57881ed72aa3457d8f07";
    private static final String GRADE_ID_PARAMETER_NAME = "grId";
    private static final String PR_MEMBER_PERSON_DATA_ALIAS = "PRMP";
    private static final String PR_MEMBER_RS_16_ALIAS = "PRU";
    private static final String PR_MEMBER_ALIAS = "PRM";
    private static final String MATRIX_POTENTIAL_ALIAS = "MP";
    private static final String POST_CAREER_DEVELOPMENT_ALIAS = "PCD";
    private static final String PERSON_PROFILE_ALIAS = "PPRF";
    private static final String GRADES_1_MULTI_ALIAS = "GRADES1MULTI";
    private static final String GRADES_2_MULTI_ALIAS = "GRADES2MULTI";
    private static final String GROUPING_1_ALIAS = "GROUPING1";
    private static final String GROUPING_2_ALIAS = "GROUPING2";
    private static final String MATRIX_MEMBER_VALID_STATUS_CODE = "1";
    private static final String GRADE_1_RUBRICATOR_NAME = "Grade Group 1";
    private static final String GROUPING_RUBRICATOR_NAME = "Grouping";
    private static final String CODE_1011 = "1011";
    private static final String CODE_1213 = "1213";
    private static final String CODE_141516 = "141516";
    private static final double PRORATION_DEFAULT_VALUE = 100.0;
    private static final int STOCK_ELIGIBILITY_GRADE_VALUE_TO_COMPARE = 14;

    private static final String SUPERVISOR_NAME_COLUMN_CODE = "79";
    private static final String SMT_1_COLUMN_CODE = "76";
    private static final String SMT_COLUMN_CODE = "77";
    private static final String CURRENCY_COLUMN_CODE = "78";
    private static final String GRADE_GROUP_1_COLUMN_CODE = "1";
    private static final String GROUPING_COLUMN_CODE = "2";
    private static final String IC_FUNDED_AMOUNT_COLUMN_CODE = "3";
    private static final String STOCK_FUNDED_AMOUNT_COLUMN_CODE = "4";
    private static final String WHAT_COLUMN_CODE = "5";
    private static final String WHAT_RATING_COLUMN_CODE = "6";
    private static final String HOW_COLUMN_CODE = "7";
    private static final String HOW_RATING_COLUMN_CODE = "8";
    private static final String STOCK_BASIS_COLUMN_CODE = "10";
    private static final String IC_PAYOUT_RANGE_MIN_COLUMN_CODE = "11";
    private static final String IC_PAYOUT_RANGE_MAX_COLUMN_CODE = "12";
    private static final String DEFAULT_IC_PROPOSAL_COLUMN_CODE = "13";
    private static final String IC_PROPOSAL_COLUMN_CODE = "14";
    private static final String DEFAULT_PRORATION_COLUMN_CODE = "16";
    private static final String PRORATION_COLUMN_CODE = "17";
    protected static final String STOCK_ELIGIBILITY_COLUMN_CODE = "29";
    private static final String STOCK_PROPOSAL_RANGE_MIN_COLUMN_CODE = "30";
    private static final String STOCK_PROPOSAL_RANGE_MAX_COLUMN_CODE = "31";
    private static final String DEFAULT_STOCK_PROPOSAL_COLUMN_CODE = "32";
    private static final String STOCK_PROPOSAL_COLUMN_CODE = "33";
    private static final String WHAT_RATING_FOR_STATEMENT_COLUMN_CODE = "58";
    private static final String HOW_RATING_FOR_STATEMENT_COLUMN_CODE = "59";
    private static final String IC_TARGET_COLUMN_CODE = "62";
    private static final String IC_BUSINESS_RATING_COLUMN_CODE = "64";
    private static final String STOCK_TARGET_COLUMN_CODE = "67";
    private static final String ABS_IC_STOCK_CALCULATION_BASE_COLUMN_CODE = "75";
    private static final String EMPLOYEE_NAME_COLUMN_CODE = "80";

    /**
     * Колонки заполняемые в данном коде
     */
    private static final Set<String> PROCESSED_COLUMN_CODES = newUnorderedSet(
            SUPERVISOR_NAME_COLUMN_CODE,
            SMT_1_COLUMN_CODE,
            SMT_COLUMN_CODE,
            CURRENCY_COLUMN_CODE,
            ABS_IC_STOCK_CALCULATION_BASE_COLUMN_CODE,
            GRADE_GROUP_1_COLUMN_CODE,
            GROUPING_COLUMN_CODE,
            IC_FUNDED_AMOUNT_COLUMN_CODE,
            STOCK_FUNDED_AMOUNT_COLUMN_CODE,
            WHAT_COLUMN_CODE,
            WHAT_RATING_COLUMN_CODE,
            HOW_COLUMN_CODE,
            HOW_RATING_COLUMN_CODE,
            STOCK_BASIS_COLUMN_CODE,
            IC_PAYOUT_RANGE_MIN_COLUMN_CODE,
            IC_PAYOUT_RANGE_MAX_COLUMN_CODE,
            DEFAULT_IC_PROPOSAL_COLUMN_CODE,
            IC_PROPOSAL_COLUMN_CODE,
            DEFAULT_PRORATION_COLUMN_CODE,
            PRORATION_COLUMN_CODE,
            STOCK_ELIGIBILITY_COLUMN_CODE,
            STOCK_PROPOSAL_RANGE_MIN_COLUMN_CODE,
            STOCK_PROPOSAL_RANGE_MAX_COLUMN_CODE,
            DEFAULT_STOCK_PROPOSAL_COLUMN_CODE,
            STOCK_PROPOSAL_COLUMN_CODE,
            WHAT_RATING_FOR_STATEMENT_COLUMN_CODE,
            HOW_RATING_FOR_STATEMENT_COLUMN_CODE,
            IC_TARGET_COLUMN_CODE,
            IC_BUSINESS_RATING_COLUMN_CODE,
            STOCK_TARGET_COLUMN_CODE,
            EMPLOYEE_NAME_COLUMN_CODE
    );
    /**
     * Имя грейда к коду значения справочника Grade Group 1
     */
    private final Map<String, String> gradeNameToGrade1RubCodes = new HashMap<>();

    public PhilipMorrisYearBonusPlanEmployeeCalculator() {
        gradeNameToGrade1RubCodes.put("10", CODE_1011);
        gradeNameToGrade1RubCodes.put("11", CODE_1011);
        gradeNameToGrade1RubCodes.put("12", CODE_1213);
        gradeNameToGrade1RubCodes.put("13", CODE_1213);
        gradeNameToGrade1RubCodes.put("14", CODE_141516);
        gradeNameToGrade1RubCodes.put("15", CODE_141516);
        gradeNameToGrade1RubCodes.put("16", CODE_141516);
    }

    @Autowired
    private Repository repository;
    @Autowired
    private CurrencyService currencyService;
    @Autowired
    private RSService rsService;
    @Autowired
    private FieldLocalizationService fieldLocalizationService;
    @Autowired
    private ScaleService scaleService;
    @Autowired
    private EntityListenerService entityListenerService;
    @Autowired
    private ConstructorDataFieldService constructorDataFieldService;
    @Autowired
    private BonusPlanEmployeeChangesService changesService;
    @Autowired
    private ORM orm;

    @Override
    protected PlanKindFileColumnInnerCalculator<PhilipMorrisYearBonusCalculatorCommonData> createInnerCalculator() {
        return new YearBonusPlanEmployeeCalculator((PhilipMorrisYearBonusCalculatorCommonData) commonData);
    }

    @Override
    public BonusPlanEmployeeWithFileCalculatorCommonData createCommonData(
            BonusPlanBean plan,
            BonusModelBean model,
            PeriodPhaseMethod careerPeriodType
    ) {
        BonusPlanEmployeeWithFileCalculatorCommonData basicData = super.createCommonData(plan, model, careerPeriodType);
        PhilipMorrisYearBonusCalculatorCommonData commonData =
                fillCommonDataWithFile(new PhilipMorrisYearBonusCalculatorCommonData(basicData));
        fillCommonData(commonData, model);
        return commonData;
    }

    /**
     * Заполнить данные для расчета доп. колонок общие для всех участников
     */
    private PhilipMorrisYearBonusCalculatorCommonData fillCommonData(
            PhilipMorrisYearBonusCalculatorCommonData commonData,
            BonusModelBean model
    ) {
        fillCommonDataSmt1(commonData);
        fillCommonDataSmt(commonData);
        fillCommonDataCrIso(commonData);
        fillCommonDataGradeIdToPercentBeanMap(commonData, model);
        fillCommonDataGrade1CodesToValues(commonData);
        fillCommonDataCode1MemberLvlDev(commonData);
        fillCommonDataGrouping(commonData);
        commonData.setGradeNameToGrade1RsCodes(gradeNameToGrade1RubCodes);
        return commonData;
    }

    private void fillCommonDataSmt1(PhilipMorrisYearBonusCalculatorCommonData commonData) {
        String smt1Fio = getFioByQueryData(repository.getSmtEnglishFioQueryData()
                .where(column(POST_CAREER_DEVELOPMENT_ALIAS, IS_SMT1_FIELD).isTrue())
        );
        commonData.setSmt1Fio(smt1Fio);
    }

    private void fillCommonDataSmt(PhilipMorrisYearBonusCalculatorCommonData commonData) {
        String smtFio = getFioByQueryData(repository.getSmtEnglishFioQueryData()
                .where(column(PERSON_PROFILE_ALIAS, IS_SMT_FIELD).isTrue())
        );
        commonData.setSmtFio(smtFio);
    }

    private void fillCommonDataCrIso(PhilipMorrisYearBonusCalculatorCommonData commonData) {
        String crIso = currencyService.findCurrencyBeanWithName("РУБ").map(CurrencyBean::getIso).orElse(EMPTY_STRING);
        commonData.setCrIso(crIso);
    }

    private void fillCommonDataGradeIdToPercentBeanMap(
            PhilipMorrisYearBonusCalculatorCommonData commonData,
            BonusModelBean model
    ) {
        PhillipMorrisPercentageBonusByGradeBean percentFilter =
                new PhillipMorrisPercentageBonusByGradeBean();
        percentFilter.setForeignId(model.getId());
        Map<String, PhillipMorrisPercentageBonusByGradeBean> gradeIdToPercentBeanMap = createMapByNameBeanId(
                EntityManager.list(percentFilter),
                PhillipMorrisPercentageBonusByGradeBean.GRADE_ID
        );
        commonData.setGradeIdToPercentBeanMap(gradeIdToPercentBeanMap);
    }

    private void fillCommonDataGrade1CodesToValues(PhilipMorrisYearBonusCalculatorCommonData commonData) {
        RubricatorBean rubFilter = new RubricatorBean();
        rubFilter.setCatSysName(PersonModule.RUB_CATEGORY);
        rubFilter.setName(GRADE_1_RUBRICATOR_NAME);
        Map<String, String> grade1CodesToValues = EntityManager.findOptional(rubFilter)
                .map(RubricatorBean::getObjAttrName)
                .filter(StringHelper::isNotEmpty)
                .map(grade1RubName ->
                        rsService.getRsBeansByCodes(grade1RubName, newUnorderedSet(gradeNameToGrade1RubCodes.values()))
                                .stream()
                                .collect(Collectors.toMap(RSBean::getCode, RSBean::getName, (name1, name2) -> name1))
                )
                .orElse(new HashMap<>());
        commonData.setGrade1CodesToValues(grade1CodesToValues);
    }

    private void fillCommonDataCode1MemberLvlDev(PhilipMorrisYearBonusCalculatorCommonData commonData) {
        commonData.setCode1MemberLvlDev(
                rsService.getRSByCode(
                        MATRIX_MEMBER_VALID_STATUS_CODE,
                        LevelDevelopmentPotentialMemberMatrixPotentialRubricator.LEVEL_DEVELOPMENT_POTENTIAL
                )
        );
    }

    private void fillCommonDataGrouping(PhilipMorrisYearBonusCalculatorCommonData commonData) {
        RubricatorBean rubFilter = new RubricatorBean();
        rubFilter.setCatSysName(PersonModule.RUB_CATEGORY);
        rubFilter.setName(GROUPING_RUBRICATOR_NAME);
        String defaultGroupingValue = EntityManager.findFieldOptional(rubFilter, RubricatorBean.OBJ_ATTR_NAME)
                .flatMap(rubName -> rsService.getDefaultRSOptional(rubName))
                .map(RSBean::getName)
                .orElse(EMPTY_STRING);
        commonData.setDefaultGroupingRsValue(defaultGroupingValue);
    }

    /**
     * Английские непустые "Фамилия Имя" из результатов queryData
     */
    private String getFioByQueryData(SelectQueryData queryData) {
        return EntityManager.findOptional(queryData, FirstLastNameQueryBean.class)
                .map(bean -> joinWithoutEmpty(SPACE, bean.getLastName(), bean.getFirstName()))
                .orElse(EMPTY_STRING);
    }

    @Override
    protected String getFileUserFieldSysName() {
        return EMPTY_STRING;
    }

    @Override
    protected List<BonusKindColVirtualBean> listColumnsForFileImport(
            BonusPlanEmployeeWithFileCalculatorCommonData commonData
    ) {
        return new ArrayList<>();
    }

    /**
     * Рассчитыватель значений доп. колонок
     */
    protected class YearBonusPlanEmployeeCalculator extends
            PlanKindFileColumnInnerCalculator<PhilipMorrisYearBonusCalculatorCommonData> {

        /**
         * Данные расчета оценки для колонки What и связанных для текущего участника плана
         */
        private Optional<PrMemberResultQueryBean> whatDataBean = Optional.empty();
        /**
         * Данные расчета оценки для колонки How и связанных для текущего участника плана
         */
        private Optional<PrMemberResultQueryBean> howDataBean = Optional.empty();
        /**
         * Значение колонки What для текущего участника плана
         */
        private Optional<Double> whatValueOptional = Optional.empty();
        /**
         * Значение колонки How для текущего участника плана
         */
        private Optional<Double> howValueOptional = Optional.empty();
        /**
         * Диапазон процентов премирования для текущего участника плана
         */
        private Optional<PhilipMorrisRangeOfBonusPercentArrayBean> rangeBeanOptional = Optional.empty();
        /**
         * Статус участника матрицы потенциала для текущего участника плана
         */
        private Optional<RSBean> matrixMemberLvlDevRsBeanOptional = Optional.empty();

        private YearBonusPlanEmployeeCalculator(PhilipMorrisYearBonusCalculatorCommonData commonData) {
            super(commonData);
        }

        @Override
        protected Collection<String> getAllColumnCodes() {
            return Collections.unmodifiableSet(PROCESSED_COLUMN_CODES);
        }

        @Override
        protected void addErrorEmployeeNotExistInFile(String employeeId, String employeeNumberColumnName) {
            //в данной задаче нет колонок из файла
        }

        @Override
        protected List<BonusPlanEmployeeColumnCalculatorCaller> getCallersList() {
            return Arrays.asList(Columns.values());
        }

        @Override
        protected Collection<BonusPlanEmployeeFileColumnCalculatorCaller> getFileColumnCallersList() {
            //в данной задаче нет колонок из файла
            return Collections.emptyList();
        }

        /**
         * колонка по коду, если она дополнительная
         */
        private Optional<BonusKindColVirtualBean> getColumnByCodeWithTypeCheck(String code) {
            Optional<BonusKindColVirtualBean> column = commonData.getColumnByCode(code);
            if (!column.isPresent() || BonusKindColType.add != column.get().getColType()) {
                //колонка настроена неправильно
                log.warn("Wrong settings for column with code " + code);
                return Optional.empty();
            }
            return column;
        }

        private Optional<NameBean> getGradeOptional() {
            return Optional.ofNullable(data.getEmployeeBean())
                    .map(BonusPlanEmployeeBean::getGrade);
        }

        /**
         * Значение доп. колонки для текущего ряда.
         */
        private Optional<String> getAddColValue(BonusKindColVirtualBean column) {
            BonusKindAddColValueProcessor colValueProcessor =
                    (BonusKindAddColValueProcessor) column.getColType().getProcessor(column);
            BonusPlanEmployeeAddColValueBean valueBean =
                    colValueProcessor.getOrCreateValueBean(data.getEmployeeBean().getId());
            return valueBean.getValue().getValue().stream().findFirst();
        }

        private void saveAddColumnValue(String columnCode, String value) {
            getColumnByCodeWithTypeCheck(columnCode).ifPresent(column -> saveAddColumnValue(column, value));
        }

        /**
         * Значение доп. колонки "ABS/ IC&Stock Calculation base" для текущего ряда
         */
        private Optional<Double> getAbcColumnValue() {
            return getColumnByCodeWithTypeCheck(ABS_IC_STOCK_CALCULATION_BASE_COLUMN_CODE)
                    .flatMap(abcColumn -> getAddColValue(abcColumn).map(Double::parseDouble));
        }

        /**
         * Проценты премирования по грейду для грейда из текущего ряда
         */
        private Optional<PhillipMorrisPercentageBonusByGradeBean> getPercentageBonusByGradeValue() {
            return getGradeOptional()
                    .map(NameBean::getId)
                    .filter(StringHelper::isNotEmpty)
                    .map(gradeId -> commonData.getGradeIdToPercentBeanMap().get(gradeId));
        }

        /**
         * Добавить к запросу колонку PRM.#resultPointFieldName# as resultpoint и джойн
         * LEFT JOIN VV$RUBSECTION PRU ON PRU.rsid = PRMP.#rs16FieldName#
         * и найти бин
         */
        private Optional<PrMemberResultQueryBean> findPrMemberResultQueryBean(
                String personId,
                String rs16FieldName,
                String resultPointFieldName
        ) {
            Date planStart = commonData.getPlanStart();
            Date planEnd = commonData.getPlanEnd();
            SelectQueryData queryData = repository.findPrMemberResultQueryData(
                    personId,
                    DateHelper.isNotNull(planStart),
                    planStart,
                    DateHelper.isNotNull(planEnd),
                    planEnd
            );
            SelectQuery query = queryData.getQuery();
            query.leftJoin(RSBean.DATANAME, PR_MEMBER_RS_16_ALIAS).on(column(PR_MEMBER_RS_16_ALIAS, RSBean.ID)
                    .eq(column(PR_MEMBER_PERSON_DATA_ALIAS, rs16FieldName))
            );
            query.addColumn(RSBean.ID, PR_MEMBER_RS_16_ALIAS)
                    .addColumn(RSBean.NAME, PR_MEMBER_RS_16_ALIAS)
                    .addColumn(RSBean.CODE, PR_MEMBER_RS_16_ALIAS)
                    .addColumn(resultPointFieldName, PR_MEMBER_ALIAS, PrMemberResultQueryBean.RESULT_POINT);
            return EntityManager.findOptional(queryData, PrMemberResultQueryBean.class);
        }

        /**
         * Добавить к запросу сортировку
         * ORDER BY MP.userfield3e026c133a9249b69da5 DESC
         * и найти бин
         */
        private Optional<RSBean> findMatrixMemberLvlDevRsBean() {
            int planStartYear = DateHelper.getYear(commonData.getPlanStart());
            SelectQueryData queryData = repository.findMatrixPotentialQueryData(
                    data.getEmployeeBean().getPerson().getId(),
                    planStartYear
            );
            queryData.getQuery()
                    .orderBy(column(MATRIX_POTENTIAL_ALIAS, FINALIZATION_DATE_FIELD).desc())
                    .limit(1)
                    .setOffset(0);
            return EntityManager.findOptional(queryData, RSBean.class);
        }

        /**
         * Записать в польз. колонку значение шкалы(либо справочника) из бина. Вернуть записанное значение
         */
        private Optional<Double> saveColumnValuePrMemberResult(
                Optional<PrMemberResultQueryBean> prMemberResultDataBean,
                String columnCode
        ) {
            Optional<Double> resultPointOptional = prMemberResultDataBean
                    .map(dataBean -> isNotEmpty(dataBean.getRsId())
                            ? parse(dataBean.getRsCode())
                            : dataBean.getResultPoint()
                    )
                    .filter(DoubleValue::isNotNull);
            saveAddColumnValue(
                    columnCode,
                    resultPointOptional
                            .map(DoubleValue::emptyIfUndefined)
                            .orElse(EMPTY_STRING)
            );
            return resultPointOptional;
        }

        private void calculateSupervisorName() {
            String resultValue = getFioByQueryData(repository.getDirectorEnglishFioQueryData(data.getPersonId()));
            saveAddColumnValue(SUPERVISOR_NAME_COLUMN_CODE, resultValue);
        }

        private void calculateSmt1() {
            saveAddColumnValue(SMT_1_COLUMN_CODE, commonData.getSmt1Fio());
        }

        private void calculateSmt() {
            saveAddColumnValue(SMT_COLUMN_CODE, commonData.getSmtFio());
        }

        private void calculateCurrency() {
            saveAddColumnValue(CURRENCY_COLUMN_CODE, commonData.getCrIso());
        }

        private void calculateAbcIcStockCalculationBase() {
            BonusPlanEmployeeBean employee = data.getEmployeeBean();
            Double abcValue = EntityManager.findOptional(employee.getPerson().getId(), PersonBean.class)
                    .map(person -> YEARLY_LABOUR_REWARD_RUNTIME.get(person.getProfile()))
                    .orElse(NULL);
            saveAddColumnValue(ABS_IC_STOCK_CALCULATION_BASE_COLUMN_CODE, DoubleValue.emptyIfUndefined(abcValue));
            ABS_IC_STOCK_CALCULATION_BASE_RUNTIME.set(employee, abcValue);
        }

        private void calculateGradeGroup1() {
            String resultValue = getGradeOptional()
                    .map(NameBean::getName)
                    .filter(StringHelper::isNotEmpty)
                    .map(gradeName -> commonData.getGradeNameToGrade1RsCodes().get(gradeName))
                    .map(rsCode -> commonData.getGrade1CodesToValues().get(rsCode))
                    .orElse(EMPTY_STRING);
            saveAddColumnValue(GRADE_GROUP_1_COLUMN_CODE, resultValue);
        }

        private void calculateGrouping() {
            String resultValue = getGradeOptional()
                    .map(NameBean::getId)
                    .filter(StringHelper::isNotEmpty)
                    .map(gradeId -> {
                        List<CaGroupingQueryBean> groupingBeans = listCaGroupingQueryBeans(gradeId);
                        Optional<String> grouping1Value = groupingBeans
                                .stream()
                                .filter(bean -> isNotEmpty(bean.getGrade1Id()))
                                .map(CaGroupingQueryBean::getGrouping1Name)
                                .filter(StringHelper::isNotEmpty)
                                .findAny();
                        return grouping1Value.orElseGet(() -> groupingBeans
                                .stream()
                                .filter(bean -> isNotEmpty(bean.getGrade2Id()))
                                .map(CaGroupingQueryBean::getGrouping2Name)
                                .filter(StringHelper::isNotEmpty)
                                .findAny()
                                .orElseGet(() -> commonData.getDefaultGroupingRsValue())
                        );
                    })
                    .orElse(EMPTY_STRING);
            saveAddColumnValue(GROUPING_COLUMN_CODE, resultValue);
        }

        /**
         * Достает данные группинга по «Подразделения, не отнесенные к существующим типам» с данным грейдом.
         * Добавляет колонки:
         * GROUP1.rsname as group1name,
         * GROUP2.rsname as gruop2name,
         * GRADES1.grid as grade1id,
         * GRADES2.grid as grade2id
         * и джойны:
         * LEFT JOIN CN$LINK_MULTI_userfieldc1b2b8db82204a4a9a87 GRADES1 on GRADES1.caid = catt.caid and GRADES1.grid
         * = :grId
         * LEFT join VV$RUBSECTION GROUP1 on GROUP1.rsid = CATT.userfielddfa21099621a46ae95ef
         * LEFT JOIN CN$LINK_MULTI_userfield64168d9d6e054d138e32 GRADES2 on GRADES2.caid = catt.caid and GRADES2.grid
         * = :grId
         * LEFT join VV$RUBSECTION GROUP2 on GROUP2.rsid = CATT.userfield85418dcd063b47a2a096
         */
        private List<CaGroupingQueryBean> listCaGroupingQueryBeans(String gradeId) {
            SelectQueryData queryData = repository.findCaGroupingQueryData(
                    getValueSet(data.getParentsNoType(), BonusPlanEmployeeParentCABean.CA_ID)
            );
            SelectQuery query = queryData.getQuery();
            addGradeIdColumnWithLinkTableJoinToQuery(
                    query,
                    GRADES_1,
                    CaGroupingQueryBean.GRADE_1,
                    GRADES_1_MULTI_ALIAS
            );
            addGradeIdColumnWithLinkTableJoinToQuery(
                    query,
                    GRADES_2,
                    CaGroupingQueryBean.GRADE_2,
                    GRADES_2_MULTI_ALIAS
            );
            addGroupNameColumnWithJoinToQuery(query, GROUPING_1, CaGroupingQueryBean.GROUPING_1_NAME, GROUPING_1_ALIAS);
            addGroupNameColumnWithJoinToQuery(query, GROUPING_2, CaGroupingQueryBean.GROUPING_2_NAME, GROUPING_2_ALIAS);
            queryData.withNamedIdParameter(GRADE_ID_PARAMETER_NAME, gradeId);
            return EntityManager.listVirtual(queryData, CaGroupingQueryBean.class);
        }

        /**
         * Колонка и джойн по мультитаблице грейдов для группинга
         */
        private void addGradeIdColumnWithLinkTableJoinToQuery(
                SelectQuery query,
                String fieldName,
                String fieldAlias,
                String multiTableAlias
        ) {
            query.leftJoin(
                    AbstractMultiTableField.createLinkTableNameByDefaultScheme(
                            AbstractMultipleLookupDataFieldType.LINK_TABLE_PREFIX,
                            fieldName
                    ),
                    multiTableAlias
            ).on(column(multiTableAlias, CABean.ID).eq(column(CAAttBean.ALIAS, CABean.ID))
                    .and(column(multiTableAlias, GradeBean.ID).eq(namedParameter(GRADE_ID_PARAMETER_NAME)))
            );
            query.addColumn(GradeBean.ID, multiTableAlias, fieldAlias);
        }

        /**
         * Колонка и джойн по справочнику для группинга
         */
        private void addGroupNameColumnWithJoinToQuery(
                SelectQuery query,
                String fieldName,
                String fieldAlias,
                String rsTableAlias
        ) {
            query.leftJoin(RSBean.DATANAME, rsTableAlias)
                    .on(column(rsTableAlias, RSBean.ID).eq(column(CAAttBean.ALIAS, fieldName)));
            query.addColumn(RSBean.NAME, rsTableAlias, fieldAlias);
        }

        private void calculateICFundedAmount() {
            String resultValue = getAbcColumnValue()
                    .flatMap(abcValue -> getPercentageBonusByGradeValue()
                            .map(PhillipMorrisPercentageBonusByGradeBean::getIncomeBonusPercentage)
                            .filter(DoubleValue::isNotNull)
                            .map(incomeBonusPercentage -> getFormattedDouble(
                                    abcValue * incomeBonusPercentage / 100,
                                    0,
                                    RoundingMode.UP
                            ))
                            .map(DoubleValue::emptyIfUndefined)
                    )
                    .orElse(EMPTY_STRING);
            saveAddColumnValue(IC_FUNDED_AMOUNT_COLUMN_CODE, resultValue);
        }

        private void calculateStockFundedAmount() {
            String resultValue = getAbcColumnValue()
                    .flatMap(abcValue -> getPercentageBonusByGradeValue()
                            .filter(percentBean -> isNotNull(percentBean.getStocksBonusPercentage()))
                            .filter(percentBean -> isNotNull(percentBean.getCorrectiveBonusPercentage1()))
                            .filter(percentBean -> isNotNull(percentBean.getCorrectiveBonusPercentage2()))
                            .map(percentBean -> {
                                double stockBonusValue = getFormattedDouble(
                                        abcValue * percentBean.getStocksBonusPercentage() / 100,
                                        0,
                                        RoundingMode.UP
                                );
                                return getFormattedDouble(
                                        stockBonusValue * percentBean.getCorrectiveBonusPercentage1() / 100
                                                * percentBean.getCorrectiveBonusPercentage2() / 100,
                                        2,
                                        RoundingMode.HALF_UP
                                );
                            })
                            .map(DoubleValue::emptyIfUndefined)
                    )
                    .orElse(EMPTY_STRING);
            saveAddColumnValue(STOCK_FUNDED_AMOUNT_COLUMN_CODE, resultValue);
        }

        private void calculateWhat() {
            whatDataBean = findPrMemberResultQueryBean(
                    data.getEmployeeBean().getPerson().getId(),
                    WHAT_16_FIELD,
                    PRMemberBean.MANUAL_RESULT_POINT
            );
            whatValueOptional = saveColumnValuePrMemberResult(whatDataBean, WHAT_COLUMN_CODE);
        }

        private void calculateWhatRating() {
            String resultValue = whatDataBean
                    .flatMap(dataBean ->
                            isNotEmpty(dataBean.getRsId())
                                    ? findRsEngName(dataBean)
                                    : findScaleNumberEngName(dataBean)
                    ).orElse(EMPTY_STRING);
            saveAddColumnValue(WHAT_RATING_COLUMN_CODE, resultValue);
        }

        private Optional<String> findRsEngName(PrMemberResultQueryBean resultQueryBean) {
            return fieldLocalizationService.getValueInSpecificLocale(
                    RSBean.DATANAME,
                    RSBean.NAME,
                    resultQueryBean.getRsId(),
                    resultQueryBean.getRsName(),
                    Locale.ENGLISH
            ).map(ValueInLocale::getValue);
        }

        private Optional<String> findScaleNumberEngName(PrMemberResultQueryBean resultQueryBean) {
            return scaleService.listScaleNumber(resultQueryBean.getResultScaleId())
                    .stream()
                    .filter(number -> DoubleValue.isEquals(number.getPoint(), resultQueryBean.getResultPoint()))
                    .findFirst()
                    .flatMap(number -> fieldLocalizationService.getValueInSpecificLocale(
                            ScaleNumberBean.DATANAME,
                            AbstractScaleNumberBean.NAME,
                            number.getId(),
                            number.getName(),
                            Locale.ENGLISH
                    ))
                    .map(ValueInLocale::getValue);
        }

        private void calculateHow() {
            howDataBean = findPrMemberResultQueryBean(
                    data.getEmployeeBean().getPerson().getId(),
                    HOW_16_FIELD,
                    PRMemberBean.MANUAL_KPI_RESULT_POINT
            );
            howValueOptional = saveColumnValuePrMemberResult(howDataBean, HOW_COLUMN_CODE);
        }

        private void calculateHowRating() {
            String resultValue = howDataBean.flatMap(dataBean ->
                    isNotEmpty(dataBean.getRsId())
                            ? findRsEngName(dataBean)
                            : findScaleNumberEngName(dataBean)
            ).orElse(EMPTY_STRING);
            saveAddColumnValue(HOW_RATING_COLUMN_CODE, resultValue);
        }

        private void calculateStockBasis() {
            String resultValue = getAbcColumnValue()
                    .flatMap(abcValue -> getPercentageBonusByGradeValue()
                            .map(PhillipMorrisPercentageBonusByGradeBean::getStocksBonusPercentage)
                            .filter(DoubleValue::isNotNull)
                            .map(stocksBonusPercentage -> getFormattedDouble(
                                    abcValue * stocksBonusPercentage / 100,
                                    0,
                                    RoundingMode.UP
                            ))
                    )
                    .map(DoubleValue::emptyIfUndefined)
                    .orElse(EMPTY_STRING);
            saveAddColumnValue(STOCK_BASIS_COLUMN_CODE, resultValue);
        }

        private void calculateICPayoutRangeMin() {
            rangeBeanOptional = getGradeOptional()
                    .map(NameBean::getId)
                    .filter(StringHelper::isNotEmpty)
                    .flatMap(gradeId -> howValueOptional
                            .flatMap(how -> whatValueOptional.flatMap(what -> {
                                Set<String> noTypeCaIds = getValueSet(
                                        data.getParentsNoType(),
                                        BonusPlanEmployeeParentCABean.CA_ID
                                );
                                return repository.findPercentRangeBean(
                                        noTypeCaIds,
                                        newUnorderedSet(gradeId),
                                        what,
                                        how,
                                        commonData.getModel().getId()
                                );
                            }))
                    );
            saveAddColumnValue(
                    IC_PAYOUT_RANGE_MIN_COLUMN_CODE,
                    getICValueFromRangeBeanOptional(PhilipMorrisRangeOfBonusPercentArrayBean::getLowerIncomeRangeLimit)
            );
        }

        private String getICValueFromRangeBeanOptional(
                Function<PhilipMorrisRangeOfBonusPercentArrayBean, Double> beanValueGetter
        ) {
            return rangeBeanOptional
                    .map(beanValueGetter)
                    .map(DoubleValue::emptyIfUndefined)
                    .orElse(EMPTY_STRING);
        }

        private void calculateICPayoutRangeMax() {
            saveAddColumnValue(
                    IC_PAYOUT_RANGE_MAX_COLUMN_CODE,
                    getICValueFromRangeBeanOptional(PhilipMorrisRangeOfBonusPercentArrayBean::getUpperIncomeRangeLimit)
            );
        }

        private void calculateIDefaultIcProposal() {
            String resultValue =
                    getICValueFromRangeBeanOptional(PhilipMorrisRangeOfBonusPercentArrayBean::getIncomePayment);
            int intValue = getIntValueFromDouble(parse(resultValue));
            saveAddColumnValue(DEFAULT_IC_PROPOSAL_COLUMN_CODE, resultValue);
            saveAddColumnValue(IC_PROPOSAL_COLUMN_CODE, IntValue.toString(intValue));
            IC_PROPOSAL_RUNTIME.set(data.getEmployeeBean(), intValue);
        }

        private int getIntValueFromDouble(Double doubleValue) {
            return isNull(doubleValue) ? IntValue.NULL : getIntValue(doubleValue);
        }

        private void calculateIDefaultProration() {
            BonusPlanEmployeeBean employee = data.getEmployeeBean();
            Date continuousDateStart = employee.getContinuousDateStart();
            String resultValue = DateHelper.isNotNull(continuousDateStart)
                    ? getProrationValueForStartDate(continuousDateStart)
                    : EMPTY_STRING;
            String stringIntValue = IntHelper.emptyIfUndefined(getIntValue(parse(resultValue)));
            findRsUserFieldByNameAndFillEmployeeFieldAndColumn(
                    employee,
                    stringIntValue,
                    DEFAULT_PRORATION_PERCENT,
                    DEFAULT_PRORATION_COLUMN_CODE
            );
            findRsUserFieldByNameAndFillEmployeeFieldAndColumn(
                    employee,
                    stringIntValue,
                    PRORATION_PERCENT,
                    PRORATION_COLUMN_CODE
            );
        }

        private void findRsUserFieldByNameAndFillEmployeeFieldAndColumn(
                BonusPlanEmployeeBean employee,
                String rsName,
                String rsUserFieldName,
                String columnCode
        ) {
            RSBean rsBean = Optional.ofNullable(rsName)
                    .filter(StringHelper::isNotEmpty)
                    .flatMap(validRsName -> rsService.findRsIdByName(
                            rsName,
                            constructorDataFieldService.getConstructorDataFieldByName(rsUserFieldName).getVocObject()
                    ))
                    .map(rsId -> rsService.getRSBean(rsId))
                    .orElse(new RSBean());
            saveAddColumnValue(columnCode, nullIfEmpty(rsBean.getName()));
            employee.setFieldValueByName(rsUserFieldName, NameBean.create(defaultIfNull(rsBean.getId()), rsName));
        }

        private String getProrationValueForStartDate(Date continuousDateStart) {
            if (DateHelper.before(continuousDateStart, commonData.getPlanStart())) {
                return emptyIfUndefined(PRORATION_DEFAULT_VALUE);
            } else {
                double monthsWorked = (double) DateHelper.MONTHS_PER_YEAR
                        - DateHelper.getMonth(continuousDateStart);
                PhilipMorrisBonusModelWorkTimeBonusPercentArrayBean filter =
                        new PhilipMorrisBonusModelWorkTimeBonusPercentArrayBean();
                filter.setNumOfMonthsWorked(monthsWorked);
                filter.setForeignId(commonData.getModel().getId());
                return EntityManager.findOptional(filter)
                        .map(PhilipMorrisBonusModelWorkTimeBonusPercentArrayBean::getBonusPercent)
                        .map(DoubleValue::emptyIfUndefined)
                        .orElse(EMPTY_STRING);
            }
        }

        private void calculateStockEligibility() {
            RSBean matrixLvlDevRsBean = getGradeOptional()
                    .map(NameBean::getId)
                    .filter(StringHelper::isNotEmpty)
                    .flatMap(gradeId -> EntityManager.findOptional(gradeId, GradeBean.class))
                    .map(GradeBean::getNo)
                    .flatMap(gradeNo -> {
                        matrixMemberLvlDevRsBeanOptional = gradeNo < STOCK_ELIGIBILITY_GRADE_VALUE_TO_COMPARE
                                ? findMatrixMemberLvlDevRsBean()
                                : commonData.getCode1MemberLvlDev();
                        return matrixMemberLvlDevRsBeanOptional;
                    })
                    .orElse(new RSBean());
            saveAddColumnValue(STOCK_ELIGIBILITY_COLUMN_CODE, defaultIfNull(matrixLvlDevRsBean.getName()));
            STOCK_ELIGIBILITY_RUNTIME.set(
                    data.getEmployeeBean(),
                    NameBean.create(defaultIfNull(matrixLvlDevRsBean.getId()))
            );
        }

        private void calculateStockProposalRangeMin() {
            saveAddColumnValue(
                    STOCK_PROPOSAL_RANGE_MIN_COLUMN_CODE,
                    getStockProposalValue(PhilipMorrisRangeOfBonusPercentArrayBean::getLowerStockRangeLimit)
            );
        }

        private String getStockProposalValue(
                Function<PhilipMorrisRangeOfBonusPercentArrayBean, Double> beanValueGetter
        ) {
            return matrixMemberLvlDevRsBeanOptional
                    .map(RSBean::getCode)
                    .filter(statusCode -> isEquals(statusCode, MATRIX_MEMBER_VALID_STATUS_CODE))
                    .flatMap(statusCode -> rangeBeanOptional
                            .map(beanValueGetter)
                            .map(DoubleValue::emptyIfUndefined)
                    )
                    .orElse(EMPTY_STRING);
        }

        private void calculateStockProposalRangeMax() {
            saveAddColumnValue(
                    STOCK_PROPOSAL_RANGE_MAX_COLUMN_CODE,
                    getStockProposalValue(PhilipMorrisRangeOfBonusPercentArrayBean::getUpperStockRangeLimit)
            );
        }

        private void calculateDefaultStockProposal() {
            String resultValue = getStockProposalValue(PhilipMorrisRangeOfBonusPercentArrayBean::getStockPayment);
            saveAddColumnValue(DEFAULT_STOCK_PROPOSAL_COLUMN_CODE, resultValue);
        }

        private void calculateStockProposal() {
            String resultValue = getColumnByCodeWithTypeCheck(DEFAULT_STOCK_PROPOSAL_COLUMN_CODE)
                    .flatMap(this::getAddColValue)
                    .orElse(EMPTY_STRING);
            int intValue = getIntValueFromDouble(parse(resultValue));
            saveAddColumnValue(STOCK_PROPOSAL_COLUMN_CODE, IntHelper.emptyIfUndefined(intValue));
            STOCK_PROPOSAL_PERCENT_RUNTIME.set(data.getEmployeeBean(), intValue);
        }

        private void calculateWhatRatingForStatement() {
            String resultValue = getColumnByCodeWithTypeCheck(WHAT_RATING_COLUMN_CODE)
                    .flatMap(this::getAddColValue)
                    .orElse(EMPTY_STRING);
            saveAddColumnValue(WHAT_RATING_FOR_STATEMENT_COLUMN_CODE, resultValue);
        }

        private void calculateHowRatingForStatement() {
            String resultValue = getColumnByCodeWithTypeCheck(HOW_RATING_COLUMN_CODE)
                    .flatMap(this::getAddColValue)
                    .orElse(EMPTY_STRING);
            saveAddColumnValue(HOW_RATING_FOR_STATEMENT_COLUMN_CODE, resultValue);
        }

        private void calculateICTarget() {
            String resultValue = getAbcColumnValue()
                    .flatMap(abcValue -> getPercentageBonusByGradeValue()
                            .map(PhillipMorrisPercentageBonusByGradeBean::getIncomeBonusPercentage)
                            .map(DoubleValue::emptyIfUndefined)
                    ).orElse(EMPTY_STRING);
            saveAddColumnValue(IC_TARGET_COLUMN_CODE, resultValue);
        }

        private void calculateICBusinessRating() {
            String value = commonData.getModel().get(BUSINESS_RATING_LEVEL_FIELD);
            saveAddColumnValue(IC_BUSINESS_RATING_COLUMN_CODE, defaultIfNull(value));
        }

        private void calculateStockTarget() {
            String resultValue = getPercentageBonusByGradeValue()
                    .map(PhillipMorrisPercentageBonusByGradeBean::getStocksBonusPercentage)
                    .map(DoubleValue::emptyIfUndefined)
                    .orElse(EMPTY_STRING);
            saveAddColumnValue(STOCK_TARGET_COLUMN_CODE, resultValue);
        }

        private void calculateEmployeeName() {
            String resultValue = getFioByQueryData(repository.getPersonEnglishFioQueryData(data.getPersonId()));
            saveAddColumnValue(EMPLOYEE_NAME_COLUMN_CODE, resultValue);
            EMPLOYEE_NAME_RUNTIME.set(data.getEmployeeBean(), resultValue);
        }
    }

    /**
     * Обработчик значений колонок
     */
    private enum Columns implements BonusPlanEmployeeColumnCalculatorCaller<YearBonusPlanEmployeeCalculator> {
        SupervisorName(SUPERVISOR_NAME_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateSupervisorName();
            }
        },
        Smt1(SMT_1_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateSmt1();
            }
        },
        Smt(SMT_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateSmt();
            }
        },
        Currency(CURRENCY_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateCurrency();
            }
        },
        AbcIcStockCalculationBase(ABS_IC_STOCK_CALCULATION_BASE_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateAbcIcStockCalculationBase();
            }
        },
        GradeGroup1(GRADE_GROUP_1_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateGradeGroup1();
            }
        },
        Grouping(GROUPING_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateGrouping();
            }
        },
        ICFundedAmount(IC_FUNDED_AMOUNT_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateICFundedAmount();
            }
        },
        StockFundedAmount(STOCK_FUNDED_AMOUNT_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateStockFundedAmount();
            }
        },
        What(WHAT_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateWhat();
            }
        },
        WhatRating(WHAT_RATING_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateWhatRating();
            }
        },
        How(HOW_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateHow();
            }
        },
        HowRating(HOW_RATING_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateHowRating();
            }
        },
        StockBasis(STOCK_BASIS_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateStockBasis();
            }
        },
        ICPayoutRangeMin(IC_PAYOUT_RANGE_MIN_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateICPayoutRangeMin();
            }
        },
        ICPayoutRangeMax(IC_PAYOUT_RANGE_MAX_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateICPayoutRangeMax();
            }
        },
        DefaultIcProposal(DEFAULT_IC_PROPOSAL_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateIDefaultIcProposal();
            }
        },
        DefaultProration(DEFAULT_PRORATION_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateIDefaultProration();
            }
        },
        StockEligibility(STOCK_ELIGIBILITY_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateStockEligibility();
            }
        },
        StockProposalRangeMin(STOCK_PROPOSAL_RANGE_MIN_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateStockProposalRangeMin();
            }
        },
        StockProposalRangeMax(STOCK_PROPOSAL_RANGE_MAX_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateStockProposalRangeMax();
            }
        },
        DefaultStockProposal(DEFAULT_STOCK_PROPOSAL_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateDefaultStockProposal();
            }
        },
        StockProposal(STOCK_PROPOSAL_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateStockProposal();
            }
        },
        WhatRatingForStatement(WHAT_RATING_FOR_STATEMENT_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateWhatRatingForStatement();
            }
        },
        HowRatingForStatement(HOW_RATING_FOR_STATEMENT_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateHowRatingForStatement();
            }
        },
        ICTarget(IC_TARGET_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateICTarget();
            }
        },
        ICBusinessRating(IC_BUSINESS_RATING_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateICBusinessRating();
            }
        },
        StockTarget(STOCK_TARGET_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateStockTarget();
            }
        },
        EmployeeName(EMPLOYEE_NAME_COLUMN_CODE) {
            @Override
            public void calculate(YearBonusPlanEmployeeCalculator innerCalculator) {
                innerCalculator.calculateEmployeeName();
            }
        };

        private final String columnCode;

        Columns(String columnCode) {
            this.columnCode = columnCode;
        }

        @Override
        public String getColumnCode() {
            return columnCode;
        }
    }

    @Module(PhilipMorrisModule.class)
    @org.springframework.stereotype.Repository
    protected interface Repository {

        /**
         * Английские фамилия + имя руководителя по основной работе физлица
         */
        @Select("SELECT PPD.pplastname, PPD.ppfirstname\n" +
                "FROM PP$PERSON P\n" +
                "INNER JOIN PP$PERSONWORK PW ON PW.pwid = P.pmainwork\n" +
                "INNER JOIN PP$PERSONPRIVATE PPD ON PPD.personid = PW.directorid\n" +
                "WHERE P.personid = :personId")
        SelectQueryData getDirectorEnglishFioQueryData(String personId);

        /**
         * Английские фамилия + имя для Smt1 и Smt
         */
        @Select("SELECT PP.pplastname, PP.ppfirstname\n" +
                "FROM CA$POST CP\n" +
                "INNER JOIN CA$POSTCAREERDEVELOPMENT PCD ON PCD.postid = CP.postid\n" +
                "INNER JOIN PP$PERSON P ON P.personid = CP.personid\n" +
                "INNER JOIN PP$PERSONPRIVATE PP ON PP.personid = P.personid\n" +
                "INNER JOIN PP$PERSONPROFILE PPRF ON PPRF.personid = PP.personid")
        SelectQueryData getSmtEnglishFioQueryData();

        /**
         * Английские фамилия + имя физлица
         */
        @Select("SELECT PP.pplastname, PP.ppfirstname\n" +
                "FROM PP$PERSONPRIVATE PP\n" +
                "WHERE PP.personid = :personId")
        SelectQueryData getPersonEnglishFioQueryData(String personId);

        @Select("SELECT PR.prid, PRAD.resultscaleid\n" +
                "FROM AT$PROCEDURE PR\n" +
                "INNER JOIN AT$PROCEDUREADD PRAD ON PRAD.prid = PR.prid\n" +
                "INNER JOIN ED$SCALE SC ON SC.scaleid = PRAD.resultscaleid\n" +
                "INNER JOIN VV$RUBSECTION VT ON VT.rsid = PR.typeid\n" +
                "INNER JOIN AT$PRMEMBER PRM on PRM.prid = PR.prid \n" +
                "INNER JOIN AT$PRMEMBERPERSONDATA PRMP ON PRMP.prmid = PRM.prmid\n" +
                "INNER JOIN RO$OBJROUTE OBJR ON OBJR.objtableid = PRM.prmid AND OBJR.objtype = 'estimatorprmember'\n" +
                "INNER JOIN RO$OBJPHASE OP ON OBJR.orid = OP.orid\n" +
                "WHERE VT.rscode IN ('1', '2') AND PRM.personid = :personId\n" +
                "	AND (:useEndDate = 0 OR PR.prevstartdate IS NULL OR PR.prevstartdate <= :endDate)\n" +
                "	AND (:useStartDate = 0 OR PR.prevenddate IS NULL OR PR.prevenddate >= :startDate)\n" +
                "ORDER BY OP.opdate DESC\n" +
                "LIMIT 1")
        SelectQueryData findPrMemberResultQueryData(
                String personId,
                boolean useStartDate,
                Date startDate,
                boolean useEndDate,
                Date endDate
        );

        /**
         * Диапазоны процентов премирования по результатам оценки участника плана у данной модели премирования
         */
        @Select("SELECT BPRAR.*\n" +
                "FROM BNS$RANGESOFBONUSPERCENT BPR\n" +
                "INNER JOIN BNS$RANGEOFBONUSPERCENT BPRAR ON BPRAR.aeforeignid = BPR.rbpid\n" +
                "INNER JOIN BNS$RANGESOFBONUSPERCENT_MULTI_RBPCAIDS CAIDS ON CAIDS.rbpid = BPR.rbpid\n" +
                "INNER JOIN BNS$RANGESOFBONUSPERCENT_MULTI_RBPGRADEIDS GRIDS ON GRIDS.rbpid = BPR.rbpid\n" +
                "INNER JOIN ED$SCALENUMBER SCNF on SCNF.snid = BPRAR.rbpfinalkpiscore\n" +
                "INNER JOIN ED$SCALENUMBER SCNM on SCNM.snid = BPRAR.rbpmanualresultbyprocedure\n" +
                "WHERE CAIDS.caid IN (:caIds) AND GRIDS.grid IN (:gradeIds)\n" +
                "    AND SCNF.snscore = :what AND SCNM.snscore = :how AND BPR.rbpbonusmodelid = :modelId")
        Optional<PhilipMorrisRangeOfBonusPercentArrayBean> findPercentRangeBean(
                Set<String> caIds,
                Set<String> gradeIds,
                Double what,
                Double how,
                String modelId
        );

        /**
         * Значение справочника "Статусы участников матрицы потенциала" для участника плана
         */
        @Select("SELECT MPMS.rsid, MPMS.rsname, MPMS.rscode\n" +
                "FROM CP$MATRIXPOTENTIAL MP\n" +
                "INNER JOIN CP$MATRIXPOTENTIALTYPE MPT ON MPT.mptid = MP.mptid\n" +
                "INNER JOIN CP$MPTYPE_MULTI_PRTYPE MPRT ON MPRT.mptid = MPT.mptid\n" +
                "INNER JOIN VV$RUBSECTION PRT ON PRT.rsid = MPRT.rsid\n" +
                "INNER JOIN CP$MATRIXPOTENTIALMEMBER MPM ON MPM.mpid = MP.mpid\n" +
                "INNER JOIN VV$RUBSECTION MPMS ON MPMS.rsid = MPM.leveldevpotentialid\n" +
                "WHERE PRT.rscode IN ('1', '2')\n" +
                "	AND MP.statusid = -601\n" +
                "	AND MPM.personid = :personId\n" +
                "	AND MP.mpyear = :planYear")
        SelectQueryData findMatrixPotentialQueryData(String personId, int planYear);

        /**
         * Основа запроса для данных группинга по организациям
         */
        @Select("SELECT CAATT.caid\n" +
                "FROM CA$CAATT CAATT\n" +
                "WHERE CAATT.caid IN (:caIds)")
        SelectQueryData findCaGroupingQueryData(Set<String> caIds);
    }

    /**
     * Фамилия + Имя
     */
    private static class FirstLastNameQueryBean extends QueryVirtualBean {
        private static final String FIRST_NAME = PersonPrivateBean.EN_FIRST_NAME;
        private static final String LAST_NAME = PersonPrivateBean.EN_LAST_NAME;

        @Name(FIRST_NAME)
        private String firstName;
        @Name(LAST_NAME)
        private String lastName;

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(String lastName) {
            this.lastName = lastName;
        }
    }

    /**
     * Данные для колонок "what" и "how" и связанных с ними. Результат процедуры по связанной шкале + значение из
     * справочника({@code WHAT_16_FIELD} или {@code HOW_16_FIELD})
     */
    private static class PrMemberResultQueryBean extends QueryVirtualBean {
        private static final String PROCEDURE_ID = ProcedureBean.ID;
        private static final String RESULT_SCALE_ID = ProcedureAddBean.RESULT_SCALE_ID;
        private static final String RS_ID = RSBean.ID;
        private static final String RS_CODE = RSBean.CODE;
        private static final String RS_NAME = RSBean.NAME;
        private static final String RESULT_POINT = "resultpoint";

        @Name(PROCEDURE_ID)
        private String procedureId;
        @Name(RESULT_SCALE_ID)
        private String resultScaleId;
        @Name(RS_ID)
        private String rsId;
        @Name(RS_NAME)
        private String rsName;
        @Name(RS_CODE)
        private String rsCode;
        @Name(RESULT_POINT)
        private Double resultPoint;

        public String getProcedureId() {
            return procedureId;
        }

        public void setProcedureId(String procedureId) {
            this.procedureId = procedureId;
        }

        public String getResultScaleId() {
            return resultScaleId;
        }

        public void setResultScaleId(String resultScaleId) {
            this.resultScaleId = resultScaleId;
        }

        public String getRsId() {
            return rsId;
        }

        public void setRsId(String rsId) {
            this.rsId = rsId;
        }

        public String getRsName() {
            return rsName;
        }

        public void setRsName(String rsName) {
            this.rsName = rsName;
        }

        public String getRsCode() {
            return rsCode;
        }

        public void setRsCode(String rsCode) {
            this.rsCode = rsCode;
        }

        public Double getResultPoint() {
            return resultPoint;
        }

        public void setResultPoint(Double resultPoint) {
            this.resultPoint = resultPoint;
        }
    }

    /**
     * Информация для колонки Grouping
     */
    private static class CaGroupingQueryBean extends QueryVirtualBean {
        private static final String CA_ID = CABean.ID;
        private static final String GRADE_1 = "grade1id";
        private static final String GRADE_2 = "grade2id";
        private static final String GROUPING_1_NAME = "group1name";
        private static final String GROUPING_2_NAME = "group2name";

        /**
         * Айди организации
         */
        @Name(CA_ID)
        private String caId;
        /**
         * Название справочника из польз. поля "Grouping 1" у организации
         */
        @Name(GROUPING_1_NAME)
        private String grouping1Name;
        /**
         * Название справочника из польз. поля "Grouping 2" у организации
         */
        @Name(GROUPING_2_NAME)
        private String grouping2Name;
        /**
         * Айди подходящего грейда из польз. поля "Grades 1" у организации
         */
        @Name(GRADE_1)
        private String grade1Id;
        /**
         * Айди подходящего грейда из польз. поля "Grades 2" у организации
         */
        @Name(GRADE_2)
        private String grade2Id;

        public String getCaId() {
            return caId;
        }

        public void setCaId(String caId) {
            this.caId = caId;
        }

        public String getGrouping1Name() {
            return grouping1Name;
        }

        public void setGrouping1Name(String grouping1Name) {
            this.grouping1Name = grouping1Name;
        }

        public String getGrouping2Name() {
            return grouping2Name;
        }

        public void setGrouping2Name(String grouping2Name) {
            this.grouping2Name = grouping2Name;
        }

        public String getGrade1Id() {
            return grade1Id;
        }

        public void setGrade1Id(String grade1Id) {
            this.grade1Id = grade1Id;
        }

        public String getGrade2Id() {
            return grade2Id;
        }

        public void setGrade2Id(String grade2Id) {
            this.grade2Id = grade2Id;
        }
    }
}