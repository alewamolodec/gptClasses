package hr.bonus.plan.employee.task;

import hr.bonus.BonusMessage;
import hr.bonus.BonusSQL;
import hr.bonus.bonusemployeeaccrual.BonusEmployeeAccrualWorkTimeBean;
import hr.bonus.formula.BonusFormulaType;
import hr.bonus.formula.BonusPlanEmployeeBonusCalcData;
import hr.bonus.model.BonusModelBean;
import hr.bonus.model.BonusModelService;
import hr.bonus.plan.BonusPlanBean;
import hr.bonus.plan.BonusPlanService;
import hr.bonus.plan.employee.*;
import hr.bonus.plan.employee.changes.BonusPlanEmployeeChangesService;
import hr.bonus.plan.forecast.BonusPlanForecastCategoryKPIValueBean;
import hr.bonus.plan.forecast.BonusPlanForecastGroupCategoryKPIValueBean;
import hr.bonus.plan.forecast.BonusPlanForecastKPIValueService;
import hr.bonus.plan.order.employee.BonusPlanOrderEmployeeBean;
import hr.bonus.vv.BonusKindRSBean;
import hr.bonus.vv.WorkTimeAccountingKindRubricator;
import hr.bonus.vv.bonuskindcol.BonusKindColumnService;
import hr.bonus.vv.bonuskindcol.BonusKindSystemColumnGroups;
import hr.bonus.worktimetable.BonusWorkTimetableBean;
import lms.core.ca.CARepository;
import lms.core.person.career.CareerBean;
import lms.service.settings.SettingsModule;
import mira.vv.rubricator.standard.RSService;
import mira.vv.rubs.schedule.CustomScheduledBean;
import org.mirapolis.core.LocalBeanFactory;
import org.mirapolis.data.bean.BeanHelper;
import org.mirapolis.data.bean.DoubleValue;
import org.mirapolis.data.bean.MultiLineText;
import org.mirapolis.data.bean.NameBean;
import org.mirapolis.mvc.model.entity.EntityListenerService;
import org.mirapolis.orm.EntityManager;
import org.mirapolis.service.message.Localized;
import org.mirapolis.sql.Ordering;
import org.mirapolis.sql.QueryData;
import org.mirapolis.sql.fragment.SelectQuery;
import org.mirapolis.util.CollectionUtils;
import org.mirapolis.util.DateHelper;
import org.mirapolis.util.IntHelper;
import org.mirapolis.util.StringHelper;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static hr.bonus.plan.employee.BonusPlanEmployeeParentCAColumnService.ALL_PARENTS;
import static hr.bonus.plan.employee.BonusPlanEmployeeParentCAColumnService.PARENTS_NO_TYPE;
import static org.mirapolis.util.CollectionUtils.newArrayList;

/**
 * Рассчитывает и сохраняет все изменения, которые должны произойти с участником плана премирования
 * Не изменяет участника плана, только собирает изменения
 *
 * @author Zlata Zanina
 * @since 19.10.2018
 */
public class BonusPlanEmployeeCalculator {

    @Autowired
    private BonusPlanService planService;
    @Autowired
    private BonusPlanEmployeeChangesService changesService;
    @Autowired
    private RSService rsService;
    @Autowired
    private BonusKindColumnService columnService;
    @Autowired
    private BonusPlanForecastKPIValueService forecastKPIValueService;
    @Autowired
    private BonusModelService bonusModelService;
    @Autowired
    private BonusPlanEmployeeParentCAColumnService parentCaColumnService;
    @Autowired
    private CARepository caRepository;
    @Autowired
    private EntityListenerService entityListenerService;

    protected BonusPlanEmployeeCalculatorCommonData commonData;
    protected BonusPlanEmployeeCalculateData data;

    protected BonusPlanEmployeeFieldFiller fieldFiller;

    protected BonusPlanEmployeeKPICalculatorCreator kpiCalculatorCreator;

    protected BonusPlanEmployeeContinuousPeriodCalculator continuousPeriodCalculator;

    protected BonusPlanUnchangeableEmployeeStatusCalculator statusCalculator;

    private List<String> errors = new ArrayList<>();
    
    /**
     * Игнорировать ли обработку колонок, которые заполняются через исполняемый код
     */
    private boolean ignoreColumnsFilledByCode;

    /**
     * Id полей участника плана, колонки по которым надо пересчитать
     * Если пустое, то пересчитываются все колонки
     */
    private Set<String> planEmployeeFieldIdsToCalculate = new HashSet<>();

    public BonusPlanEmployeeCalculator() {
        LocalBeanFactory.autoWire(this);
    }

    protected void addError(String error) {
        errors.add(error);
    }

    /**
     * @return накопленный список ошибок для вывода
     */
    public List<String> getErrors() {
        return errors;
    }

    /**
     * Обязательно нужно вызвать этот метод перед запуском расчетов
     */
    public void initialize(BonusPlanEmployeeCalculatorCommonData commonData,
            BonusPlanEmployeeCalculateData data,
            BonusPlanEmployeeFieldFiller fieldFiller,
            BonusPlanEmployeeKPICalculatorCreator kpiCalculatorCreator,
            BonusPlanUnchangeableEmployeeStatusCalculator statusCalculator) {
        this.commonData = commonData;
        this.data = data;
        this.fieldFiller = fieldFiller;
        this.kpiCalculatorCreator = kpiCalculatorCreator;
        this.continuousPeriodCalculator = createContinuousPeriodCalculator();
        this.statusCalculator = statusCalculator;
    }
    
    public BonusPlanEmployeeCalculator ignoreColumnsFilledByCode() {
        ignoreColumnsFilledByCode = true;
        return this;
    }
    
    /**
     * Игнорировать обработку колонок, которые заполняются через исполняемый код
     */
    public boolean isIgnoreColumnsFilledByCode() {
        return ignoreColumnsFilledByCode;
    }

    public Set<String> getPlanEmployeeFieldIdsToCalculate() {
        return planEmployeeFieldIdsToCalculate;
    }

    public BonusPlanEmployeeCalculator setPlanEmployeeFieldIdsToCalculate(Set<String> planEmployeeFieldIdsToCalculate) {
        this.planEmployeeFieldIdsToCalculate = planEmployeeFieldIdsToCalculate;
        return this;
    }

    protected void validateData() {
        if (data == null || fieldFiller == null) {
            throw new IllegalStateException("Initialize method of BonusPlanEmployeeCalculator was not called");
        }
    }

    /**
     * Проводит расчет по всем колонкам, отображаемым в гриде "Сотрудники" плана премирования для участника плана
     */
    public void calculateChanges() {
        validateData();
        if (!data.isActive && data.existingBean == null) {
            //нет смысла создавать нового участника плана, если он сразу станет неактивным
            return;
        }
        //заполнение основных полей бина
        calculateBaseFields();

        //колонки, настроенные в виде премии, рассчитываются после определенных системных расчетов согласно заданному пользователем порядку
        BonusPlanEmployeeKindColumnValueCalculator kindColumnValueCalculator = createKindColumnCalculator();
        kindColumnValueCalculator.calculateAfterGroup(BonusKindSystemColumnGroups.base);

        //считается полем с порядком расчета "2", так что ставим после основных перед kpi
        calculateBonusPercentAndBonusFixedAmount();

        //расчет данных по kpi
        BonusPlanEmployeeKPICalculator kpiCalculator = createKpiCalculator();
        kpiCalculator.calculate(kindColumnValueCalculator);
        //расчет типа участия и премий
        calculateEmployeeType();
        calculatePlanEmployeeBonus(kindColumnValueCalculator);
        collectBaseFieldsChanges();
    }

    /**
     * Сохраняет рассчитанные изменения в бин "Изменения участника плана премирования"
     */
    public void saveChanges() {
        if (data.existingBean != null) {
            changesService.saveChanges(data.getEmployeeBean().getId(), data.getChanges());
        }
        entityListenerService.getSaveListener(BonusPlanEmployeeBean.class).save(data.getEmployeeBean());
    }

    /**
     * @return данные для расчета
     */
    public BonusPlanEmployeeCalculateData getData() {
        return data;
    }

    /**
     * @return созданный или обновленный участник
     */
    public BonusPlanEmployeeBean getEmployeeBean() {
        return data.getEmployeeBean();
    }

    //--------------------Работа с основными полями бина участника плана

    protected void calculateBaseFields() {
        if (data.existingBean == null) {
            createNewParticipant();
        } else {
            data.setEmployeeBean(BeanHelper.copy(data.existingBean));
            updateParticipant();
        }
        processAfterCalculatingBaseFields();
        loadEmployeeCaTypeDivisions();
    }

    protected void calculateBonusPercentAndBonusFixedAmount() {
        fieldFiller.fillBonusPercentAndBonusFixedAmount(data);
        processAfterCalculatingBaseFields();//еще одно сохранение, если нужно
    }

    /**
     * Эти действия будут выполнены после того, как будут рассчитаны все новые значения для полей бина участника плана
     * (кроме премии)
     */
    protected void processAfterCalculatingBaseFields() {
    }

    private void loadEmployeeCaTypeDivisions() {
        List<BonusPlanEmployeeParentCABean> oldValues = listOldTypeDivisionValues();
        List<BonusPlanEmployeeParentCABean> newValues = listNewTypeDivisionValues();

        Map<String, List<BonusPlanEmployeeParentCABean>> oldByColumn =
                parentCaColumnService.getCaByColumn(oldValues);
        Map<String, List<BonusPlanEmployeeParentCABean>> newByColumn =
                parentCaColumnService.getCaByColumn(newValues);

        newByColumn.entrySet().stream()
                .filter(e -> !CollectionUtils.equals(
                        e.getValue(),
                        oldByColumn.get(e.getKey()),
                        BonusPlanEmployeeParentCABean::getCaId))
                .forEach(e -> addParentColumnChange(e.getKey(), oldByColumn.get(e.getKey()), e.getValue()));
        oldByColumn.entrySet().stream()
                .filter(e -> !newByColumn.containsKey(e.getKey()))
                .forEach(e -> addParentColumnChange(e.getKey(), e.getValue(), new ArrayList<>()));

        processEmployeeCaTypeDivisions(oldValues, newValues);
        data.setAllParents(newByColumn.getOrDefault(ALL_PARENTS, newArrayList()));
        data.setParentsNoType(newByColumn.getOrDefault(PARENTS_NO_TYPE, newArrayList()));
    }

    private void addParentColumnChange(String column,
                                       List<BonusPlanEmployeeParentCABean> oldValue,
                                       List<BonusPlanEmployeeParentCABean> newValue) {
        data.getChanges().add(changesService.createChange(
                column,
                parentCaColumnService.getColumnName(column),
                new MultiLineText(oldValue != null? CollectionUtils.join(
                        oldValue, BonusPlanEmployeeParentCABean::getCaName, " - "): ""),
                new MultiLineText(CollectionUtils.join(
                        newValue, BonusPlanEmployeeParentCABean::getCaName, " - ")),
                ""
        ));
    }


    protected void processEmployeeCaTypeDivisions(
            List<BonusPlanEmployeeParentCABean> oldValues, List<BonusPlanEmployeeParentCABean> newValues
    ) {

    }

    private List<BonusPlanEmployeeParentCABean> listOldTypeDivisionValues() {
        if (StringHelper.isEmpty(data.getEmployeeBean().getId())) {
            return new ArrayList<>();
        }
        BonusPlanEmployeeParentCABean filter = new BonusPlanEmployeeParentCABean();
        filter.setEmployeeId(data.getEmployeeBean().getId());
        return EntityManager.list(filter);
    }

    private List<BonusPlanEmployeeParentCABean> listNewTypeDivisionValues() {
        if (StringHelper.isEmpty(data.getCareer().getCaId())) {
            return new ArrayList<>();
        }
        List<BonusPlanEmployeeParentCABean> parents = caRepository.listEmployeeParentCA(data.getCareer().getCaId(),
                !SettingsModule.getSettings().getBonusSettingsBean().getShowCaNameFrom().isAddName());
        parents.forEach(b -> b.setEmployeeId(data.getEmployeeBean().getId()));
        return parents;
    }

    /**
     * Эти действия будут выполнены после того, как будут рассчитаны премии
     */
    protected void processAfterCalculatingBonuses() {
        calculateStatuses();
    }

    protected void processAfterCalculatingBonusDifference() {
        calculateStatuses();
    }

    protected void calculateStatuses() {
        statusCalculator.calculateStatuses(
                commonData,
                data
        );
    }

    /**
     * Сбор изменений в полях бина участника плана
     */
    protected void collectBaseFieldsChanges() {
        if (data.existingBean == null) {
            return;
        }
        //нужно сформировать запись о том, что поле "Табельный номер" должно было измениться
        data.getEmployeeBean().setTabNumber(data.getEmployeeBean().getCurrentTabNumber());
        data.getChanges().addAll(changesService.collectChanges(data.existingBean, data.getEmployeeBean()));
        data.getEmployeeBean().setTabNumber(data.existingBean.getTabNumber());
    }

    protected void createNewParticipant() {
        BonusPlanEmployeeBean employeeBean = new BonusPlanEmployeeBean();
        data.setEmployeeBean(employeeBean);
        employeeBean.setPlanId(commonData.plan.getId());
        employeeBean.setCareerId(data.career.getId());
        employeeBean.getPerson().setId(data.personId);
        employeeBean.setActive(true);
        employeeBean.setType(data.getWithoutBonus().isEmployeeWithoutBonus() ? BonusPlanEmployeeType.not_participant
                : BonusPlanEmployeeType.participant);
        employeeBean.setSourceType(BonusPlanEmployeeSourceType.auto);
        employeeBean.setBaseStatus(BonusPlanEmployeeBaseStatus.not_checked);
        employeeBean.setCalcStatus(BonusPlanEmployeeCalculationStatus.not_calculated);
        employeeBean.setOrderStatus(BonusPlanEmployeeOrderStatus.empty);
        if (StringHelper.isNotEmpty(data.previousPlanEmployee)) {
            employeeBean.getPreviousPlanEmployee().setId(data.previousPlanEmployee);
            //попробуем найти наиболее ранний приказ по предыдущему участнику плана
            QueryData<SelectQuery> prevOrderQueryData = BonusSQL.ListBonusPlanEmployeesOrders.create(Collections.singleton(data.previousPlanEmployee));
            SelectQuery prevOrderQuery = prevOrderQueryData.getQuery().copy();
            prevOrderQuery.orderBy(Ordering.asc(BonusPlanOrderEmployeeBean.CALC_DATE));
            prevOrderQueryData.setQuery(prevOrderQuery);
            EntityManager.findOptional(prevOrderQueryData, BonusPlanEmployeeWithOrderQueryBean.class)
                    .ifPresent(prevOrder -> {
                        employeeBean.getPrevOrderEmployee().setId(prevOrder.getOrderEmpId());
                        employeeBean.getPrevOrderEmployee().setName(prevOrder.getOrderEmpId());
                    });
        }
        fillBaseFields();
        if (!data.isChangeable()) {
            employeeBean.setId("-1");
        }
    }

    protected void updateParticipant() {
        if (StringHelper.isNotEmpty(data.previousPlanEmployee)
                && StringHelper.isEmpty(data.getEmployeeBean().getPreviousPlanEmployee().getId())) {
            data.getEmployeeBean().getPreviousPlanEmployee().setId(data.previousPlanEmployee);
        }
        data.setEmployeeBean(statusCalculator.calculateChangeTabNumberConsequences(data));

        fillBaseFields();

        //Выключено: Формировать участников в плане по сотрудникам, попадающим под фильтры без премий
        data.getEmployeeBean().setActive(data.isActive);
    }

    protected void fillBaseFields() {
        fieldFiller.fill(commonData, data);
        continuousPeriodCalculator.calculateAndFill(data.getEmployeeBean());
    }

    protected void calculateEmployeeType() {
        BonusPlanEmployeeType newType = getNewEmployeeTypeAndSetTypeCause();

        //По участникам с признаком, проставленным вручную, если по ним значение признака снова определено, как «Под вопросом»,
        // то не изменять значение, проставленное вручную.
        if (!(newType.isQuestionable() && data.getEmployeeBean().getSourceType().isManual())) {
            data.getEmployeeBean().setType(newType);
            data.getEmployeeBean().setSourceType(BonusPlanEmployeeSourceType.auto);
        }
    }

    private BonusPlanEmployeeType getNewEmployeeTypeAndSetTypeCause() {
        BonusPlanEmployeeWithoutBonusData bonusData = getBonusData();
        if (bonusData.isEmployeeWithoutBonus() && bonusData.getCauseChangeToType().isPresent()) {
            data.getEmployeeBean().setTypeCause(bonusData.getCause());
            return bonusData.getCauseChangeToType().get();
        } else {
            data.getEmployeeBean().setTypeCause("");
        }
        if (!data.isActive()) {
            return BonusPlanEmployeeType.not_participant;
        }
        if (isQuestionableByModelSettings()) {
            return BonusPlanEmployeeType.questionable;
        }
        return BonusPlanEmployeeType.participant;
    }

    private BonusPlanEmployeeWithoutBonusData getBonusData() {
        if (data.getWithoutBonus().getCauseChangeToType().isPresent() &&
                data.getWithoutBonus().isEmployeeWithoutBonus()) {
            return data.getWithoutBonus();
        }
        BonusPlanEmployeeWithoutBonusData bonusData = isNotParticipantByModelSettings();
        if (bonusData.isEmployeeWithoutBonus()) {
            return bonusData;
        }
        return isNotParticipantByMinValues();
    }

    protected boolean isQuestionableByModelSettings() {
        String contGroundOfDismissal = data.getEmployeeBean().getContinuousGroundOfDismissal().getId();
        return StringHelper.isNotEmpty(contGroundOfDismissal) &&
                NameBean.getIdSet(commonData.model.getQuestionableGroundDismissal()).contains(contGroundOfDismissal);
    }

    protected BonusPlanEmployeeWithoutBonusData isNotParticipantByModelSettings() {
        //настройка "Дата начала непрерывного периода работы в компании меньше,
        // чем дата окончания отчетного периода менее, чем на (в днях)"

        //не участник, если дата начала в периоде меньше даты окончания периода плана и между ними меньше N дней
        if (IntHelper.isNotNull(commonData.model.getNotParticipantDaysInCompany()) &&
                DateHelper.isNotNull(data.getEmployeeBean().getContinuousDateStart()) &&
                data.getEmployeeBean().getContinuousDateStart().before(commonData.plan.getEnd()) &&
                DateHelper.addDaysToDate(data.getEmployeeBean().getContinuousDateStart(),
                        commonData.model.getNotParticipantDaysInCompany()).after(commonData.plan.getEnd())) {
            return BonusPlanEmployeeWithoutBonusData.withoutBonus(
                    BonusMessage.start_work_company_less_end_period.toString());
        }

        //настройка "Основания увольнения при завершении непрерывного периода работы
        // в компании до и после окончания отчетного периода"

        String contGroundOfDismissal = data.getEmployeeBean().getContinuousGroundOfDismissal().getId();
        if (StringHelper.isEmpty(contGroundOfDismissal)) {
            //нет основания увольнения в карьере
            return BonusPlanEmployeeWithoutBonusData.withBonus();
        }
        if (NameBean.getIdSet(commonData.model.getNotParticipantGroundDismissal()).contains(contGroundOfDismissal)) {
            return BonusPlanEmployeeWithoutBonusData.withoutBonus(
                    Localized.format(BonusMessage.ground_of_dismissal_cause,
                            data.getEmployeeBean().getContinuousGroundOfDismissal().getName()).toString());
        }

        //настройка "Основания увольнения при завершении непрерывного периода работы
        // в компании в течение отчетного периода"

        if (!NameBean.getIdSet(commonData.model.getNotParticipantGroundDismissalDuringPeriod())
                .contains(contGroundOfDismissal)) {
            //основание уволнения не входит в список заданных, дальше не проверяем
            return BonusPlanEmployeeWithoutBonusData.withBonus();
        }

        String messageNotBonus = Localized.format(BonusMessage.ground_of_dismissal_during_period_cause,
                data.getEmployeeBean().getContinuousGroundOfDismissal().getName()).toString();
        //последняя карьера из непрерывного периода
        CareerBean dismissalCareer = continuousPeriodCalculator.getDismissalCareer();
        //Проверяем, есть ли хотя бы один плановый рабочий день согласно графику работы из карьеры
        // или согласно производственному календарю,
        // дата которого больше даты окончания этого этапа карьеры И меньше или равна дате окончания отчетного периода
        Date afterDismissal = DateHelper.addDaysToDate(dismissalCareer.getDismissalDate(), 1);
        List<BonusWorkTimetableBean> timetables = getTimetables(dismissalCareer.getSchedule().getId(), afterDismissal);
        if (containsNotNullValue(timetables)) {
            //есть плановый рабочий день по графику из карьеры
            return BonusPlanEmployeeWithoutBonusData.withoutBonus(messageNotBonus);
        }
        //нет по графику из карьеры - проверяем максимальную дату, которую нашли по этому графику
        Optional<Date> maxDate = timetables.stream().map(BonusWorkTimetableBean::getDate)
                .max(DateHelper::compareNullableDates);
        if (maxDate.isPresent() && DateHelper.isEquals(maxDate.get(), commonData.plan.getEnd())) {
            //последний день по графику приходится на конец отчетного периода - всё ок
            return BonusPlanEmployeeWithoutBonusData.withBonus();
        }
        //теперь придется проверить производственный календарь
        CustomScheduledBean defaultSchedule = new CustomScheduledBean();
        defaultSchedule.setIsDefault(true);
        if (!EntityManager.load(defaultSchedule)) {
            //нет графика для производственного календаря
            return BonusPlanEmployeeWithoutBonusData.withBonus();
        }
        List<BonusWorkTimetableBean> defaultTimetables = getTimetables(defaultSchedule.getId(), maxDate.isPresent() ?
                DateHelper.addDaysToDate(maxDate.get(), 1) : afterDismissal);
        return containsNotNullValue(defaultTimetables) ?
                BonusPlanEmployeeWithoutBonusData.withoutBonus(messageNotBonus) :
                BonusPlanEmployeeWithoutBonusData.withBonus();
    }

    private List<BonusWorkTimetableBean> getTimetables(String scheduleRsId, Date from) {
        if (StringHelper.isEmpty(scheduleRsId) || DateHelper.isNull(from)) {
            return new ArrayList<>();
        }
        QueryData<SelectQuery> timetableQueryData = BonusSQL.ListWorkTimetablesWithSumByDates.create(
                scheduleRsId,
                from,
                commonData.plan.getEnd(),
                rsService.getRSId("По дням", WorkTimeAccountingKindRubricator.WORK_TIME_ACCOUNTING_KIND)
        );
        return EntityManager.list(timetableQueryData, BonusWorkTimetableBean.class);
    }

    private boolean containsNotNullValue(List<BonusWorkTimetableBean> timetables) {
        return timetables.stream().anyMatch(bean -> DoubleValue.isNotNull(bean.getMainValue()));
    }

    protected BonusPlanEmployeeWithoutBonusData isNotParticipantByMinValues() {
        if (commonData.model.getMinValues().getEntries().isEmpty()) {
            return BonusPlanEmployeeWithoutBonusData.withBonus();
        }
        //если значение любой заданной колонки меньше установленного для нее значения,
        // то участник получает тип "Не участник"
        return commonData.model.getMinValues().getEntries().stream()
                .filter(bean -> bean.getMinValuesType().isCanCheck(bean))
                .map(minValueBean -> {
                    Double checkingValue = minValueBean.getMinValuesType()
                            .getEmployeeValue(minValueBean, data.getEmployeeBean().getId());
                    return DoubleValue.isNotNull(checkingValue) && checkingValue < minValueBean.getMinValue()
                            ? BonusPlanEmployeeWithoutBonusData.withoutBonus(
                            Localized.format(BonusMessage.min_value_in_column_cause,
                                    minValueBean.getMinValuesType().getColumnName(minValueBean)).toString()
                    ) : BonusPlanEmployeeWithoutBonusData.withBonus();
                })
                .filter(BonusPlanEmployeeWithoutBonusData::isEmployeeWithoutBonus)
                .findFirst()
                .orElse(BonusPlanEmployeeWithoutBonusData.withBonus());
    }

    /**
     * Расчет премий для участника плана
     */
    protected void calculatePlanEmployeeBonus(BonusPlanEmployeeKindColumnValueCalculator kindColumnValueCalculator) {
        BonusKindRSBean modelKind = StringHelper.isNotEmpty(commonData.model.getKind().getId()) ?
                EntityManager.find(commonData.model.getKind().getId(), BonusKindRSBean.class) :
                new BonusKindRSBean();
        //Премия
        Double bonus = getBonus(modelKind);
        if (commonData.plan.getType().isCorrection()) {
            data.getEmployeeBean().setCorrectedPremium(bonus);
        } else {
            data.getEmployeeBean().setPremium(bonus);
        }
        //Премия с РК и ПН
        Double bonusRkPn = getBonusRkPn(modelKind);
        if (commonData.plan.getType().isCorrection()) {
            data.getEmployeeBean().setCorrectedPremiumRkPn(bonusRkPn);
        } else {
            data.getEmployeeBean().setPremiumWithRkPn(bonusRkPn);
        }

        processAfterCalculatingBonuses();
        kindColumnValueCalculator.calculateAfterGroup(BonusKindSystemColumnGroups.bonus);

        if (commonData.plan.getType().isCorrection() &&
                StringHelper.isNotEmpty(data.getEmployeeBean().getPreviousPlanEmployee().getId())) {
            BonusPlanEmployeeBean previous =
                    planService.getBonusPlanEmployee(data.getEmployeeBean().getPreviousPlanEmployee().getId());
            BonusPlanBean previousPlan = planService.getBonusPlan(previous.getPlanId());
            data.getEmployeeBean().setPremium(previousPlan.getType().isCorrection() ? previous.getCorrectedPremium() : previous.getPremium());
            data.getEmployeeBean().setPremiumWithRkPn(previousPlan.getType().isCorrection() ? previous.getCorrectedPremiumRkPn() : previous.getPremiumWithRkPn());
            data.getEmployeeBean().setPremiumDiff(DoubleValue.getDoubleValue(data.getEmployeeBean().getCorrectedPremium()) - DoubleValue.getDoubleValue(data.getEmployeeBean().getPremium()));
            data.getEmployeeBean().setPremiumRkPnDiff(DoubleValue.getDoubleValue(data.getEmployeeBean().getCorrectedPremiumRkPn()) - DoubleValue.getDoubleValue(data.getEmployeeBean().getPremiumWithRkPn()));
            processAfterCalculatingBonusDifference();
        }
        kindColumnValueCalculator.calculateAfterGroup(BonusKindSystemColumnGroups.bonus_difference);
    }

    protected Double getBonus(BonusKindRSBean modelKind) {
        if (!data.getEmployeeBean().getType().isParticipant()) {
            return DoubleValue.NULL;
        }
        String bonusFormula = commonData.model.getBonusFormulaDeterminingMethod().fromKind() ?
                modelKind.getFormulaBonus() :
                (data.isPostFilterSet() &&
                        data.getPostFilterBean()
                                .getModelPostFilter().getBonusFormulaDeterminingMethod().isFromGroupingFilters() ?
                        data.getPostFilterBean().getModelPostFilter().getBonusFormula() :
                        commonData.model.getBonusFormula());
        if (StringHelper.isNotEmpty(bonusFormula)) {
            return BonusFormulaType.bonus.calculate(createFormulaData(), bonusFormula);
        }
        return 0d;
    }

    protected Double getBonusRkPn(BonusKindRSBean modelKind) {
        if (!data.getEmployeeBean().getType().isParticipant()) {
            return DoubleValue.NULL;
        }
        String bonusCoefficientAllowanceFormula =
                commonData.model.getBonusCoefficientAllowanceFormulaDeterminingMethod().fromKind() ?
                modelKind.getFormulaBonusCoefficientAllowance() :
                (data.isPostFilterSet() &&
                        data.getPostFilterBean().getModelPostFilter()
                                .getBonusRkPnFormulaDeterminingMethod().isFromGroupingFilters() ?
                            data.getPostFilterBean().getModelPostFilter().getBonusRkPnFormula() :
                        commonData.model.getBonusCoefficientAllowanceFormula());
        if (StringHelper.isNotEmpty(bonusCoefficientAllowanceFormula)) {
            return BonusFormulaType.bonus_coefficient_allowance.calculate(
                createFormulaData(), bonusCoefficientAllowanceFormula);
        }
        return 0d;
    }

    private BonusPlanEmployeeBonusCalcData createFormulaData() {
        return new BonusPlanEmployeeBonusCalcData(
            data.getEmployeeBean(),
            new BonusEmployeeAccrualWorkTimeBean(),
            data.isChangeable(),
            data.getChanges()
        );
    }

    //--------------------Работа с kpi

    protected BonusPlanEmployeeKPICalculator createKpiCalculator() {
        return kpiCalculatorCreator.createCalculator(commonData, data);
    }

    //--------------------Работа с колонками, задаваемыми в значении вида премии

    protected BonusPlanEmployeeKindColumnValueCalculator createKindColumnCalculator() {
        BonusPlanEmployeeKindColumnValueCalculator calculator =
            new BonusPlanEmployeeKindColumnValueCalculator(commonData, data)
                .setPlanEmployeeFieldIdsToCalculate(planEmployeeFieldIdsToCalculate);
        if (ignoreColumnsFilledByCode) {
            calculator.ignoreColumnsFilledByCode();
        }
        return calculator;
    }

    //--------------------Работа с колонками, относящимися к непрерывному периоду работы

    protected BonusPlanEmployeeContinuousPeriodCalculator createContinuousPeriodCalculator() {
        return new BonusPlanEmployeeContinuousPeriodCalculator(commonData, data);
    }

    //------------------Создание общих данных
    /**
     * @return общие данные, которые можно рассчитать один раз для всех участников плана
     */
    public BonusPlanEmployeeCalculatorCommonData createCommonData(
            BonusPlanBean plan,
            BonusModelBean model,
            PeriodPhaseMethod careerPeriodType
    ) {
        BonusKindRSBean kind = bonusModelService.getBonusKindByModelId(model.getId());
        BonusPlanEmployeeGridSettings settings = columnService.getGridSettingsByBonusPlan(plan);
        List<BonusPlanForecastGroupCategoryKPIValueBean> groupCategoryValues = plan.getType().isForecasting() ?
                forecastKPIValueService.listPlanGroupCategoryValues(plan.getId()) :
                new ArrayList<>();
        List<BonusPlanForecastCategoryKPIValueBean> categoryValues = plan.getType().isForecasting() ?
                forecastKPIValueService.listPlanCategoryValues(plan.getId()) :
                new ArrayList<>();
        return new BonusPlanEmployeeCalculatorCommonData(
                settings,
                plan,
                model,
                kind,
                groupCategoryValues,
                categoryValues,
                careerPeriodType
        );
    }
}
