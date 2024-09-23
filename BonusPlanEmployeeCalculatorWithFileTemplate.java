package hr.bonus.plan.employee.task;

import hr.bonus.BonusMessage;
import hr.bonus.bonusemployeeaccrual.value.processor.BonusKindColValueProcessor;
import hr.bonus.model.BonusModelBean;
import hr.bonus.plan.BonusPlanBean;
import hr.bonus.plan.BonusPlanService;
import hr.bonus.plan.employee.BonusPlanEmployeeFormBean;
import hr.bonus.plan.employee.PeriodPhaseMethod;
import hr.bonus.vv.BonusKindRSBean;
import hr.bonus.vv.bonuskindcol.BonusKindColVirtualBean;
import hr.bonus.vv.bonuskindcol.BonusKindColumnService;
import mira.constructor.datafield.ConstructorDataFieldService;
import org.mirapolis.control.file.FileService;
import org.mirapolis.data.bean.NameBean;
import org.mirapolis.file.File;
import org.mirapolis.file.FileFormat;
import org.mirapolis.file.name.FileName;
import org.mirapolis.migration.serialization.table.Table;
import org.mirapolis.migration.serialization.table.TableRow;
import org.mirapolis.service.xls.aspose.AsposeRawNumericXlsTableReader;
import org.mirapolis.util.StringHelper;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toList;

/**
 * Абстрактный класс для выполняемых кодов расчета пользовательских колонок из файла в плане премирования
 *
 * @author Nina Yavsenkova
 * @since 07.05.2021
 */
public abstract class BonusPlanEmployeeCalculatorWithFileTemplate extends BonusPlanEmployeeCalculatorTemplate {
	@Autowired
	private AsposeRawNumericXlsTableReader xlsTableReader;
	@Autowired
	private FileService fileService;
	@Autowired
	private BonusPlanService planService;
	@Autowired
	private BonusKindColumnService columnService;
	@Autowired
	private ConstructorDataFieldService dataFieldService;

	@Override
	protected BonusPlanEmployeeCalculator createNonEditableEmployeeCalculator() {
		return new BonusPlanEmployeeCalculator() {
			@Override
			protected BonusPlanEmployeeKindColumnValueCalculator createKindColumnCalculator() {
				return new BonusPlanEmployeeKindColumnValueCalculator(
						commonData, data, createInnerCalculator());
			}
		};
	}

	@Override
	protected BonusPlanEmployeeEditableCalculator createEditableEmployeeCalculator() {
		boolean ignoreColumnsFilledByCode = isIgnoreColumnsFilledByCode();
		BonusPlanEmployeeEditableCalculator editableCalculator = new BonusPlanEmployeeEditableCalculator() {
			@Override
			protected BonusPlanEmployeeKindColumnValueCalculator createKindColumnCalculator() {
				BonusPlanEmployeeEditableKindColumnValueCalculator kindCalculator =
					new BonusPlanEmployeeEditableKindColumnValueCalculator(commonData, data, createInnerCalculator());
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
		return editableCalculator;
	}

	/**
	 * Калькулятор с дополнительной логикой для расчетов
	 */
	protected abstract PlanKindFileColumnInnerCalculator createInnerCalculator();

	/**
	 * Системное название пользовательского поля с файлом
	 */
	protected abstract String getFileUserFieldSysName();

	@Override
	public BonusPlanEmployeeWithFileCalculatorCommonData createCommonData(
			BonusPlanBean plan,
			BonusModelBean model,
			PeriodPhaseMethod careerPeriodType
	) {
		BonusPlanEmployeeCalculatorCommonData basicData = super.createCommonData(plan, model, careerPeriodType);
		BonusPlanEmployeeWithFileCalculatorCommonData commonData =
				new BonusPlanEmployeeWithFileCalculatorCommonData(basicData);
		return fillCommonDataWithFile(commonData);
	}

	/**
	 * Дополнительная общая логика для работы с таблицой из файла
	 */
	protected <T extends BonusPlanEmployeeWithFileCalculatorCommonData> T fillCommonDataWithFile(T commonData) {
		String filedSysName = getFileUserFieldSysName();
		if (StringHelper.isNotEmpty(filedSysName)) {
			dataFieldService.listAllUserDataFields().stream().
					filter(dataField -> dataField.getName().equals(filedSysName))
					.findFirst()
					.ifPresent(dataField -> commonData.setFileFieldCaption(dataField.getCaption()));
		}
		initColumnsByCode(commonData);
		initCommonDataForFileImport(commonData);
		return commonData;
	}


	protected <T extends BonusPlanEmployeeWithFileCalculatorCommonData> void initColumnsByCode(T commonData) {
		BonusKindRSBean kind = planService.getBonusKindByPlan(commonData.getPlan().getId());
		commonData.setColumnsByCode(columnService.getColumnsFromBonusKindMapByCode(kind));
	}

	//-----------Импорт из файла

	protected void initCommonDataForFileImport(BonusPlanEmployeeWithFileCalculatorCommonData commonData) {
		commonData.setTableFromFile(readTable(commonData));
		initFromFileColumnCodes(commonData);
	}


	/**
	 * Чтение таблицы из файла в пользовательском поле
	 */
	private Optional<Table> readTable(BonusPlanEmployeeWithFileCalculatorCommonData commonData) {
		String fileUserField = getFileUserFieldSysName();
		if (StringHelper.isEmpty(fileUserField)) {
			return Optional.empty();
		}

		if (!commonData.getPlan().findField(fileUserField).isPresent()) {
			addError("В плане премирования отсутствует поле с файлом, обратитесь к администратору системы.");
			return Optional.empty();
		}

		NameBean fileValue = commonData.getPlan().getFieldValueByName(fileUserField);
		if (StringHelper.isEmpty(fileValue.getId())) {
			addError(String.format("Не загружен файл импорта в поле %s: " +
					"если вы нажали на кнопку «Сформировать список», чтобы потом сформировать преднаполненный " +
					"шаблон для загрузки информации по сотрудникам, то проигнорируйте данное сообщение; " +
					"если вы нажали для того, чтобы рассчитать премию, то приложите сначала файл, " +
					"нажмите «Сохранить» и нажмите снова «Сформировать список».",
					commonData.getFileFieldCaptionOrSysName(fileUserField)));
			return Optional.empty();
		}
		File file = checkAndGetFile(fileValue.getId(), commonData.getFileFieldCaptionOrSysName(fileUserField));
		if (file == null) {
			return Optional.empty();
		}
		return Optional.of(xlsTableReader.read(file, 0));
	}

	private File checkAndGetFile(String fileId, String fileFieldName) {
		File file = fileService.getFile(fileId);
		FileName name = file.getFileName();
		if (!file.exists()) {
			addError("В поле \"Файл импорта\" указан файл, который не найден, приложите файл заново.");
			return null;
		}
		if (!name.isAnyFormat(FileFormat.XLS, FileFormat.XLSX)) {
			addError("В поле " + fileFieldName + " приложен файл неверного формата.");
			return null;
		}
		return file;
	}

	/**
	 * Проверка наличия значений для колонок в файле и запись индексов колонок в commonData
	 */
	private void initFromFileColumnCodes(BonusPlanEmployeeWithFileCalculatorCommonData commonData) {
		if (!commonData.getTableFromFile().isPresent()) {
			return;
		}
		List<BonusKindColVirtualBean> columns = listColumnsForFileImport(commonData);

		String employeeNumberColumnName = commonData.getSettings().getColumnSettingsTitle(
				BonusPlanEmployeeFormBean.AUTONUMBER, BonusMessage.plan_participant_autonumber.toString());
		commonData.setEmployeeNumberColumnName(employeeNumberColumnName);

		//собираем только те колонки, которые показываются в плане
		columns = columns.stream()
				.filter(column -> commonData.getSettings()
						.isShow(column.getColType().getProcessor(column).getGridColumnName()))
				.collect(toList());
		if (columns.isEmpty()) {
			return;
		}
		//запоминаем, где какая колонка в файле, мапа код - номер колонки таблицы
		Table table = commonData.getTableFromFile().get();
		TableRow row = table.getRow(0);
		//находим все индексы колонок с названием, равным названию колонки автономера участника плана из настроек
		List<Integer> employeeAutonumberColumnNumbers = new ArrayList<>();
		List<Integer> columnsFromFileIndexes = commonData.getColumnsFromFileIndexes();
		for (int i = 1; i <= row.getMaxColumnNumber(); i++) {
			String columnName = row.getValue(i);
			if (StringHelper.isEmpty(columnName)) {
				addError("В файле есть колонка без названия, скорретируйте и загрузите снова.");
				return;
			}
			//Пытаемся найти в таблице колонку с названием колонки из плана, которые нужно импортировать
			Optional<BonusKindColVirtualBean> column = findColByTitle(commonData, columnName, columns);
			if (column.isPresent()) {
				putToColumnsInFile(commonData, column.get(), columnName, i);
				columnsFromFileIndexes.add(i);
			}
			if (employeeNumberColumnName.equals(columnName)) {
				employeeAutonumberColumnNumbers.add(i);
			}
		}

		if (employeeAutonumberColumnNumbers.isEmpty()) {
			addError(String.format(
					"В файле не найдена колонка «%s», данная колонка необходима для загрузки данных.",
					employeeNumberColumnName));
			return;
		}

		if (employeeAutonumberColumnNumbers.size() > 1) {
			addError(String.format(
					"Найдено несколько колонок «%s». Требуется оставить в файле только одну колонку.",
					employeeNumberColumnName));
			return;
		}
		commonData.getFromFileColumnCodes().addAll(commonData.getColumnsInFile().keySet());
		commonData.setEmployeeNumberColumn(employeeAutonumberColumnNumbers.get(0));
	}

	/**
	 * @return список колонок, значения которых расчитываются из файла
	 */
	protected abstract List<BonusKindColVirtualBean> listColumnsForFileImport(
			BonusPlanEmployeeWithFileCalculatorCommonData commonData);

	protected void addBonusKindColumnIfExist(BonusPlanEmployeeWithFileCalculatorCommonData commonData,
											 List<BonusKindColVirtualBean> columns, String colCode,
											 String colName) {
		if (commonData.getColumnsByCode().containsKey(colCode)) {
			columns.add(commonData.getColumnsByCode().get(colCode));
		}
	}

	private void putToColumnsInFile(
			BonusPlanEmployeeWithFileCalculatorCommonData commonData,
			BonusKindColVirtualBean column,
			String excelColumnName,
			int columnIndex) {
		if (!commonData.getColumnsInFile().containsKey(column.getColCode())) {
			commonData.getColumnsInFile().put(column.getColCode(), new HashMap<>());
		}
		commonData.getColumnsInFile().get(column.getColCode()).put(excelColumnName, columnIndex);
	}

	/**
	 * @return колонка, у которой название, с которого начинается заданный текст
	 */
	private Optional<BonusKindColVirtualBean> findColByTitle(
			BonusPlanEmployeeWithFileCalculatorCommonData commonData,
			String excelColumnName,
			Collection<BonusKindColVirtualBean> columns) {
		//выбираем ту колонку, название которой больше совпадает с колонкой из файла
		//например, колонка в файле называется "АБВГ, в днях", а в настройках есть "АБВГ" и "АБ".
		return columns.stream().filter(bean -> excelColumnName.startsWith(getTitleFromSettings(commonData, bean)))
				.min(comparing(o -> leftOverLength(excelColumnName, getTitleFromSettings(commonData, o))));
	}

	/**
	 * @return название колонки из настроек
	 */
	private String getTitleFromSettings(BonusPlanEmployeeWithFileCalculatorCommonData commonData,
										BonusKindColVirtualBean column) {
		BonusKindColValueProcessor processor = column.getColType().getProcessor(column);
		return getTitleFromSettings(commonData, column, processor);
	}

	private String getTitleFromSettings(
			BonusPlanEmployeeWithFileCalculatorCommonData commonData,
			BonusKindColVirtualBean column, BonusKindColValueProcessor processor) {
		return commonData.getSettings().getColumnSettingsTitle(processor.getGridColumnName(), column.getTitle());
	}

	private Integer leftOverLength(String excelColumnName, String colTitle) {
		return excelColumnName.replace(colTitle, "").length();
	}
}
