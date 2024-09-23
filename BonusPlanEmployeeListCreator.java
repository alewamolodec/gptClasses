package hr.bonus.plan.employee;

import hr.bonus.BonusMessage;
import hr.bonus.BonusModule;
import hr.bonus.BonusRepository;
import hr.bonus.BonusSQL;
import hr.bonus.model.BonusModelBean;
import hr.bonus.model.BonusModelService;
import hr.bonus.model.CareerFilterQueryBuilder;
import hr.bonus.model.post.BonusModelPostBean;
import hr.bonus.model.post.BonusModelPostFilterVirtualBean;
import hr.bonus.model.post.filter.BonusModelPostFilterBean;
import hr.bonus.model.post.filter.BonusModelPostFilterQueryBuilder;
import hr.bonus.model.post.person.PersonBonusModelVirtualBean;
import hr.bonus.plan.BonusPlanBean;
import hr.bonus.plan.BonusPlanService;
import hr.bonus.plan.employee.task.*;
import hr.bonus.plan.order.employee.BonusPlanOrderEmployeePlanLinkBean;
import hr.bonus.responsibilitycenter.BonusAdditionalAppointmentsBean;
import hr.bonus.vv.BonusKindCodeService;
import lms.core.person.PersonBean;
import lms.core.person.career.CareerBean;
import lms.core.person.career.CareerPersonIdBean;
import lms.core.qua.QuaMessage;
import mira.task.TaskService;
import mira.task.log.TaskLogBean;
import org.apache.commons.lang3.tuple.Pair;
import org.mirapolis.data.bean.BeanHelper;
import org.mirapolis.lock.LockService;
import org.mirapolis.mvc.model.entity.EntityListenerService;
import org.mirapolis.mvc.model.grid.SortDirection;
import org.mirapolis.orm.DataObject;
import org.mirapolis.orm.EntityManager;
import org.mirapolis.orm.ORM;
import org.mirapolis.orm.paging.BeanQueryPagingIterator;
import org.mirapolis.service.message.Localized;
import org.mirapolis.service.spring.BeanFactory;
import org.mirapolis.sql.*;
import org.mirapolis.sql.fragment.*;
import org.mirapolis.task.ProducerConsumerTask;
import org.mirapolis.util.CollectionUtils;
import org.mirapolis.util.DateHelper;
import org.mirapolis.util.StringHelper;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.mirapolis.sql.fragment.Column.column;
import static org.mirapolis.sql.fragment.Parameter.parameter;
import static org.mirapolis.sql.fragment.Table.table;
import static org.mirapolis.util.BooleanHelper.falseIfNull;
import static org.mirapolis.util.CollectionUtils.newArrayList;
import static org.mirapolis.util.CollectionUtils.newUnorderedSet;
import static org.mirapolis.util.StringHelper.*;

/**
 * Вспомогательный класс для создания и обработки списка участников плана премирования
 * Код перенесен из BonusPlanEmployeeTask
 *
 * @author Zlata Zanina
 * @since 18.10.2019
 */
public class BonusPlanEmployeeListCreator {
    
    private static final String CAREER_ALIAS = "C";
    private static final String CAREERS_SELECT_QUERY_ALIAS = "TM";
    private static final String TOP_ROW_BP_EMPLOYEE_TO_CLEAR_QUERY_ALIAS = "TMP";

    @Autowired
    protected BonusPlanService planService;
    @Autowired
    private BonusModelService bonusModelService;
    @Autowired
    private EntityListenerService entityListenerService;
    @Autowired
    private TaskService taskService;
    @Autowired
    protected ORM orm;
    @Autowired
    private BonusKindCodeService codeService;
    @Autowired
    private LockService lockService;
    @Autowired
    private BonusRepository bonusRepository;
    @Autowired
    private BonusModelService modelService;
    @Autowired
    private SQLStore sqlStore;

    protected BonusPlanBean plan;
    protected BonusModelBean model;

    protected BonusPlanEmployeeListStrategy strategy;

    //количество потоков-потребителей при обработке карьер
    private static final int CONSUMERS_TASKS_NUMBER = 5;
    private static final String EMPTY_ID = "-1";
    
    /**
     * Если непустой, то пересчитываются только эти записи по участникам плана
     */
    private Set<String> checkedBpEmployeeIds;
    private Set<String> checkedPersonIds;

    //фильтры модели премирования по должностям, которые подходят под эти фильтры
    private Map<String, BonusModelPostFilterVirtualBean> filtersByPosts;

    private final BonusPlanEmployeeCalculatorCommonData commonData;

    private final Set<String> errors = new LinkedHashSet<>();
    private final Optional<BonusPlanPeriodPhaseExecuteCode> periodPhaseCalculator;

    /**
     * @param plan     план, по которому рассчитываем
     * @param strategy стратегия, определяющая дополнительные детали поведения
     */
    public BonusPlanEmployeeListCreator(BonusPlanBean plan, BonusPlanEmployeeListStrategy strategy) {
        this.plan = plan;
        this.strategy = strategy;

        BeanFactory.autoWire(this);

        model = bonusModelService.getBonusModel(plan.getModel().getId());
        bonusModelService.getProcessors().forEach(processor ->
                processor.updateBonusModels(CollectionUtils.newArrayList(model)));
        periodPhaseCalculator =
            codeService.getOrCreatePeriodPhaseExecuteCode(planService.getBonusKindByPlan(plan.getId()));
        commonData = createCommonData();
    }

    /**
     * @param checkedPersonIds физлица, по которым будем рассчитывать.
     *                         Если не заданы, рассчитываем по всем, которым положены премии
     */
    public void setCheckedPersonIds(Set<String> checkedPersonIds) {
        this.checkedPersonIds = checkedPersonIds;
    }
    
    public void setCheckedBpEmployeeIds(Set<String> checkedBpEmployeeIds) {
        this.checkedBpEmployeeIds = checkedBpEmployeeIds;
    }
    
    /**
     * @return сообщения об ошибках, накопленные в ходе работы
     */
    public Set<String> getErrors() {
        return errors;
    }

    /**
     * Запуск расчетов
     */
    public void doRun() {
        if (commonData.getKind().getBonusCalcMethod().isForPeriod()
                && DateHelper.isNull(plan.getStart()) && DateHelper.isNull(plan.getEnd()) ||
                commonData.getKind().getBonusCalcMethod().isOnDate() && DateHelper.isNull(plan.getDateCalculation())) {
            //Способ расчета премии = За период, и Не задан отчетный период
            errors.add(Localized.group(QuaMessage.profile_not_specified, Localized.valueOf(": "),
                    BonusMessage.reporting_period).toString());
            return;
        }
        Date start = plan.getStart();
        Date end = plan.getEnd();
        if (commonData.getKind().getBonusCalcMethod().isOnDate()) {
            //Если «Способ расчета премии» = «На дату», то везде, где сейчас используется период из поля
            // «Отчетный период» из карточки плана, использовать период с даты из поля «Дата расчета»
            // из карточки плана по ту же дату.
            plan.setStart(plan.getDateCalculation());
            plan.setEnd(plan.getDateCalculation());
        }
        filtersByPosts = getPostFiltersByPosts();

        if (plan.getType().isCorrection()) {
            processCorrectionPlan();
        } else {
            processParticipant();
        }

        //восстанавливаем исходные значения
        plan.setStart(start);
        plan.setEnd(end);
        processAfterListCreated();
    }

    /**
     * Здесь можно разместить дополнительные действия после окончания формирования списка, если требуется
     */
    protected void processAfterListCreated() {

    }

    /**
     * Выполлняется для планов корректировки
     */
    private void processCorrectionPlan() {
        if (plan.getPlansForCorrect().isEmpty()) {
            return;
        }
        //добавляем участников из планов для корректировки
        processEmployeesFromPlansForCorrection();
    }

    /**
     * Собирает сотрудников из планов для корректировки
     */
    private void processEmployeesFromPlansForCorrection() {
        //сначала из планов премирования для корректировки получаем всех участников, у которых статус приказа
        // = "Приказ подписан, требуется корректировка",
        //у которых для связи "физлицо-табельный номер" нет подписанных приказов за тот же
        // период с той же моделью премирования
        //и у которых совпадает "табельный номер" и "текущий табельный номер" и нет связанных
        // (по текущему табельному номеру или по табельному номеру) записей, в которых бы не совпадал
        List<BonusPlanEmployeeBean> employeesWithCorrectionOrderStatuses = EntityManager.list(
                addCheckedPersonIdFilterToPlanEmployeeQuery(
                        BonusSQL.ListBonusPlanEmployeesForCorrection.create(plan.getId())),
                BonusPlanEmployeeBean.class);
        //разложим по карьерам
        Map<String, List<BonusPlanEmployeeBean>> employeesByCareer = employeesWithCorrectionOrderStatuses.stream()
                .collect(Collectors.groupingBy(BonusPlanEmployeeBean::getCareerId));
        //если по одной карьере есть несколько участников, нужно выбрать и оставить тех,
        // у которых одинаковое максимальное время и дата изменения статуса приказа (= время формирования плана)
        List<BonusPlanEmployeeBean> correctionEmployees = employeesByCareer.entrySet().stream()
                .map(this::getSuitableEmployeeForCareer).flatMap(Collection::stream).collect(Collectors.toList());
        Map<String, List<BonusPlanEmployeeBean>> previousEmployeesByCareer = correctionEmployees.stream()
                .collect(Collectors.groupingBy(BonusPlanEmployeeBean::getCareerId));

        //создадим новых участников
        Set<String> allCareerIds = new HashSet<>();
        correctionEmployees.forEach(correctionEmployee -> allCareerIds.addAll(
                processCorrectionForPreviousEmployee(correctionEmployee, previousEmployeesByCareer)));
        //пройдем по всем существующим участникам плана корректировки и обновим данные по ним.
        processExistingCorrectionEmployees(allCareerIds);
    }

    /**
     * Проходим по тем участникам, которые уже были в плане, но при новом формировании было определено,
     * что они бы не были бы созданы
     *
     * @param selectedCareerIds карьеры, обработанные по основному алгоритму формирования участников корректировки
     */
    private void processExistingCorrectionEmployees(Set<String> selectedCareerIds) {
        SelectQuery query = orm.getDataObject(BonusPlanEmployeeBean.class).getQueryWithLookups();
        query.where(column(DataObject.PARENT_ALIAS, BonusPlanEmployeeBean.PLAN_ID).eq(parameter()));
        QueryData<SelectQuery> queryData = QueryData.fromQuery(query).addIntParam(plan.getId());
        queryData = addCheckedPersonIdFilterToPlanEmployeeQuery(queryData);
        BeanQueryPagingIterator<BonusPlanEmployeeBean> iterator = BeanQueryPagingIterator.create(
                Pager.defaultLimit(), new SelectQueryData(queryData), BonusPlanEmployeeBean.class);
        for (List<BonusPlanEmployeeBean> beans : iterator) {
            Set<String> careerIds = BeanHelper.getValueSet(beans, BonusPlanEmployeeBean.CAREER_ID);
            Map<String, CareerBean> careers = BeanHelper.createMapByPrimaryKey(EntityManager
                    .list(CareerBean.class, careerIds), CareerBean.ID);
            //Обновляем и делаем неактивными тех, чьи "предки" не попали в выборку
            beans.stream().filter(employeeBean -> !selectedCareerIds.contains(employeeBean.getCareerId()))
                    .forEach(bean -> {
                        CareerBean careerBean = careers.get(bean.getCareerId());
                        BonusPlanEmployeeWithoutBonusData withoutBonus = isEmployeeWithoutBonus(
                                careerBean, bean.getPerson().getId());
                        processPlanEmployeeCareer(
                                bean.getCareerId(),
                                bean.getPerson().getId(),
                                bean,
                                withoutBonus,
                                false,
                                bean.getPreviousPlanEmployee().getId(),
                                filtersByPosts.get(careerBean.getCaPost().getId()),
                                Optional.empty()
                        );
                    });
        }
    }

    /**
     * @return мапа id должности - фильтр, по которому она подходит к модели премирования
     */
    private Map<String, BonusModelPostFilterVirtualBean> getPostFiltersByPosts() {
        Map<String, BonusModelPostFilterBean> filtersByFilterId = bonusModelService.getBonusModelPostFilters(
                model.getId()).stream().collect(Collectors.toMap(BonusModelPostFilterBean::getId, bean -> bean));
        bonusModelService.getProcessors().forEach(processor ->
                processor.updateBonusModelPostFilters(filtersByFilterId.values()));
        BonusModelPostFilterQueryBuilder queryBuilder = new BonusModelPostFilterQueryBuilder();
        if (!commonData.getKind().getEmployeeFormingMethod().isByModel()) {
            Map<String, BonusModelPostFilterVirtualBean> filtersByPostId = new HashMap<>();
            filtersByFilterId.values().stream()
                    .sorted(Comparator.comparing(
                            BonusModelPostFilterBean::getOrder, Comparator.nullsFirst(Comparator.reverseOrder())))
                    .forEach(o ->
                            queryBuilder.getPostIds(
                                    o.getFilter()).forEach(
                                            postid -> filtersByPostId.put(postid,
                                                    new BonusModelPostFilterVirtualBean(o, new BonusModelPostBean()))
                            )
                    );
            return filtersByPostId;
        } else {
            return EntityManager.stream(BonusSQL.ListBonusModelPost.create(model.getId()), BonusModelPostBean.class)
                    .collect(Collectors.toMap(bean -> bean.getPost().getId(), bean ->
                                    new BonusModelPostFilterVirtualBean(
                                            filtersByFilterId.get(bean.getModelFilter().getId()), bean),
                            (f, s) -> f));
        }
    }

    /**
     * @return набор карьер, которые были отобраны для формирования списка участников
     */
    private Set<String> processCorrectionForPreviousEmployee(
            BonusPlanEmployeeBean correctionEmployee,
            Map<String, List<BonusPlanEmployeeBean>> previousEmployeesByCareer) {
        //выбираем все карьеры участника для корректировки, в которых тот же табельный номер
        List<CareerBean> careers = getCareersForCorrection(
                correctionEmployee.getId(),
                correctionEmployee.getPerson().getId(),
                correctionEmployee.getTabNumber());

        if (commonData.getKind().getEmployeeFormingMethod().isByModel()) {
            processCorrectionEmployeeByModel(
                    careers,
                    correctionEmployee.getPerson().getId(),
                    previousEmployeesByCareer);
        } else {
            processCorrectionEmployeeByFilter(
                    careers,
                    correctionEmployee.getPerson().getId(),
                    previousEmployeesByCareer);
        }
        return BeanHelper.getIdSet(careers);
    }

    /**
     * Запускаем, когда в виде премии плана корректировки указано, что формируем по модели
     */
    private void processCorrectionEmployeeByModel(
            List<CareerBean> careers,
            String personId,
            Map<String, List<BonusPlanEmployeeBean>> previousEmployeesByCareer) {
        Map<String, List<PersonBonusModelVirtualBean>> periodsByCareer = getPeriodsByCareer(personId);
        if (!falseIfNull(
                commonData.getKind().getFormByCareers())
                && careers.size() == 1) {
            //если формируем не по всем карьерам, оставим толькот период с наибольшей датой начала
            CareerBean careerBean = careers.get(0);
            List<PersonBonusModelVirtualBean> periods = periodsByCareer.containsKey(careerBean.getId()) ?
                    periodsByCareer.get(careerBean.getId()) : new ArrayList<>();
            Optional<PersonBonusModelVirtualBean> period = periods.stream()
                    .max(Comparator.comparing(PersonBonusModelVirtualBean::getIntersectionStart));
            periodsByCareer.put(careerBean.getId(), period.isPresent() ?
                    CollectionUtils.newArrayList(period.get()) : new ArrayList<>());
        }
        //Участники плана по ID карьеры
        Map<String, List<BonusPlanEmployeeBean>> planEmployeeByCareerId = getPlanEmployeesByPersonIdGroupByCareer(
                CollectionUtils.newUnorderedSet(personId));
        careers.stream().forEach(careerBean -> processPersonCareer(
                personId,
                careerBean,
                planEmployeeByCareerId,
                periodsByCareer,
                previousEmployeesByCareer.containsKey(careerBean.getId()) ?
                        previousEmployeesByCareer.get(careerBean.getId()) : new ArrayList<>()
        ));
    }

    /**
     * Запускаем, когда в виде премии плана корректировки указано, что формируем по фильтрам
     */
    private void processCorrectionEmployeeByFilter(List<CareerBean> careers,
                                                   String personId,
                                                   Map<String, List<BonusPlanEmployeeBean>> previousEmployeesByCareer) {
        //обернем карьеры в виртуальные карьеры
        List<CareerPersonIdBean> virtualCareers = CollectionUtils.transformList(careers, careerBean -> {
            CareerPersonIdBean career = new CareerPersonIdBean();
            career.setCareer(careerBean);
            career.setPersonId(personId);
            return career;
        });
        Map<String, String> previousEmployeeIdByCareer = previousEmployeesByCareer.keySet().stream()
                .filter(careerId -> !previousEmployeesByCareer.get(careerId).isEmpty())
                .collect(Collectors.toMap(cereerId -> cereerId,
                        careerId -> previousEmployeesByCareer.get(careerId).get(0).getId()));
        processCareerList(virtualCareers, previousEmployeeIdByCareer);
    }

    /**
     * @return карьеры, по которым будем создавать/обновлять участников плана корректировки
     * по указанной связке физлицо-табномер
     */
    private List<CareerBean> getCareersForCorrection(
            String employeeId,
            String personId,
            String tabNumber) {
        boolean tabNumberIsEmpty = isEmpty(tabNumber);
        String usingTabNumber = tabNumberIsEmpty ? "emptyTabNumber" : tabNumber;
        List<CareerBean> oldCareers = getOldCareersForCorrection(
                personId, tabNumberIsEmpty, usingTabNumber);
        List<CareerBean> newCareers = getNewCareersForCorrection(
                employeeId,
                personId, tabNumberIsEmpty, usingTabNumber);

        List<CareerBean> allCareers = CollectionUtils.newArrayList(oldCareers, newCareers);

        if (!falseIfNull(commonData.getKind().getFormByCareers())) {
            //если формируем не по всем карьерам, выбираем одну
            //с наиболее поздней датой начала, либо наиболее поздней датой увольнения (пустые - наибольшие),
            // либо с наибольшим id
            Optional<CareerBean> career = allCareers.stream().max((o1, o2) -> {
                int emplDatesCompare = o1.getEmploymentDate().compareTo(o2.getEmploymentDate());
                if (emplDatesCompare != 0) {
                    return emplDatesCompare;
                }
                int disDatesCompare = DateHelper.compareNullableDatesWithNullFirst(
                        o1.getDismissalDate(), o2.getDismissalDate());
                if (disDatesCompare != 0) {
                    return disDatesCompare;
                }
                return Integer.valueOf(o1.getId()).compareTo(Integer.valueOf(o2.getId()));
            });
            return career.isPresent() ? CollectionUtils.newArrayList(career.get()) : new ArrayList<>();
        }

        return allCareers;
    }

    private List<CareerBean> getOldCareersForCorrection(
            String personId,
            boolean tabNumberIsEmpty,
            String tabNumber) {
        return commonData.getKind().getBonusCalcMethod().isForPeriod() ?
                //по периоду
                bonusRepository.listOldCareersForCorrectionByPersonAndTabNumberByPeriod(
                        plan.getId(),
                        personId,
                        tabNumberIsEmpty,
                        tabNumber,
                        commonData.getKind().getEmployeeFormingMethod().isByModel()
                ) :
                //по дате
                bonusRepository.listOldCareersForCorrectionByPersonAndTabNumberByCalcDate(
                        plan.getId(),
                        personId,
                        tabNumberIsEmpty,
                        tabNumber,
                        commonData.getKind().getEmployeeFormingMethod().isByModel()
                );
    }

    private List<CareerBean> getNewCareersForCorrection(
            String employeeId,
            String personId,
            boolean tabNumberIsEmpty,
            String tabNumber) {
        //по этим карьерам нет участников в планах для корректировки, но они подходят по фильтрам и Центру
        SelectQueryData queryData = commonData.getKind().getBonusCalcMethod().isForPeriod() ?
                //по периоду
                bonusRepository.listNewCareersForCorrectionByPersonAndTabNumberByPeriod(
                        plan.getId(),
                        personId,
                        tabNumberIsEmpty,
                        tabNumber,
                        commonData.getKind().getEmployeeFormingMethod().isByModel()
                ) :
                //по дате
                bonusRepository.listNewCareersForCorrectionByPersonAndTabNumberByCalcDate(
                        plan.getId(),
                        personId,
                        tabNumberIsEmpty,
                        tabNumber,
                        commonData.getKind().getEmployeeFormingMethod().isByModel()
                );
        Optional<BonusPlanBean> planBeanOpt = bonusRepository.getMostPreviousPlan(employeeId);
        if (planBeanOpt.isPresent()) {
            BonusPlanBean planBean = EntityManager.find(planBeanOpt.get().getId(), BonusPlanBean.class);
            addAllFilters(queryData, planBean);
        }
        return EntityManager.list(queryData, CareerBean.class);
    }

    /**
     * @return true, если физлицо подходит под фильтры подели или набора параметров премирования
     * с вкладки "Сотрудники, которым не положено премирование"
     */
    private BonusPlanEmployeeWithoutBonusData isEmployeeWithoutBonus(CareerBean careerBean,
                                                                     String personId) {
        if (isEmpty(careerBean.getCaPost().getId())) {
            return BonusPlanEmployeeWithoutBonusData.withBonus();
        }
        BonusModelPostFilterVirtualBean filterBean = filtersByPosts.get(careerBean.getCaPost().getId());
        return filterBean == null || filterBean.getModelPostFilter() == null ?
                BonusPlanEmployeeWithoutBonusData.withBonus() :
                isEmployeeWithoutBonus(careerBean, personId, filterBean.getModelPostFilter());
    }

    /**
     * Если для участника плана корректировки мы нашли несколько участников разных планов ДЛЯ корректировки,
     * то отбираем из того плана, который формировался позже всех
     */
    private List<BonusPlanEmployeeBean> getSuitableEmployeeForCareer(
            Map.Entry<String, List<BonusPlanEmployeeBean>> entry) {
        if (entry.getValue().size() == 1) {
            return entry.getValue();
        }
        Set<String> plans = BeanHelper.getValueSet(entry.getValue(), BonusPlanEmployeeBean.PLAN_ID);
        Map<String, TaskLogBean> lastTasks = taskService.getLastTaskLogsForTasks(plans, BonusPlanEmployeeTask.class);
        //найдем план, у которого позже всех был сформирован список
        Map.Entry<String, TaskLogBean> latestEntry = lastTasks.entrySet().stream()
                .max((o1, o2) ->
                        DateHelper.compareNullableDates(o1.getValue().getEndTime(), o2.getValue().getEndTime()))
                .orElse(null);
        return latestEntry == null ? entry.getValue() :
                entry.getValue().stream()
                        .filter(planEmployeeBean -> planEmployeeBean.getPlanId().equals(latestEntry.getKey()))
                        .collect(Collectors.toList());
    }

    /**
     * Формирование участников плана
     */
    private void processParticipant() {
        if (commonData.getKind().getEmployeeFormingMethod().isByModel()) {
            processParticipantByModel();
        } else {
            processParticipantByFilters();
        }

        //Существующие участники, которые больше не подходят:
        //Участники, которые больше не подходят под фильтры или центр премирования
        if (!strategy.planHasNoFilters()) {
            processNotActiveParticipants(addCheckedPersonIdFilterToPlanEmployeeQuery(
                    getPlanEmployeeNotActiveByFilter()));
        }
    }

    /**
     * Формирование первичного или прогнозного плана по модели
     */
    private void processParticipantByModel() {
        boolean emptyCheckedPersons = CollectionUtils.isEmpty(checkedPersonIds);
        SelectQueryData queryData = bonusRepository.listPersonsForBonusPlanByAllConditions(
                plan.getId(),
                emptyCheckedPersons,
                emptyCheckedPersons ? CollectionUtils.newArrayList("-1") : checkedPersonIds
        );
        addBonusCenterFilter(queryData);
        strategy.addFiltersToCareerQuery(queryData, plan);
        //выбираем всех физлиц, которым положено премирование по штатной должности или по индивидуальным условиям
        //плюс всех физлиц, по которым уже сформированы участники
        BeanQueryPagingIterator<PersonBean> personIterator = new BeanQueryPagingIterator<>(
                Pager.dbLimit(),
                queryData,
                PersonBean.class
        );
        runProducerConsumerTask("Persons", personIterator, this::processPersons);
    }

    /**
     * Для каждого физлица подбираем подходящие под премирование карьеры и периоды премирования,
     * затем обрабатываем подобранные карьеры и периоды
     */
    private void processPersons(List<PersonBean> personBeans) {
        Set<String> personIds = BeanHelper.getIdSet(personBeans);
        Map<String, List<BonusPlanEmployeeBean>> planEmployeeByIds = getPlanEmployeesByPersonIds(personIds);
        for (String personId : personIds) {
            Map<String, List<BonusPlanEmployeeBean>> employeesByCareer = planEmployeeByIds.containsKey(personId) ?
                    BeanHelper.createMapFromListByFK(planEmployeeByIds.get(personId), BonusPlanEmployeeBean.CAREER_ID) :
                    new HashMap<>();
            Map<String, List<PersonBonusModelVirtualBean>> periodsByCareer = getPeriodsByCareer(personId);
            Set<String> careerIds = Stream.concat(employeesByCareer.keySet().stream(),
                    periodsByCareer.keySet().stream()).collect(Collectors.toSet());
            SelectQueryData queryData = new CareerFilterQueryBuilder().getCareersIdsQuery(careerIds);
            strategy.addFiltersToCareerQuery(queryData, plan);
            List<CareerBean> careers = EntityManager.list(queryData, CareerBean.class);
            //по каждой карьере обрабатываем отдельно
            careers.forEach(career -> processPersonCareer(
                    personId,
                    career,
                    employeesByCareer,
                    periodsByCareer,
                    new ArrayList<>()
            ));
        }
    }

    /**
     * По карьерам подбираем периоды премирования (общие и индивидуальные)
     */
    private Map<String, List<PersonBonusModelVirtualBean>> getPeriodsByCareer(String personId) {
        String centerId = plan.getBonusCenter().getId();
        String planId = plan.getId();
        boolean centerIdIsNull = isEmpty(centerId);
        if (centerIdIsNull) {
            centerId = EMPTY_ID;
        }
        List<PersonBonusModelVirtualBean> commonPeriods = planService.cropByBonusPlanDates(
                plan,
                bonusRepository.listCommonPersonPremiumPeriods(
                        personId,
                        planId,
                        centerIdIsNull,
                        centerId
                )
        );
        List<PersonBonusModelVirtualBean> individualPeriods = planService.cropByBonusPlanDates(
            plan,
            bonusRepository.listIndividualPersonPremiumPeriods(personId, planId, centerIdIsNull, centerId)
        );

        return planService.getPeriodsByCareer(
            commonData.getKind().getId(),
            personId,
            commonPeriods,
            individualPeriods);
    }

    /**
     * Обрабатываем карьеру физлица, создавая участников плана премирования
     * по общим и индивидуальным периодам премирования
     */
    private void processPersonCareer(String personId,
                                     CareerBean career,
                                     Map<String, List<BonusPlanEmployeeBean>> employeesByCareer,
                                     Map<String, List<PersonBonusModelVirtualBean>> periodsByCareer,
                                     List<BonusPlanEmployeeBean> previousEmployees
    ) {
        String careerId = career.getId();
        List<BonusPlanEmployeeBean> employees = employeesByCareer.getOrDefault(careerId, new ArrayList<>());

        List<PersonBonusModelVirtualBean> periods =
                periodsByCareer.getOrDefault(careerId, new ArrayList<>())
                        .stream()
                        .filter(period -> DateHelper.isNull(career.getDismissalDate())
                                || DateHelper.beforeOrEquals(period.getStart(), career.getDismissalDate())
                        ).collect(Collectors.toList());

        sortByStartDate(employees);
        sortByStartDate(previousEmployees);
        Collections.sort(periods, (o1, o2) -> DateHelper.compareNullableDates(o1.getStart(), o2.getStart()));

        //Если нет общих и индивидуальных периодам премирования, то все участники по карьере
        // переводяться в статус "Не участник"
        if (periods.isEmpty()) {
            processNoEmployeeWithoutPeriod(personId, career, employees);
            return;
        }

        if (periodPhaseCalculator.isPresent() && !plan.getType().isCorrection()) {
            processEmployeeCareerPhaseByPeriod(personId, careerId, employees, periods);
            return;
        }
        processEmployeeCareerByPeriod(personId, career, employees, periods, previousEmployees);
    }

    /**
     * Обрабатываем карьеру физлица, создавая участников плана премирования
     * по общим и индивидуальным периодам премирования
     */
    private void processEmployeeCareerByPeriod(
            String personId,
            CareerBean career,
            List<BonusPlanEmployeeBean> employees,
            List<PersonBonusModelVirtualBean> periods,
            List<BonusPlanEmployeeBean> previousEmployees
    ) {
        int maxIndex = Math.max(employees.size(), periods.size());
        //сопоставляем участников плана с вычисленными периодами премирования
        for (int i = 0; i < maxIndex; i++) {
            Optional<BonusPlanEmployeeBean> previousEmployee = CollectionUtils.findByIndex(previousEmployees, i);
            Optional<PersonBonusModelVirtualBean> period = CollectionUtils.findByIndex(periods, i);

            boolean isIndividualByModel = period.isPresent()
                    && period.get().getCondition().getConditionKind() != null
                    && period.get().getCondition().getConditionKind().isByBonusModel();

            //индивидуальный по модели не может быть без премии
            BonusPlanEmployeeWithoutBonusData withoutBonusData = isEmployeeWithoutBonus(career, personId);
            boolean isWithoutBonus = !isIndividualByModel &&
                    withoutBonusData.isEmployeeWithoutBonus();

            BonusModelPostFilterVirtualBean postFilterBean = isIndividualByModel
                    && StringHelper.isNotEmpty(period.get().getCondition().getPostFilter().getId())
                    ? new BonusModelPostFilterVirtualBean(
                    modelService.getBonusModelPostFilter(period.get().getCondition().getPostFilter().getId()),
                    new BonusModelPostBean())
                    : filtersByPosts.get(career.getCaPost().getId());

            boolean isActive = period.isPresent() //существует период с тем же порядковым номером
                    && (!isWithoutBonus //не подходит под фильтры "не положена премия"
                    //или подходит под фильтры, но стоит чекбокс "строить по тем, кто попадает под фильтры"
                    || falseIfNull(model.getFormPlanEmployeeNotSupposedBonus()));
            processPlanEmployeeCareer(
                    career.getId(),
                    personId,
                    CollectionUtils.findByIndex(employees, i).orElse(null),
                    withoutBonusData,
                    isActive,
                    previousEmployee.orElse(new BonusPlanEmployeeBean()).getId(),
                    postFilterBean,
                    period
            );
        }
    }

    /**
     * Все участники плана по карьере переводятся в статус "Не участник"
     */
    private void processNoEmployeeWithoutPeriod(
            String personId,
            CareerBean career,
            List<BonusPlanEmployeeBean> employees
    ) {
        BonusPlanEmployeeWithoutBonusData withoutBonusData = isEmployeeWithoutBonus(career, personId);
        BonusModelPostFilterVirtualBean postFilterBean = filtersByPosts.get(career.getCaPost().getId());
        employees.forEach(employee -> {
            processPlanEmployeeCareer(
                    career.getId(),
                    personId,
                    employee,
                    withoutBonusData,
                    false,
                    "",
                    postFilterBean,
                    Optional.empty()
            );
        });
    }

    /**
     * Обрабатываем карьеру физлица, создавая участников плана премирования
     * по общим и индивидуальным периодам премирования с выполняемым кодомдля формирования подпериодов
     *
     * Одновременно может строиться участник плана толькопо одной карьера в паре "физлицо - табельный номер".
     * Для этого используется блокировка
     * Это нужно для корректного изменения статусов приказа
     */
    private void processEmployeeCareerPhaseByPeriod(
            String personId,
            String careerId,
            List<BonusPlanEmployeeBean> employees,
            List<PersonBonusModelVirtualBean> periods
    ) {
        CareerBean careerBean = new CareerBean();
        careerBean.setId(careerId);
        EntityManager.load(careerBean);

        List<EmployeeCareerBonusPeriod> careerBonusPeriods =
                createSortEmployeeCareerBonusPeriodList(careerBean, periods);

        int maxIndex = Math.max(employees.size(), careerBonusPeriods.size());
        //сопоставляем участников плана с вычисленными периодами премирования
        for (int i = 0; i < maxIndex; i++) {
            Optional<BonusPlanEmployeeBean> employee = CollectionUtils.findByIndex(employees, i);
            Optional<EmployeeCareerBonusPeriod> careerBonusPeriod = CollectionUtils.findByIndex(careerBonusPeriods, i);

            boolean isIndividualByModel = careerBonusPeriod.map(careerPeriod ->
                    careerPeriod.getPeriod().getCondition().getConditionKind() != null
                            && careerPeriod.getPeriod().getCondition().getConditionKind().isByBonusModel()
            ).orElse(false);
            //индивидуальный по модели не может быть без премии
            BonusPlanEmployeeWithoutBonusData withoutBonusData = isEmployeeWithoutBonus(careerBean, personId);
            boolean isWithoutBonus = !isIndividualByModel && withoutBonusData.isEmployeeWithoutBonus();

            String postFilterId = careerBonusPeriod
                    .map(careerPeriod -> careerPeriod.getPeriod().getCondition().getPostFilter().getId())
                    .orElse("");
            BonusModelPostFilterVirtualBean postFilterBean =
                    isIndividualByModel && StringHelper.isNotEmpty(postFilterId)
                            ? getModelPostFilter(postFilterId)
                            : filtersByPosts.get(careerBean.getCaPost().getId());

            boolean isActive = careerBonusPeriod.isPresent()
                    && (!isWithoutBonus //не подходит под фильтры "не положена премия"
                    //или подходит под фильтры, но стоит чекбокс "строить по тем, кто попадает под фильтры"
                    || falseIfNull(model.getFormPlanEmployeeNotSupposedBonus()));
            lockService.execWithLock(personId + "_" + careerBean.getTabNumber(),
                    () -> processPlanEmployeeCareerWithinLock(
                            careerBonusPeriod.map(EmployeeCareerBonusPeriod::getCareer).orElse(careerBean),
                            personId,
                            employee.orElse(null),
                            withoutBonusData,
                            isActive,
                            "",
                            postFilterBean,
                            careerBonusPeriod.map(EmployeeCareerBonusPeriod::getPeriod)
                    ));
        }
    }

    private BonusModelPostFilterVirtualBean getModelPostFilter(String postFilterId) {
        return new BonusModelPostFilterVirtualBean(
                modelService.getBonusModelPostFilter(postFilterId),
                new BonusModelPostBean()
        );
    }

    /**
     * Получение отсортированного списка карьер с периодами.
     * Элементы сортируются по дате начала карьеры в порядке возрастания
     */
    private List<EmployeeCareerBonusPeriod> createSortEmployeeCareerBonusPeriodList(
            CareerBean careerBean,
            List<PersonBonusModelVirtualBean> periods
    ) {
        List<EmployeeCareerBonusPeriod> result = periods.stream()
                .map(period -> {
                    List<EmployeeCareerBonusPeriod> careerBonusPeriods = periodPhaseCalculator
                            .filter(calculator -> !plan.getType().isCorrection())
                            .map(calculator ->
                                    calculator.calculatePeriodPhaseDate(commonData, careerBean, Optional.of(period))
                            )
                            .map(phases -> getCareerPhaseBonusPeriod(phases, careerBean, period))
                            .orElseGet(CollectionUtils::newArrayList);
                    return CollectionUtils.isEmpty(careerBonusPeriods)
                            ? CollectionUtils.newArrayList(new EmployeeCareerBonusPeriod(careerBean, period))
                            : careerBonusPeriods;
                })
                .flatMap(List::stream)
                .collect(Collectors.toList());
        return CollectionUtils.sort(result, this::compareCareerPeriodDates);
    }

    /**
     * Сравнение дат у карьер с периодами
     */
    private int compareCareerPeriodDates(EmployeeCareerBonusPeriod career1, EmployeeCareerBonusPeriod career2) {
        Date firstCareerStart = career1.getCareer().getEmploymentDate();
        Date firstPeriodStart = career1.getPeriod().getStart();
        Date firstPeriodEnd = career1.getPeriod().getEnd();
        Date secondCareerStart = career2.getCareer().getEmploymentDate();
        Date secondPeriodStart = career2.getPeriod().getStart();
        Date secondPeriodEnd = career2.getPeriod().getEnd();
        Date firstStartDate = DateHelper.isBetweenDates(firstCareerStart, firstPeriodStart, firstPeriodEnd)
                ? firstCareerStart
                : firstPeriodStart;
        Date secondStartDate = DateHelper.isBetweenDates(secondCareerStart, secondPeriodStart, secondPeriodEnd)
                ? secondCareerStart
                : secondPeriodStart;
        return firstStartDate.compareTo(secondStartDate);
    }

    /**
     * Сначала сортируем по дате начала работы в отчетном периоде, затем по дате окончания
     */
    private void sortByStartDate(List<BonusPlanEmployeeBean> employees) {
        Collections.sort(employees, (o1, o2) -> {
            int res = DateHelper.compareNullableDates(o1.getPlanStartDate(),
                    o2.getPlanStartDate());
            return res == 0 ? DateHelper.compareNullableDates(o1.getPlanEndDate(), o2.getPlanEndDate()) : res;
        });
    }

    /**
     * Обработка первичных и пронозных планов по фильтрам
     */
    private void processParticipantByFilters() {
        SelectQueryData queryData = getCareersSelectQueryDataForParticipantsByFilter();
        addAllFilters(queryData);
        boolean isByAllCareers = falseIfNull(commonData.getKind().getFormByCareers());
        BeanQueryPagingIterator<CareerPersonIdBean> careerIterator = new BeanQueryPagingIterator<>(
                Pager.dbLimit(), queryData, CareerPersonIdBean.class);
        processParticipantCareers(careerIterator, careerVirtualBeans -> {
            if (!isByAllCareers) {
                careerVirtualBeans.forEach(this::fillCareerForPersonAndTabNumber);
            }
            processCareerList(careerVirtualBeans, new HashMap<>());
        });
        if (!isByAllCareers) {
            processNotActiveParticipants(getBpEmployeeToMakeInactiveWhenSingleCareerQueryData(queryData));
        }
        //Участники, в карьерах которых даты, не подходящие под отчетный период
        processNotActiveParticipants(addCheckedPersonIdFilterToPlanEmployeeQuery(getPlanEmployeeNotActiveByPeriod()));
    }
    
    /**
     * Выполняет запрос на основе переданного SelectQueryData, выдающий всех участников плана премирования
     * которых надо сделать неактивными, если нужно формировать их только по одному этапу карьеры.
     * SELECT BPE.*
     * FROM BNS$BPEMPLOYEE BPE
     * WHERE BPE.bnspid = :planId AND BPE.careerid NOT IN (
     *      SELECT TMP.pcid
     *      FROM (
     *           SELECT TM.pcid,
     *           ROW_NUMBER() OVER (
     *              PARTITION BY TM.personid,
     *              TM.pwtabnumber ORDER BY TM.pcemploymentdate DESC NULLS LAST,
     *              TM.pcdismissaldate DESC
     *           ) as rownumber
     *           FROM (
     *               *careersSelectQueryData*
     *           ) TM
     *      ) TMP
     *      WHERE TMP.rownumber = 1
     * )
     */
    private SelectQueryData getBpEmployeeToMakeInactiveWhenSingleCareerQueryData(
        SelectQueryData careersSelectQueryData
    ) {
        SelectQuery careersSelectQuery = careersSelectQueryData.getQuery();
        careersSelectQuery.addTableColumns(
            CAREER_ALIAS,
            CareerBean.ID,
            CareerBean.DISMISSAL_DATE,
            CareerBean.EMPLOYMENT_DATE
        );
        careersSelectQuery.setAlias(CAREERS_SELECT_QUERY_ALIAS);
        
        SelectQueryData top1RowQueryData = bonusRepository.getTopRowFromBpEmployeeToMakeInactiveQueryData();
        Aliasable rootQuery = top1RowQueryData.getQuery().getFromWhereClause()
            .getRootQueryByAlias(TOP_ROW_BP_EMPLOYEE_TO_CLEAR_QUERY_ALIAS);
        if (rootQuery instanceof SelectQuery) {
            FromWhereClause fromWhere = ((SelectQuery) rootQuery).getFromWhereClause();
            fromWhere.removeTables();
            fromWhere.addTable(careersSelectQuery);
        }
     
        Expression top1QueryExp = new Expression(null, top1RowQueryData.getQuery(), EMPTY_STRING).withBrackets();
        top1QueryExp.setUnaryOperation(true);
        
        SelectQueryData getIdsToDeleteQueryData = bonusRepository.getBpEmployeeIdsToToMakeInactiveQueryData(plan.getId());
        getIdsToDeleteQueryData.getQuery().getFromWhereClause()
            .addCondition(column(BonusPlanEmployeeBean.ALIAS, BonusPlanEmployeeBean.CAREER_ID).notIn(top1QueryExp));
        getIdsToDeleteQueryData.mergeNamedParams(top1RowQueryData).mergeNamedParams(careersSelectQueryData);
        getIdsToDeleteQueryData.getParams().add(careersSelectQueryData.getParams());
        getIdsToDeleteQueryData.getParams().add(top1RowQueryData.getParams());
        return getIdsToDeleteQueryData;
    }

    /**
     * Обработка карьер для создания/обновления участников премирования без привязки
     * к общим/индивидуальным периодам премирования
     */
    protected void processCareerList(
            List<CareerPersonIdBean> careerVirtualBeans,
            Map<String, String> previousEmployeesByCareer) {
        Set<CareerBean> careers = BeanHelper.getValueSet(careerVirtualBeans, CareerPersonIdBean.CAREER);
        Set<String> careerIds = BeanHelper.getIdSet(careers);
        //Участники плана по ID карьеры
        Map<String, List<BonusPlanEmployeeBean>> planEmployeeByCarerId = getPlanEmployeeByCareerId(careerIds);
        boolean processAllEmployeeBeans = CollectionUtils.isEmpty(checkedBpEmployeeIds);
        updateLastEmployeesIfPersonCareerChanged(careerVirtualBeans, planEmployeeByCarerId);
        Set<String> relevantCareerIds = getRelevantCareerIds(planEmployeeByCarerId);
        careerVirtualBeans.stream()
            .filter(careerBean -> processAllEmployeeBeans || relevantCareerIds.contains(careerBean.getCareer().getId()))
            .forEach(careerBean -> processEmployeeByCareerPhase(
                careerBean.getCareer(),
                careerBean.getPersonId(),
                planEmployeeByCarerId.getOrDefault(careerBean.getCareer().getId(), Collections.emptyList()),
                previousEmployeesByCareer
            ));
    }
    
    /**
     * Если формируем по последнему этапу карьеры, то обновить карьеры у уже существующих участников плана у которых
     * последний этапом карьеры не тот, что при предыдущем формировании
     */
    private void updateLastEmployeesIfPersonCareerChanged(
        List<CareerPersonIdBean> careerVirtualBeans,
        Map<String, List<BonusPlanEmployeeBean>> planEmployeeByCarerId
    ) {
        if (!falseIfNull(commonData.getKind().getFormByCareers())) {
            careerVirtualBeans
                .stream()
                .filter(career -> !planEmployeeByCarerId.containsKey(career.getCareer().getId()))
                .forEach(career -> updateLastEmployeeIfPersonCareerChanged(career, planEmployeeByCarerId));
        }
    }
    
    /**
     * Если при формировании списка оказывается, что последним этапом карьеры оказывается не тот этап, что при
     * предыдущем формировании, то нужно обновить данные по уже существующему участнику плана и не формировать нового
     */
    private void updateLastEmployeeIfPersonCareerChanged(
        CareerPersonIdBean careerPersonBean,
        Map<String, List<BonusPlanEmployeeBean>> planEmployeeByCarerId
    ) {
        String tabNumber = careerPersonBean.getCareer().getTabNumber();
        bonusRepository.findBpEmployeeIdWithLatestCareerDates(
			plan.getId(),
			careerPersonBean.getPersonId(),
			isEmpty(tabNumber),
			defaultIfEmpty(tabNumber, EMPTY_ID)
        )
        .flatMap(empId -> EntityManager.findOptional(empId, BonusPlanEmployeeBean.class))
        .ifPresent(employee -> {
            String careerId = careerPersonBean.getCareer().getId();
            employee.setCareerId(careerId);
            BonusPlanEmployeeBean updatedEmployee =
                entityListenerService.getSaveListener(BonusPlanEmployeeBean.class).save(employee).getUpdatedBean();
            planEmployeeByCarerId.put(careerId, newArrayList(updatedEmployee));
        });
    }
    
    /**
     * Выбранные из мапы айдишники карьер участников плана, выбранных в checkedBpEmployeeIds
     */
    private Set<String> getRelevantCareerIds(Map<String, List<BonusPlanEmployeeBean>> planEmployeeByCarerId) {
        return CollectionUtils.isEmpty(checkedBpEmployeeIds)
            ? newUnorderedSet()
            : planEmployeeByCarerId.entrySet()
                .stream()
                .flatMap(entry -> entry.getValue()
                    .stream()
                    .filter(bean -> checkedBpEmployeeIds.contains(bean.getId()))
                    .map(BonusPlanEmployeeBean::getCareerId)
                )
                .collect(Collectors.toSet());
    }

    /**
     * Создание участников плана премирования по карьере. Карьера разбивается на этапы
     * выполняемым кодом(если код выключен, то карьера считается, как один этап карьеры),
     * по каждому этапу карьеры создается отдельный участник плана премирования
     * Если участников плана больше чем этапов карьеры, лишние участники плана переводятся в статус "Не активен"
     *
     * Одновременно может строиться участник плана толькопо одной карьера в паре "физлицо - табельный номер".
     * Для этого используется блокировка
     * Это нужно для корректного изменения статусов приказа
     */
    private void processEmployeeByCareerPhase(
            CareerBean career,
            String personId,
            List<BonusPlanEmployeeBean> employeesBeans,
            Map<String, String> previousEmployeesByCareer
    ) {
        BonusModelPostFilterVirtualBean modelPostFilter = filtersByPosts.getOrDefault(
                career.getCaPost().getId(), new BonusModelPostFilterVirtualBean());
        BonusPlanEmployeeWithoutBonusData withoutBonus = isEmployeeWithoutBonus(career,
                personId, modelPostFilter.getModelPostFilter());
        Collection<Pair<Date, Date>> phases = periodPhaseCalculator
                .filter(calculator -> !plan.getType().isCorrection())
                .map(calculator -> calculator.calculatePeriodPhaseDate(commonData, career, Optional.empty()))
                .orElseGet(ArrayList::new);

        //Формируются даты этапов карьеры, для создания отдельных участников плана, по этим периодам
        sortByStartDate(employeesBeans);
        List<CareerBean> careers = CollectionUtils.sort(
                phases.isEmpty() ? CollectionUtils.newArrayList(career) : createCareerPhase(phases, career),
                (o1, o2) -> DateHelper.compareNullableDates(o1.getEmploymentDate(), o2.getEmploymentDate())
        );

        int maxIndex = Math.max(employeesBeans.size(), careers.size());
        //сопоставляем участников плана с вычисленными периодами премирования
        for (int i = 0; i < maxIndex; i++) {
            Optional<BonusPlanEmployeeBean> employee = CollectionUtils.findByIndex(employeesBeans, i);
            Optional<CareerBean> newCareer = CollectionUtils.findByIndex(careers, i);

            boolean isActive = newCareer.isPresent()
                    && (model.getFormPlanEmployeeNotSupposedBonus() || !withoutBonus.isEmployeeWithoutBonus());
            lockService.execWithLock(personId + "_" + career.getTabNumber(),
                    () -> processPlanEmployeeCareerWithinLock(
                            newCareer.orElse(career),
                            personId,
                            employee.orElse(null),
                            withoutBonus,
                            isActive,
                            previousEmployeesByCareer.getOrDefault(career.getId(), ""),
                            modelPostFilter,
                            Optional.empty()
                    ));
        }
    }

    /**
     * Когда формируем не по всем карьерам, то по связке физлицо-табномер определяем одну карьеру,
     * по которой будем формировать участника
     */
    private void fillCareerForPersonAndTabNumber(CareerPersonIdBean beanForCareer) {
        String tabNumber = beanForCareer.getCareer().getTabNumber();
        String emptyTabNumber = "emptyTabNumber";
        boolean isEmptyTabNumber = isEmpty(tabNumber);
        SelectQueryData careerQueryData = commonData.getKind().getBonusCalcMethod().isForPeriod() ?
                bonusRepository.getPersonCareerByPeriod(
                        beanForCareer.getPersonId(),
                        isEmptyTabNumber,
                        isEmptyTabNumber ? emptyTabNumber : tabNumber,
                        DateHelper.isNull(plan.getStart()),
                        DateHelper.isNull(plan.getEnd()),
                        plan.getStart(),
                        plan.getEnd()
                ) :
                bonusRepository.getPersonCareerByDate(
                        beanForCareer.getPersonId(),
                        isEmptyTabNumber,
                        isEmptyTabNumber ? emptyTabNumber : tabNumber,
                        plan.getDateCalculation()
                );
        strategy.addFiltersToCareerQuery(careerQueryData, plan);
        beanForCareer.setCareer(EntityManager.find(careerQueryData, CareerBean.class));
    }

    /**
     * @return запрос для получения карьер или связок физлицо-табномер в зависимости от настроек вида премии,
     * по которым будут построены участники плана
     */
    private SelectQueryData getCareersSelectQueryDataForParticipantsByFilter() {
        boolean isByAllCareers = falseIfNull(commonData.getKind().getFormByCareers());
        if (commonData.getKind().getBonusCalcMethod().isForPeriod()) {
            //за период
            return isByAllCareers ?
                    //по всем карьерам
                    bonusRepository.getCareersWithPersonForPlanEmployeesByPeriod(
                            DateHelper.isNull(plan.getStart()),
                            DateHelper.isNull(plan.getEnd()),
                            plan.getStart(),
                            plan.getEnd()
                    ) :
                    //по связкам физлицо - табномер
                    bonusRepository.getDistinctCareersWithPersonForPlanEmployeesByPeriod(
                            DateHelper.isNull(plan.getStart()),
                            DateHelper.isNull(plan.getEnd()),
                            plan.getStart(),
                            plan.getEnd()
                    );
        } else {
            //на дату
            return isByAllCareers ?
                    //по всем карьерам
                    bonusRepository.getCareersWithPersonForPlanEmployeesByDate(plan.getDateCalculation()) :
                    //по связкам физлицо - табномер
                    bonusRepository.getDistinctCareersWithPersonForPlanEmployeesByDate(plan.getDateCalculation());
        }
    }

    /**
     * Обработка карьер запускается в нескольких потоках
     */
    private void processParticipantCareers(BeanQueryPagingIterator<CareerPersonIdBean> careerIterator,
                                           Consumer<List<CareerPersonIdBean>> processor) {
        runProducerConsumerTask("Careers", careerIterator, processor);
    }

    /**
     * @return запрос на участников, который стали неактивны по фильтрам плана
     */
    protected QueryData<SelectQuery> getPlanEmployeeNotActiveByFilter() {
        SelectQuery selectQuery = orm.getDataObject(BonusPlanEmployeeBean.class).getQueryWithLookups();
        //выбираем участников плана, у которых id карьер not in (список карьер участников плана,
        // которые попадают под фильтры и центр премирования)
        QueryData<SelectQuery> careerQueryData = BonusSQL.ListBonusPlanEmployeesCareerIds.create(plan.getId());
        careerQueryData.setQuery(careerQueryData.getQuery().copy());
        strategy.addFiltersToCareerQuery(new SelectQueryData(careerQueryData), plan);
        addBonusCenterFilter(careerQueryData);
        String planParam = "planId";
        selectQuery.where(Column.column(DataObject.PARENT_ALIAS, BonusPlanEmployeeBean.PLAN_ID)
                .eq(NamedParameter.namedParameter(planParam))
                .and(Column.column(DataObject.PARENT_ALIAS, BonusPlanEmployeeBean.CAREER_ID)
                        .notIn(careerQueryData.getQuery().setChild())));
        QueryData<SelectQuery> queryData = QueryData.fromQuery(selectQuery);
        queryData.setParams(careerQueryData.getParams());
        queryData.mergeNamedParams(careerQueryData);
        queryData.withNamedIdParameter(planParam, CollectionUtils.newArrayList(plan.getId()));
        return queryData;
    }

    protected void addBonusCenterFilter(QueryData<SelectQuery> queryData) {
        if (StringHelper.isNotEmpty(plan.getBonusCenter().getId())) {
            String centerId = plan.getBonusCenter().getId();
            addBonusCenterFilter(queryData, centerId);
        }
    }

    /**
     * Добавить к запросу фильтр по центру премирования
     */
    protected void addBonusCenterFilter(QueryData<SelectQuery> queryData, String centerId) {
        if (isEmpty(centerId)) {
            return;
        }
        String careerAlias = queryData.getQuery().getFromWhereClause()
                .getTableByName(CareerBean.DATANAME).getAlias();

		SelectQuery selectQuery = queryData.getQuery();
		//карьера не должна относиться к исключениям по центру ответственности
		//исключение - это когда ни физлицо карьеры, ни должность карьеры не относится к центру
		//значит, физлицо и должность должны быть в списке относящихся к центру
		String checkPersonAlias = planService.getFinalPersonIdAlias(queryData.getQuery());
		Expression postCondition = addWhereInToCareerQuery(
				careerAlias,
				CareerBean.CA_POST_ID,
				bonusRepository.listBonusCenterPostIds(centerId),
				queryData
		);
		Expression personCondition = addWhereInToCareerQuery(
				careerAlias,
				CareerBean.ID,
				bonusRepository.listBonusCenterCareerIds(centerId),
				queryData
		);

		//плюс еще одно условие либо-либо
		//либо карьера подходит по организации (организация в центре или является дочерней для организации в центре)
		SelectQuery rootCaQuery = sqlStore.getQuery(BonusModule.NAME, "ListCasByBonusCenter");
		Expression caCondition = column(careerAlias, CareerBean.CA_ID).in(rootCaQuery.copy().setChild());
		queryData.getParams().insert(SQLParameter.integer(centerId));

		//либо карьера подходит под дополнительное назначение
		String addAppCenterParam = "bonusCenter";
		queryData.withNamedIdParameter(addAppCenterParam, CollectionUtils.newArrayList(centerId));

		String addAppAlias = "BCAA";
		Expression personOrPostCondition =
				column(checkPersonAlias, PersonBean.ID)
						.eq(column(addAppAlias, BonusAdditionalAppointmentsBean.PERSON_ID))
						.or(column(careerAlias, CareerBean.CA_POST_ID)
								.eq(column(addAppAlias, BonusAdditionalAppointmentsBean.POST_ID)))
						.withBrackets();
		selectQuery.leftJoin(BonusAdditionalAppointmentsBean.DATANAME, addAppAlias)
				.on(column(addAppAlias, BonusAdditionalAppointmentsBean.CENTER_ID)
						.eq(NamedParameter.namedParameter(addAppCenterParam))
						.and(personOrPostCondition));
		Expression addAppCondition = column(addAppAlias, BonusAdditionalAppointmentsBean.ID).isNotNull();

		Expression resultCondition = postCondition.or(personCondition).or(caCondition)
                .or(addAppCondition).withBrackets();

        selectQuery.where(resultCondition);
    }

    private Expression addWhereInToCareerQuery(
            String careerAlias,
            String columnName,
            QueryData<SelectQuery> idsQuery,
            QueryData<SelectQuery> careerQueryData
    ) {
        careerQueryData.getParams().add(idsQuery.getParams());
        return column(careerAlias, columnName)
                .in(idsQuery.getQuery().setChild());
    }

    /**
     * Добавить к запросу фильтр по физическим лицам (например, при действии "Переформировать по выбранным")
     */
    private QueryData<SelectQuery> addCheckedPersonIdFilterToPlanEmployeeQuery(QueryData<SelectQuery> queryData) {
        if (CollectionUtils.isEmpty(checkedPersonIds)) {
            return queryData;
        }
        SelectQuery selectQuery = queryData.getQuery().copy();
        queryData.setQuery(selectQuery);
        String planEmployeeAlias = selectQuery.getFromWhereClause()
                .getTableByName(BonusPlanEmployeeBean.DATANAME).getAlias();
        addCheckedPersonNamedParamExpression(column(planEmployeeAlias, BonusPlanEmployeeBean.PERSON_ID), queryData);
        return queryData;
    }

    /**
     * Добавить к запросу фильтр по физическим лицам по указанной колонке
     */
    private void addCheckedPersonNamedParamExpression(Column checkPersonIdColumn, QueryData<SelectQuery> queryData) {
        String checkedParam = "checkedpersonids";
        queryData.getQuery().where(checkPersonIdColumn.in(
                new BracketsValueList<>(NamedParameter.namedParameter(checkedParam))));
        queryData.withNamedIdParameter(checkedParam, checkedPersonIds);
    }

    private List<CareerBean> createCareerPhase(Collection<Pair<Date, Date>> phases, CareerBean careerBean) {
        return phases.stream().map(pair -> {
            CareerBean newCareer = BeanHelper.copy(careerBean);
            newCareer.setEmploymentDate(pair.getLeft());
            newCareer.setDismissalDate(pair.getRight());
            return newCareer;
        }).collect(Collectors.toList());
    }

    /**
     * Получение списка этапов карьеры с периодом премирования, в котором действовал этап карьеры
     */
    private List<EmployeeCareerBonusPeriod> getCareerPhaseBonusPeriod(
            Collection<Pair<Date, Date>> phases,
            CareerBean careerBean,
            PersonBonusModelVirtualBean period
    ) {
        return phases.stream().map(pair -> {
            CareerBean newCareer = BeanHelper.copy(careerBean);
            newCareer.setEmploymentDate(pair.getLeft());
            newCareer.setDismissalDate(pair.getRight());
            return new EmployeeCareerBonusPeriod(newCareer, period);
        }).collect(Collectors.toList());
    }

    /**
     * Если этапы карьеры не пустые ищется участник:
     * Дата начала работы соответствует дате начала отчетного периода
     * и дата окончания работы соответствует дате окончания отчетного периода
     *
     * Если этапы карьеры пустые проверятеся {@link BonusPlanEmployeeListCreator#isEmployeeExist}
     */
    private Optional<BonusPlanEmployeeBean> findEmployee(
            List<BonusPlanEmployeeBean> employeesBeans,
            Collection<Pair<Date, Date>> phases,
            CareerBean career,
            Optional<PersonBonusModelVirtualBean> premiumPeriod
    ) {
        if (phases.isEmpty()) {
            return employeesBeans.stream()
                    .filter(employee -> isEmployeeExist(employee, premiumPeriod))
                    .findFirst();
        }
        return employeesBeans.stream()
                .filter(employee -> DateHelper.isEquals(career.getEmploymentDate(), employee.getPlanStartDate()))
                .findFirst();
    }

    /**
     * Участник существует, если:
     * Существует индивидуальный период, дата начала которого совпадает с датой начала отчетного периода
     * ИЛИ дата начала плана премирования совпадает с датой начала отчетного периода
     */
    private boolean isEmployeeExist(
            BonusPlanEmployeeBean employee,
            Optional<PersonBonusModelVirtualBean> premiumPeriod
    ) {
        return premiumPeriod
                .map(p -> DateHelper.isEquals(p.getStart(), employee.getPlanStartDate()))
                .orElse(false)
                || DateHelper.isEquals(plan.getStart(), employee.getPlanStartDate())
                || DateHelper.isNull(employee.getPlanStartDate());
    }

    /**
     * Создать/обновить участника премирования по карьере с блоком по связке физлицо-табномер
     */
    private void processPlanEmployeeCareer(String careerId, String personId,
                                           BonusPlanEmployeeBean existingBean,
                                           BonusPlanEmployeeWithoutBonusData isEmployeeWithoutBonus,
                                           boolean isActive,
                                           String previousPlanEmployee,
                                           BonusModelPostFilterVirtualBean postFilterBean,
                                           Optional<PersonBonusModelVirtualBean> premiumPeriod
    ) {
        CareerBean careerBean = EntityManager.find(careerId, CareerBean.class);
        //одновременно может обрабатываться только одна карьера в паре "физлицо - табельный номер".
        // Это нужно для корректного изменения статусов приказа
        lockService.execWithLock(personId + "_" + careerBean.getTabNumber(), () -> processPlanEmployeeCareerWithinLock(
                careerBean, personId, existingBean, isEmployeeWithoutBonus,
                isActive, previousPlanEmployee, postFilterBean, premiumPeriod));
    }

    /**
     * Создать/обновить участника премирования по карьере
     */
    private void processPlanEmployeeCareerWithinLock(CareerBean careerBean, String personId,
                                                     BonusPlanEmployeeBean existingBean,
                                                     BonusPlanEmployeeWithoutBonusData isEmployeeWithoutBonus,
                                                     boolean isActive,
                                                     String previousPlanEmployee,
                                                     BonusModelPostFilterVirtualBean postFilterBean,
                                                     Optional<PersonBonusModelVirtualBean> premiumPeriod) {
        //если в плане несколько участников связаны с одним и тем же участником приказа,
        // то при формировании списка мог поменяться статус приказа.
        //поэтому необходимо загрузить участника плана заново
        existingBean = existingBean != null ? EntityManager.find(existingBean.getId(), BonusPlanEmployeeBean.class)
                : null;
        if (existingBean != null
                && !existingBean.getOrderStatus().isEmpty()
                && isEmployeesCanChange()
                && checkIfOrderStatusShouldBeEmpty(existingBean)) {
            existingBean.setOrderStatus(BonusPlanEmployeeOrderStatus.empty);
            existingBean = entityListenerService.getSaveListener(BonusPlanEmployeeBean.class)
                    .save(existingBean).getUpdatedBean();
        }
        BonusPlanEmployeeCalculateData calculateData = createEmployeeCalculateData(
                existingBean,
                postFilterBean,
                careerBean,
                personId,
                isEmployeeWithoutBonus,
                isActive,
                previousPlanEmployee,
                premiumPeriod
        );
        BonusPlanEmployeeCalculator calculator = createCalculator(calculateData);
        calculator.initialize(
                commonData,
                calculateData,
                strategy.createFieldFiller(),
                strategy.createKpiCalculatorCreator(),
                createUnchangeableEmployeeStatusCalculator());
        calculator.calculateChanges();
        processAfterCalculateChanges(calculator);
        storeErrorsFromCalculator(calculator);
    }

    protected BonusPlanUnchangeableEmployeeStatusCalculator createUnchangeableEmployeeStatusCalculator() {
        return new BonusPlanUnchangeableEmployeeStatusCalculator();
    }

    /**
     * Сюда добавляются действия после вычисления изменений по одному участнику плана
     */
    protected void processAfterCalculateChanges(BonusPlanEmployeeCalculator calculator) {

    }

    /**
     * @return объект-сборник данных, необходимых для формирования участника плана
     */
    protected BonusPlanEmployeeCalculateData createEmployeeCalculateData(
            BonusPlanEmployeeBean existingBean,
            BonusModelPostFilterVirtualBean postFilterBean,
            CareerBean careerBean, String personId,
            BonusPlanEmployeeWithoutBonusData isEmployeeWithoutBonus,
            boolean isActive,
            String previousPlanEmployee,
            Optional<PersonBonusModelVirtualBean> premiumPeriod
    ) {
        return new BonusPlanEmployeeCalculateData(
                postFilterBean,
                careerBean,
                personId,
                existingBean,
                isEmployeeWithoutBonus, isActive,
                isEmployeesCanChange() && (existingBean == null || existingBean.isCanChange()),
                previousPlanEmployee,
                premiumPeriod
        );
    }

    /**
     * @return true, если можно сохранять изменения сотрудников в базу
     */
    protected boolean isEmployeesCanChange() {
        return false;
    }

    /**
     * Создает класс, который будет вычислять изменения
     *
     * @param changeable true, если значения полей участника можно перезаписывать на новые в базе
     */
    protected BonusPlanEmployeeCalculator createCalculator(boolean changeable) {
        return codeService.getOrCreateEmployeeCalculator(planService.getBonusKindByPlan(plan.getId()))
           .orElseGet(() -> changeable
               ? new BonusPlanEmployeeEditableCalculator()
               : new BonusPlanEmployeeCalculator()
           );
    }

    /**
     * Создает класс, который будет расчитывать изменения, но гарантированно не запишет их в базу
     */
    private BonusPlanEmployeeCalculator createNotChangeableCalculator() {
        return createCalculator(false);
    }

    /**
     * Создает класс, который будет вычислять изменения
     */
    private BonusPlanEmployeeCalculator createCalculator(BonusPlanEmployeeCalculateData calculateData) {
        return createCalculator(calculateData.isChangeable());
    }

    private BonusPlanEmployeeWithoutBonusData isEmployeeWithoutBonus(
            CareerBean career, String personId, BonusModelPostFilterBean modelPostFilter) {
        boolean withoutBonusByFilter = new CareerFilterQueryBuilder()
                .isCareerByFilter(career.getId(), modelPostFilter, model);
        if (withoutBonusByFilter) {
            return BonusPlanEmployeeWithoutBonusData.withoutBonus(
                    BonusMessage.filters_by_employees_without_bonus.toString())
                    .setCauseChangeToType(model.getNotParticipantCareerFilter() ?
                            Optional.of(BonusPlanEmployeeType.not_participant) : Optional.empty());
        }
        if (bonusModelService.getProcessors().isEmpty()) {
            return BonusPlanEmployeeWithoutBonusData.withBonus();
        }
        //если какой-то процессор возвращает результат "не положена премия", то пробрасываем его,
        // а иначе - премия положена
        //при этом сначала ищем тот, который побуждает к какой-либо смене типа участия
        List<BonusPlanEmployeeWithoutBonusData> results = bonusModelService.getProcessors().stream()
                .map(processor -> processor.isPlanEmployeeNotSupposed(model, modelPostFilter, plan, personId, career))
                .filter(BonusPlanEmployeeWithoutBonusData::isEmployeeWithoutBonus)
                .collect(Collectors.toList());
        return results.isEmpty() ? BonusPlanEmployeeWithoutBonusData.withBonus() :
                results.stream().filter(data -> data.getCauseChangeToType().isPresent())
                        .findFirst().orElse(results.get(0));
    }

    /**
     * Обрабаытвает участников, которые должны стать неактивными, по переданному запросу
     */
    private void processNotActiveParticipants(QueryData<SelectQuery> queryData) {
        SelectQueryData selectQueryData = new SelectQueryData(queryData);
        selectQueryData.setSortings(new SQLSortings().addSorting(BonusPlanEmployeeBean.ID, SortDirection.ASC.getSqlValue()));
        BeanQueryPagingIterator<BonusPlanEmployeeBean> iterator = new BeanQueryPagingIterator<>(Pager.dbLimit(),
                selectQueryData, BonusPlanEmployeeBean.class);
        if (iterator.getCount() > 0) {
            runProducerConsumerTask("NotActive", iterator, this::processNotActiveParticipants);
        }
    }

    private <T> void runProducerConsumerTask(String nameSuffix,
                                             Iterable<? extends T> source,
                                             Consumer<? super T> processor) {
        ProducerConsumerTask.newTask(source, processor)
                .withName(this.getClass().getSimpleName() + plan.getId() + nameSuffix)
                .withProducersNumber(1)
                .withConsumersNumber(CONSUMERS_TASKS_NUMBER)
                .withQueueSize(CONSUMERS_TASKS_NUMBER)
                .withTimeout(600, TimeUnit.SECONDS)
                .process();
    }

    /**
     * Создание набора данных, которые можно вычислить один раз и которые потребуются для расчетов участников
     * плана премирования
     * Создается на основе класса-калькулятора, т.к. калькулятор определяет, какие данные ему понадобятся для расчетов
     */
    private BonusPlanEmployeeCalculatorCommonData createCommonData() {
        BonusPlanEmployeeCalculator emptyCalculator = createNotChangeableCalculator();
        PeriodPhaseMethod careerPeriodType =
                periodPhaseCalculator
                        .map(calculator -> PeriodPhaseMethod.ByExecuteCode)
                        .orElse(PeriodPhaseMethod.None);
        BonusPlanEmployeeCalculatorCommonData commonData = emptyCalculator.createCommonData(
                plan,
                model,
                careerPeriodType
        );
        storeErrorsFromCalculator(emptyCalculator);
        return commonData;
    }

    /**
     * Переносим ошибки из калькулятора в общий список ошибок
     */
    private void storeErrorsFromCalculator(BonusPlanEmployeeCalculator calculator) {
        calculator.getErrors().forEach(errors::add);
    }

    /**
     * Обрабатываем участников, которые станут неактивными
     */
    private void processNotActiveParticipants(List<BonusPlanEmployeeBean> beans) {
        Set<String> careerIds = BeanHelper.getValueSet(beans, BonusPlanEmployeeBean.CAREER_ID);
        Map<String, CareerBean> careerByIds = EntityManager.list(CareerBean.class, careerIds).stream()
                .collect(Collectors.toMap(CareerBean::getId, careerBean -> careerBean));
        beans.forEach(employeeBean -> {
            CareerBean careerBean = careerByIds.get(employeeBean.getCareerId());
            processPlanEmployeeCareer(
                    careerBean.getId(),
                    employeeBean.getPerson().getId(),
                    employeeBean,
                    isEmployeeWithoutBonus(careerBean, employeeBean.getPerson().getId()),
                    false,
                    "",
                    filtersByPosts.get(careerBean.getCaPost().getId()),
                    Optional.empty()
            );
        });
    }

    /**
     * Добавить к запросу полный набор фильтров - со вкладки "Фильтры", по центру премирования и на физических лиц
     */
    private void addAllFilters(SelectQueryData queryData) {
        //фильтр на выбранных физлиц
        queryData.setQuery(queryData.getQuery().copy());
        strategy.addFiltersToCareerQuery(queryData, plan);
        addCheckedPersonFilterToCareerFilter(queryData);
        addBonusCenterFilter(queryData);
    }

    /**
     * Добавить к запросу полный набор фильтров - со вкладки "Фильтры", по центру премирования и на физических лиц
     */
    private void addAllFilters(SelectQueryData queryData, BonusPlanBean plan) {
        addBonusCenterFilter(queryData, plan.getBonusCenter().getId());
        //фильтр на выбранных физлиц
        addCheckedPersonFilterToCareerFilter(queryData);
        strategy.addFiltersToCareerQuery(queryData, plan);
    }

    /**
     * Добавляет фильтр на физлица в запрос карьеры
     */
    protected void addCheckedPersonFilterToCareerFilter(SelectQueryData queryData) {
        if (CollectionUtils.isNotEmpty(checkedPersonIds)) {
            String checkPersonAlias = planService.getFinalPersonIdAlias(queryData.getQuery());
            Column checkPersonIdColumn = column(checkPersonAlias, PersonBean.ID);
            addCheckedPersonNamedParamExpression(checkPersonIdColumn, queryData);
        }
    }

    /**
     * Запрос на участников, которые больше не подходят по периоду
     */
    private QueryData<SelectQuery> getPlanEmployeeNotActiveByPeriod() {
        SelectQuery query = orm.getDataObject(BonusPlanEmployeeBean.class).getQuery();
        String careerAlias = "C";
        query = query.innerJoin(table(CareerBean.DATANAME, careerAlias))
                .on(column(DataObject.PARENT_ALIAS, BonusPlanEmployeeBean.CAREER_ID).eq(column(careerAlias,
                        CareerBean.ID)));

        if (commonData.getKind().getEmployeeFormingMethod().isByModel()) {
            //джоины на фильтры шд, чтобы второй раз не выбирать сотрудников, которые не подходят больше по шд
            String modelPostAlias = "BMP";
            String modelPostFilterAlias = "BMF";
            query.innerJoin(table(BonusModelPostBean.DATANAME, modelPostAlias))
                    .on(column(modelPostAlias, BonusModelPostBean.POST_ID).eq(column(careerAlias, CareerBean.CA_POST_ID)))
                    .innerJoin(table(BonusModelPostFilterBean.DATANAME, modelPostFilterAlias))
                    .on(column(modelPostAlias, BonusModelPostBean.MODEL_FILTER_ID).eq(column(modelPostFilterAlias,
                            BonusModelPostFilterBean.ID))
                            .and(column(modelPostFilterAlias, BonusModelPostFilterBean.MODEL_ID)
                                    .eq(Constant.intConst(model.getId()))));
        }

        query.where(
                column(DataObject.PARENT_ALIAS, BonusPlanEmployeeBean.PLAN_ID).eq(Constant.intConst(plan.getId()))
        );
        return commonData.getKind().getBonusCalcMethod().isForPeriod() ?
                bonusModelService.getCareerNotInPeriodQueryData(query, plan.getStart(), plan.getEnd()) :
                getCareerNotWithCalcDate(careerAlias, query);
    }

    /**
     * @return запрос с условием (дата начала больше даты расчета или дата увольнения заполнена и меньше даты расчета)
     */
    private QueryData<SelectQuery> getCareerNotWithCalcDate(String careerAlias, SelectQuery selectQuery) {
        Expression condition = column(careerAlias, CareerBean.EMPLOYMENT_DATE).gt(parameter())
                .or(column(careerAlias, CareerBean.DISMISSAL_DATE).isNotNull()
                        .and(column(careerAlias, CareerBean.DISMISSAL_DATE).lt(parameter()))).withBrackets();
        return selectQuery.where(condition)
                .createWithParameters(SQLParameter.date(plan.getDateCalculation()),
                        SQLParameter.date(plan.getDateCalculation()));
    }

    /**
     * Существующие участники плана по списку карьер
     */
    private Map<String, List<BonusPlanEmployeeBean>> getPlanEmployeeByCareerId(Set<String> careerIds) {
        SelectQueryData queryData = getPlanEmployeeBySomeId(BonusPlanEmployeeBean.CAREER_ID, careerIds);
        return BeanHelper.createMapFromListByFK(
            EntityManager.list(queryData, BonusPlanEmployeeBean.class),
            BonusPlanEmployeeBean.CAREER_ID
        );
    }

    /**
     * Существующие участники плана по списку физлиц
     */
    private Map<String, List<BonusPlanEmployeeBean>> getPlanEmployeesByPersonIds(Set<String> personIds) {
        SelectQueryData queryData = getPlanEmployeeBySomeId(BonusPlanEmployeeBean.PERSON_ID, personIds);
        return BeanHelper.createMapFromListByLookup(EntityManager.list(queryData, BonusPlanEmployeeBean.class),
                BonusPlanEmployeeBean.PERSON_ID);
    }

    /**
     * Существующие участники плана по списку физлиц, сгруппированные по карьерам
     */
    private Map<String, List<BonusPlanEmployeeBean>> getPlanEmployeesByPersonIdGroupByCareer(
            Set<String> ids) {
        SelectQueryData queryData = getPlanEmployeeBySomeId(BonusPlanEmployeeBean.PERSON_ID, ids);
        return BeanHelper.createMapFromListByFK(EntityManager.list(queryData, BonusPlanEmployeeBean.class),
                BonusPlanEmployeeBean.CAREER_ID);
    }

    /**
     * Запрос на участников плана по списку ключей
     */
    private SelectQueryData getPlanEmployeeBySomeId(String column, Set<String> ids) {
        SelectQuery query = orm.getDataObject(BonusPlanEmployeeBean.class).getQueryWithLookups();

        String namedParam = "emplids";
        query.where(
                column(DataObject.PARENT_ALIAS, BonusPlanEmployeeBean.PLAN_ID).eq(parameter())
                        .and(column(DataObject.PARENT_ALIAS, column)
                                .in(new BracketsValueList<>(NamedParameter.namedParameter(namedParam))))
        );
        return query
                .createWithParameters(SQLParameter.integer(plan.getId()))
                .withNamedIdParameter(namedParam, ids);
    }

    /**
     * @return true, если статус приказа участника плана должен быть пустым
     */
    protected boolean checkIfOrderStatusShouldBeEmpty(BonusPlanEmployeeBean bean) {
        //если нет участников приказов
        BonusPlanOrderEmployeePlanLinkBean filter = new BonusPlanOrderEmployeePlanLinkBean();
        filter.setPlamEmplId(bean.getId());
        return !EntityManager.exists(filter);
    }
}
