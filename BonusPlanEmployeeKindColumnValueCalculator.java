package hr.bonus.plan.employee.task;

import hr.bonus.BonusRepository;
import hr.bonus.bonusemployeeaccrual.value.processor.BonusKindColValueProcessor;
import hr.bonus.bonusemployeeaccrual.value.processor.BonusKindColValueSaver;
import hr.bonus.plan.employee.BonusPlanEmployeeBean;
import hr.bonus.plan.employee.BonusPlanEmployeeFrame;
import hr.bonus.plan.employee.BonusPlanEmployeeGridSettings;
import hr.bonus.plan.employee.changes.BonusPlanEmployeeChangesService;
import hr.bonus.plan.employee.colvalue.AbstractBonusPlanEmployeeColValueBean;
import hr.bonus.vv.BonusKindRSBean;
import hr.bonus.vv.bonuskindcol.*;
import mira.constructor.datafield.ConstructorDataFieldService;
import mira.constructor.datafield.type.DataFieldType;
import org.mirapolis.data.bean.IntValue;
import org.mirapolis.data.bean.MultiLineText;
import org.mirapolis.orm.EntityManager;
import org.mirapolis.service.spring.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.stream.Collectors;

import static org.mirapolis.data.bean.DoubleValue.*;
import static org.mirapolis.util.BooleanHelper.falseIfNull;
import static org.mirapolis.util.StringHelper.EMPTY_STRING;
import static org.mirapolis.util.StringHelper.isNotEmpty;

/**
 * Расчет значения колонок, настраиваемых в значении вида премии
 * Не изменяет значения, только собирает изменения
 *
 * @author Zlata Zanina
 * @since 22.10.2018
 */
public class BonusPlanEmployeeKindColumnValueCalculator extends AbstractBonusPlanEmployeeAdditionalCalculator {

    @Autowired
    private BonusKindColumnService columnService;
    @Autowired
    private BonusPlanEmployeeChangesService changesService;
    @Autowired
    private BonusRepository bonusRepository;
    @Autowired
    private ConstructorDataFieldService constructorDataFieldService;

    //все колонки из вида премирования
    protected List<BonusKindColVirtualBean> allColumns;
    //все значения настраиваемых колонок из записей каталога "Начисления и рабочее время"
    protected List<BonusEmployeeAccrualWorkTimeLinkVirtualBean> linkBeans;
    protected Map<String, List<BonusKindColValueVirtualBean>> valuesByAwt;
    //объект, осуществляющий сохранение значения пользовательской колонки
    protected BonusKindColValueSaver colValueSaver;

    //нестандартный алгоритм расчета колонок
    protected BonusPlanEmployeeKindColumnValueInnerCalculator innerCalculator;
    /**
     * Игнорировать ли обработку колонок, которые заполняются через исполняемый код
     */
    private boolean ignoreColumnsFilledByCode;

    /**
     * Id полей участника плана, колонки по которым надо пересчитать
     * Если пустое, то пересчитываются все колонки
     */
    private Set<String> planEmployeeFieldIdsToCalculate = new HashSet<>();

    public BonusPlanEmployeeKindColumnValueCalculator(
            BonusPlanEmployeeCalculatorCommonData commonData,
            BonusPlanEmployeeCalculateData data,
            BonusPlanEmployeeKindColumnValueInnerCalculator innerCalculator) {
        this(commonData, data);
        this.innerCalculator = innerCalculator;
        this.innerCalculator.setColValueSaver(colValueSaver);
        this.innerCalculator.setData(data);
    }

    public BonusPlanEmployeeKindColumnValueCalculator(
            BonusPlanEmployeeCalculatorCommonData commonData,
            BonusPlanEmployeeCalculateData data) {
        super(commonData, data);

        BeanFactory.autoWire(this);

        colValueSaver = createSaver();
        //все колонки из вида премирования
        BonusKindRSBean bonusKindRSBean = EntityManager.find(commonData.model.getKind().getId(), BonusKindRSBean.class);
        allColumns = columnService.getColumnsFromBonusKind(bonusKindRSBean);
    
        String planEmployeeId = data.getEmployeeBean().getId();
        //все записи из каталога "Начисления и рабочее время", подходящие по периоду плана премирования для сотрудника
        linkBeans = isNotEmpty(planEmployeeId)
            ? bonusRepository.listWorkTimeAccrualsByPlanEmployee(planEmployeeId)
            : new ArrayList<>();
        //все значения настроиваемых колонок из записей каталога "Начисления и рабочее время"
        Set<String> awtIds = linkBeans.stream().map(BonusEmployeeAccrualWorkTimeLinkVirtualBean::getAwtId)
                .collect(Collectors.toSet());
        List<BonusKindColValueVirtualBean> allValues = awtIds.isEmpty()
            ? new ArrayList<>()
            : bonusRepository.listAccrualWorkTimeValuesForPlan(awtIds);
        valuesByAwt = allValues.stream().collect(Collectors.groupingBy(BonusKindColValueVirtualBean::getAwtId));
    }

    /**
     * Рассчитать значения по колонкам, настроенным в значении вида премии
     */
    public void calculateAfterGroup(BonusKindSystemColumnGroups group) {
        int afterGroup = group.getCalcOrder();
        int beforeGroup = group.getNextGroupOrder();
        allColumns.stream()
            .filter(column -> column.getCalcOrder() >= afterGroup
                && (beforeGroup < 0 || column.getCalcOrder() < beforeGroup)
            )
            .filter(column -> planEmployeeFieldIdsToCalculate.isEmpty() ||
                planEmployeeFieldIdsToCalculate.contains(column.getViewFieldId())
            )
            .sorted(Comparator.comparing(BonusKindColVirtualBean::getCalcOrder))
            .forEach(this::calculateColumn);
    }
    
    private void calculateColumn(BonusKindColVirtualBean column) {
        BonusKindColValueProcessor<?, ?, ?> processor = column.getColType().getProcessor(column);
        BonusKindColValueDetermMethod columnValueDetermMethod = column.getValueDetermMethod();
        BonusPlanEmployeeGridSettings commonSettings = commonData.getSettings();
        boolean columnHasViewField = isNotEmpty(column.getViewFieldId());
        //если эта колонка заполняется через калькулятор в исп. коде
        if (innerCalculator != null && innerCalculator.isCalculateColumnByInnerCalculator(column)) {
            //если запускаем калькулятор не через "Сформировать список", то не пересчитываем колонки заполняющиеся
            // через исп. код ни через формулы ни из каталога начислений
            if (ignoreColumnsFilledByCode) {
                //Кроме случая когда у колонки есть связ. поле, тогда обновить значение колонки и
                // историю изменений(как в режиме определения значения "Вручную")
                if (columnHasViewField) {
                    calculateValuesByManualInput(processor, commonSettings);
                }
                return;
            }
            innerCalculator.calculateColumn(column);
        } else {
            if (columnHasViewField && BonusKindColValueDetermMethod.manual == columnValueDetermMethod
                //колонки с нечисловыми полями заполняем как "Вручную" всегда
                || falseIfNull(column.getFieldIsNotCalculable())
            ) {
                calculateValuesByManualInput(processor, commonSettings);
            } else if (BonusKindColValueDetermMethod.copy == columnValueDetermMethod) {
                //для каждой колонки проходимся по всем сотрудникам и обновляем значения
                // в этих колонках согласно полученным из каталога
                processor.calculateValuesForPlanFromAccrual(
                    data.getEmployeeBean(),
                    commonSettings,
                    linkBeans,
                    valuesByAwt,
                    colValueSaver
                );
            } else if (BonusKindColValueDetermMethod.calculate == columnValueDetermMethod) {
                processor.calculateValuesByFormulas(
                    data.getEmployeeBean(),
                    commonSettings,
                    linkBeans,
                    colValueSaver,
                    data.isChangeable(),
                    data.getChanges()
                );
            }
        }
    }
    
    private void calculateValuesByManualInput(
        BonusKindColValueProcessor<?, ?, ?> processor,
        BonusPlanEmployeeGridSettings commonSettings
    ) {
        processor.calculateValuesByManualInput(
            data.getEmployeeBean(),
            commonSettings,
            colValueSaver
        );
    }
    
    /**
     * Игнорировать обработку колонок, которые заполняются через исполняемый код
     */
    public BonusPlanEmployeeKindColumnValueCalculator ignoreColumnsFilledByCode() {
        ignoreColumnsFilledByCode = true;
        return this;
    }
    
    public boolean isIgnoreColumnsFilledByCode() {
        return ignoreColumnsFilledByCode;
    }

    /**
     * Id полей участника плана, колонки по которым надо пересчитать
     */
    public BonusPlanEmployeeKindColumnValueCalculator setPlanEmployeeFieldIdsToCalculate(
        Set<String> planEmployeeFieldIdsToCalculate
    ) {
        this.planEmployeeFieldIdsToCalculate = planEmployeeFieldIdsToCalculate;
        return this;
    }

    /**
     * @return создает вспомогательную сущность, сохраняющую значение колонок
     */
    public BonusKindColValueSaver createSaver() {
        return new BonusKindColValueSaver() {
            @Override
            public void saveValue(
                    BonusKindColVirtualBean columnBean,
                    AbstractBonusPlanEmployeeColValueBean valueBean,
                    Collection<String> newValues,
                    String columnSysName,
                    String columnTitle) {
                setColumnValue(columnBean, valueBean, new MultiLineText(newValues), columnSysName, columnTitle);
            }
        };
    }

    protected void setColumnValue(
            BonusKindColVirtualBean columnBean,
            AbstractBonusPlanEmployeeColValueBean valueBean,
            MultiLineText newValue,
            String columnSysName,
            String columnTitle) {
        MultiLineText prevValue = valueBean.getValue();
        if ((prevValue == null || !prevValue.equals(newValue)) && data.existingBean != null) {
            data.addKindColCauseChange(columnBean.getCauseChange());
            data.getChanges().add(changesService.createChange(columnSysName, columnTitle, prevValue, newValue, ""));
        }
        if (!falseIfNull(columnBean.getFieldIsNotCalculable()) && isNotEmpty(columnBean.getViewFieldId())) {
            updateEmployeeBeanValueForConnectedField(columnBean, newValue);
        }
        processAfterCalculateColumnValue(valueBean, newValue);
    }
    
    private void updateEmployeeBeanValueForConnectedField(
        BonusKindColVirtualBean columnBean,
        MultiLineText newValue
    ) {
        String viewFieldSysName = columnBean.getViewFieldSysName();
        if (isNotEmpty(viewFieldSysName)) {
            Optional.ofNullable(Optional.ofNullable(columnBean.getViewFieldDfType()).orElseGet(() ->
                    constructorDataFieldService.findSystemDataFieldTypeByName(
                        viewFieldSysName,
                        BonusPlanEmployeeFrame.NAME
                    ).orElse(null)
                ))
                .filter(fieldType -> fieldType == DataFieldType.FLOAT || fieldType == DataFieldType.INT)
                .ifPresent(fieldType ->
                    updateNumericValueInPlanEmployee(viewFieldSysName, fieldType, newValue)
                );
        }
    }
    
    private void updateNumericValueInPlanEmployee(
        String fieldSysNsme,
        DataFieldType fieldType,
        MultiLineText newNumericValue
    ) {
        String firstNewValue = newNumericValue.getValue().stream().findFirst().orElse(EMPTY_STRING);
        BonusPlanEmployeeBean planEmployeeBean = data.getEmployeeBean();
        Double doubleValue = parse(firstNewValue);
        if (fieldType == DataFieldType.FLOAT) {
            planEmployeeBean.setFieldValueByName(
                fieldSysNsme,
                isNull(doubleValue) ? NULL : doubleValue
            );
        } else {
            planEmployeeBean.setFieldValueByName(
                fieldSysNsme,
                isNull(doubleValue) ? IntValue.NULL : getIntValue(doubleValue)
            );
        }
    }

    /**
     * Эти действия будут выполнены после расчета нового значения
     */
    protected void processAfterCalculateColumnValue(
            AbstractBonusPlanEmployeeColValueBean valueBean, MultiLineText newValue) {

    }
}
