package ext.terralink.hr.bonus.plan;

import ext.nizhpharm.bonus.BonusModelCompaRatioRangeBean;
import ext.terralink.lms.core.person.career.CareerTerraBean;
import hr.bonus.plan.BonusPlanBean;
import hr.bonus.plan.BonusPlanService;
import hr.bonus.plan.employee.BonusPlanEmployeeBean;
import hr.bonus.plan.employee.BonusPlanEmployeeService;
import hr.bonus.plan.employee.colvalue.BonusPlanEmployeeAddColValueBean;
import hr.bonus.plan.employee.task.BonusPlanEmployeeCalculator;
import hr.bonus.plan.employee.task.BonusPlanEmployeeCalculatorTemplate;
import hr.bonus.plan.employee.task.BonusPlanEmployeeEditableCalculator;
import lms.core.person.PersonBean;
import lms.core.person.PersonService;
import lms.core.person.career.CareerBean;
import lms.core.person.career.CareerService;
import mira.vv.rubricator.standard.RSService;
import org.mirapolis.data.bean.NameBean;
import org.mirapolis.data.bean.reflect.repository.Select;
import org.mirapolis.orm.EntityManager;
import org.mirapolis.util.DateHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.*;

public class NizhPharmBonusPlanCodeExecution extends BonusPlanEmployeeCalculatorTemplate {
    private static final String COMPA_RATION_FIELD = "userfield1487904a5ae04d3dae8a";
    private static final String MIN_PERCENTAGE_INCREASE_FIELD = "userfield6b08896a354c4a0f8352";
    private static final String MAX_PERCENTAGE_INCREASE_FIELD = "userfieldad19471d07794f929055";
    private static final String ADMISSION_PERIOD_DATE_FIELD = "userfield94252376592e4aa7b3cd";
    private static final String PLANNED_RISE_CODE_FIELD = "userfield8bbc5878f3dc4f30a4ef";
    private static final String PLANNED_RISE_MANUAL_FIELD = "userfield43b139384282469ebe73";
    private static final String TO_BE_UP_SALARY_FIELD = "userfielde79d2dfbd97346c1b3c3";
    private static final String TO_BE_UP_REVENUE_FIELD = "userfield18502b6ef4f4446ab3d2";
    private static final String OLD_REVENUE_OCT_FIELD = "userfieldae786a6d6fa54ca49306";
    private static final String OLD_SALARY_BONUS_PLAN_FIELD = "userfield2a29e2fe692843f5b306";
    private static final String OLD_SALARY_PERSON_FIELD = "personfinalsalary";
    private static final String OLD_BONUS_SUM_BONUS_PLAN_FIELD = "userfield7bfc1cda0846436d97ac";
    private static final String OLD_BONUS_SUM_PERSON_FIELD = "userfield3c471cb177fa41db8b33";
    private static final String OLD_BONUS_RHY_FIELD = "userfield00db84f659044245a6ad";
    private static final String POST_PREMIUM_TARGET_PERIOD_CITY_FIELD = "postpremiumtargetperiodicity";
    private static final String OLD_BONUS_PERCENT_FIELD = "userfield179e5c75a90b44109933";
    private static final String POST_PREMIUM_TARGET_PERCENT_FIELD = "postpremiumtargetpercent";
    private static final String OLD_REVENUE_FIELD = "userfield3d5e441f10a9465cb676";
    private static final String OLD_REVENUE_PERSON_FIELD = "userfield6778f9d7eb7645aab44d";
    private static final String TOTAL_REMUNERATION_FIELD = "userfieldd6e37d11dda9419caa53";


    private static final String RS_FIELD_YES_VALUE = "1548";
    private static final String RS_FIELD_NO_VALUE = "1549";

    @Autowired
    RSService rubricatorService;

    @Autowired
    BonusPlanCodeExecutionRepository personCareerRepository;

    @Autowired
    PersonService personService;

    @Autowired
    BonusPlanService bonusPlanService;

    @Autowired
    BonusPlanEmployeeService bonusPlanEmployeeService;

    @Autowired
    CareerService careerService;

    @Override
    protected BonusPlanEmployeeCalculator createNonEditableEmployeeCalculator() {
        return new NizhPharmBonusPlanCodeExecution.NonEditableEmployeeCalculator();
    }

    @Override
    protected BonusPlanEmployeeEditableCalculator createEditableEmployeeCalculator() {
        return new NizhPharmBonusPlanCodeExecution.EditableEmployeeCalculator();
    }

    @Override
    public void calculateChanges() {
        super.calculateChanges();
        setPlannedRiseCode();
        updateEmploymentDate();
        updateComparatio();
        setTotalSalaryAndTotalRewardForOctoberFirst();
        updateFromPersonProfile();
        updateFromBonusPlan();
    }

    private Double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void setPlannedRiseCode() {
        BonusPlanEmployeeBean employeeBean = data.getEmployeeBean();
        boolean condition1 = parseDouble(employeeBean.getFieldValueByName(PLANNED_RISE_MANUAL_FIELD).toString()).equals("0");
        boolean condition2 = employeeBean.getFieldValueByName(ADMISSION_PERIOD_DATE_FIELD) != null;
        boolean condition3 = Double.parseDouble(employeeBean.getFieldValueByName(TO_BE_UP_SALARY_FIELD).toString()) > 0;
        boolean condition4 = Double.parseDouble(employeeBean.getFieldValueByName(TO_BE_UP_REVENUE_FIELD).toString()) > 0;

        if (condition1 || condition2 || condition3 || condition4) {
            employeeBean.setFieldValueByName(PLANNED_RISE_CODE_FIELD, NameBean.create(rubricatorService.getRSBean(RS_FIELD_NO_VALUE)));
        } else {
            employeeBean.setFieldValueByName(PLANNED_RISE_CODE_FIELD, NameBean.create(rubricatorService.getRSBean(RS_FIELD_YES_VALUE)));
        }
    }

    private void updateEmploymentDate() {
        BonusPlanEmployeeBean employeeBean = data.getEmployeeBean();
        String personId = employeeBean.getPerson().getId();
        // Получаем год расчета из данных плана
        int calculationYear = DateHelper.getYear(commonData.getPlan().getEnd());
        // Создаем даты начала и конца периода
        Date startDate = DateHelper.getDate(calculationYear, 9, 1);
        Date endDate = DateHelper.getDate(calculationYear, 11, 31);
        List<CareerBean> careers = personCareerRepository.getPersonCareersQueryData(personId);

        Optional<Date> latestHireDate = careers.stream()
                .map(CareerBean::getEmploymentDate)
                .filter(date -> date != null && !date.before(startDate) && !date.after(endDate))
                .max(Date::compareTo);
        if (latestHireDate.isPresent()) {
            employeeBean.setFieldValueByName(ADMISSION_PERIOD_DATE_FIELD, latestHireDate.get());
        }

    }

    private void updateComparatio() {
        BonusPlanEmployeeBean bonusPlanEmployeeBean = data.getEmployeeBean();
        String planId = bonusPlanEmployeeBean.getPlanId();
        List<BonusModelCompaRatioRangeBean> bonusModelCompaRatioRangeBeans = personCareerRepository.
                getComparationRange(planId);
        Double compaRationValue = bonusPlanEmployeeBean.getFieldValueByName(COMPA_RATION_FIELD);
        BonusModelCompaRatioRangeBean compaRatioRangeBean = bonusModelCompaRatioRangeBeans.stream()
                .filter(data -> data.getMincr() <= compaRationValue &&
                        data.getMaxrc() > compaRationValue).findFirst().orElse(null);
        if (compaRatioRangeBean != null) {
            bonusPlanEmployeeBean.setFieldValueByName(MIN_PERCENTAGE_INCREASE_FIELD, compaRatioRangeBean.getMinvalue());
            bonusPlanEmployeeBean.setFieldValueByName(MAX_PERCENTAGE_INCREASE_FIELD, compaRatioRangeBean.getMaxvalue());
        }
    }

    private void setTotalSalaryAndTotalRewardForOctoberFirst() {
        BonusPlanEmployeeBean employee = data.getEmployeeBean();
        int accountingYear = DateHelper.getYear(commonData.getPlanEnd());
        Date calculatedDate = DateHelper.getDate(accountingYear, 9, 1);
        PersonBean personBean = personService.getPersonById(employee.getPerson().getId());
        List<CareerBean> careerBeans = careerService.getCareerByWork(personBean.getMainWork().getId());
        careerBeans.stream().filter(careerBean -> (calculatedDate.after(careerBean.getEmploymentDate()) ||
                calculatedDate.equals(careerBean.getEmploymentDate())) &&
                calculatedDate.before(careerBean.getDismissalDate())).forEach(careerBean -> {
            CareerTerraBean bean = careerBean.getFieldValueByName(CareerTerraBean.EXT_FIELD_NAME);
            employee.setFieldValueByName(TOTAL_REMUNERATION_FIELD, bean.getFinalSalary());
            employee.setFieldValueByName(OLD_REVENUE_FIELD, careerBean.getFieldValueByName(OLD_REVENUE_OCT_FIELD));
        });
    }

    private void updateFromPersonProfile() {
        BonusPlanEmployeeBean employee = data.getEmployeeBean();
        PersonBean personBean = personService.getPersonById(employee.getPerson().getId());

        employee.setFieldValueByName(OLD_SALARY_BONUS_PLAN_FIELD, personBean.getFieldValueByName(OLD_SALARY_PERSON_FIELD));
        employee.setFieldValueByName(OLD_BONUS_SUM_BONUS_PLAN_FIELD, personBean.getFieldValueByName(OLD_BONUS_SUM_PERSON_FIELD));
        employee.setFieldValueByName(OLD_BONUS_RHY_FIELD, personBean.getFieldValueByName(POST_PREMIUM_TARGET_PERIOD_CITY_FIELD));
        employee.setFieldValueByName(OLD_BONUS_PERCENT_FIELD, personBean.getFieldValueByName(POST_PREMIUM_TARGET_PERCENT_FIELD));
        employee.setFieldValueByName(OLD_REVENUE_FIELD, personBean.getFieldValueByName(OLD_REVENUE_PERSON_FIELD));
    }

    private void updateFromBonusPlan() {
        BonusPlanEmployeeBean employee = data.getEmployeeBean();

        List<BonusPlanBean> bonusPlanBeanList = bonusPlanService.getBonusPlansByModel("17");
        bonusPlanBeanList.sort((o1, o2) -> {
            Date date1 = o1.getFieldValueByName("createdate");
            Date date2 = o2.getFieldValueByName("createdate");
            return date1.compareTo(date2);
        });
        List<BonusPlanBean> lastTwoPlansList = bonusPlanBeanList.size() > 2 ?
                bonusPlanBeanList.subList(bonusPlanBeanList.size() - 2, bonusPlanBeanList.size()) :
                bonusPlanBeanList;
        for(BonusPlanBean bonusPlanBean: lastTwoPlansList) {
            Set<String> employeeIds = bonusPlanEmployeeService.listAllBonusPlanEmployeeIds(bonusPlanBean.getId());
            employeeIds.stream().forEach(employeeId -> {
                String personId = employee.getPerson().getId();
                BonusPlanEmployeeBean BPEmployee = EntityManager.findOptional(employeeId, BonusPlanEmployeeBean.class).get();
                if (BPEmployee.getPerson().getId().equals(personId)) {
                    BonusPlanEmployeeAddColValueBean addColBean = new BonusPlanEmployeeAddColValueBean();
                    addColBean.setPlanEmployee(NameBean.create(BPEmployee.getId()));
                    List<BonusPlanEmployeeAddColValueBean> valueBeans = EntityManager.list(addColBean);

                    for (BonusPlanEmployeeAddColValueBean valueBean : valueBeans) {
                        String empId = addColBean.getPlanEmployee().getId();
                        BonusPlanEmployeeAddColValueBean bean = new BonusPlanEmployeeAddColValueBean();
                        if (valueBean.getColumn().getId().equals("104")&& !empId.equals(employee.getId())) {
                            bean.setPlanEmployee(NameBean.create(employee.getId()));
                            bean.setColumn(NameBean.create("160"));
                            bean.setValue(valueBean.getValue());

                        } else if (valueBean.getColumn().getId().equals("112")&& !empId.equals(employee.getId())) {
                            bean.setPlanEmployee(NameBean.create(employee.getId()));
                            bean.setColumn(NameBean.create("161"));
                            bean.setValue(valueBean.getValue());
                            EntityManager.update(bean);
                        } else if (valueBean.getColumn().getId().equals("158")&& !empId.equals(employee.getId())) {
                            bean.setPlanEmployee(NameBean.create(employee.getId()));
                            bean.setColumn(NameBean.create("162"));
                            bean.setValue(valueBean.getValue());
                            EntityManager.update(bean);
                        } else if (valueBean.getColumn().getId().equals("103")&& !empId.equals(employee.getId())) {
                            bean.setPlanEmployee(NameBean.create(employee.getId()));
                            bean.setColumn(NameBean.create("163"));
                            bean.setValue(valueBean.getValue());
                            EntityManager.update(bean);
                        } else if (!valueBean.getColumn().getId().equals("113")&& !empId.equals(employee.getId())) {
                            bean.setPlanEmployee(NameBean.create(employee.getId()));
                            bean.setColumn(NameBean.create("164"));
                            bean.setValue(valueBean.getValue());
                            EntityManager.update(bean);
                        }
                        List<BonusPlanEmployeeAddColValueBean> beansForInsert = new ArrayList<>();
                        beansForInsert.add(bean);
                        EntityManager.insertIfNotExist(beansForInsert,false);
                    }
                }
            });
        }
    }

    private class NonEditableEmployeeCalculator extends BonusPlanEmployeeCalculator {
        // Implement specific logic for non-editable calculator if needed
    }

    private class EditableEmployeeCalculator extends BonusPlanEmployeeEditableCalculator {
        // Implement specific logic for editable calculator if needed
    }

    @Repository
    private interface
    BonusPlanCodeExecutionRepository {
        /**
         * @return запрос на получение списка карьер пользователя
         */
        @Select("SELECT C.pcId AS id,\n" +
                "'personcareer' AS type,\n" +
                "                       CA.caName AS caidname,\n" +
                "                       CA.caId,\n" +
                "                       RS.rsName,\n" +
                "                       C.pcEmploymentDate,\n" +
                "                       C.pcDismissalDate,\n" +
                "                       C.pcDismissalReason,\n" +
                "                       C.pcAdditionalInfo,\n" +
                "                       C.pcOrderNum,\n" +
                "                       CRS.rsName AS capost,\n" +
                "                       C.pwtabnumber,\n" +
                "                       C.pwcontractdate,\n" +
                "                      C.pwfixedtermcontract,\n" +
                "                   C.pwcontractnumber,\n" +
                "                      WSRS.rsname AS pwworkschedule,\n" +
                "                      CASE WHEN (C.pwfixedtermcontract = 0) THEN NULL ELSE C.pwcontractterm END AS pwcontractterm,\n" +
                "                       GODN.rsname AS groundfordismissals,\n" +
                "                       ROFN.rsname AS reasonsofdismissals,\n" +
                "                       KORODN.rsname AS kindsofreasonsofdismissals,\n" +
                "                       EKRS.rsname AS pwemploymenttype,\n" +
                "                       CKRS.rsname AS pwcontracttype\n" +
                "                FROM PP$Career C\n" +
                "                INNER JOIN PP$CAREERTERRA CT ON C.pcid = CT.pcid\n" +
                "                LEFT JOIN CA$CA CA ON C.caId = CA.caId\n" +
                "                LEFT JOIN VV$RubSection RS ON C.rsPostId = RS.rsId\n" +
                "                LEFT JOIN ca$post CAP ON C.capostid = CAP.postid\n" +
                "                LEFT JOIN VV$RubSection CRS ON CAP.rspostid = CRS.rsId\n" +
                "                LEFT JOIN VV$RubSection WSRS ON WSRS.rsid = C.pwworkschedule\n" +
                "                LEFT JOIN VV$GROUNDOFDISMIAASLS GOD ON C.groundfordismissals = GOD.rsid\n" +
                "                LEFT JOIN VV$RubSection GODN ON GOD.rsid = GODN.rsid\n" +
                "                LEFT JOIN VV$REASONOFDISMISSALS ROF ON GOD.reasonsofdiasissals = ROF.rsid\n" +
                "                LEFT JOIN VV$RubSection ROFN ON ROF.rsid = ROFN.rsid\n" +
                "                LEFT JOIN VV$RubSection KORODN ON ROF.kindsofreasofdissmissals = KORODN.rsid\n" +
                "                LEFT JOIN VV$RubSection CKRS ON CKRS.rsid = C.pwcontracttype\n" +
                "                LEFT JOIN VV$RubSection EKRS ON EKRS.rsid = C.pwemploymenttype\n" +
                "                WHERE CT.event = 1421 AND\n" +
                "                      C.pwtabnumber = :personId")
        List<CareerBean> getPersonCareersQueryData(String personId);

        @Select("SELECT BNS.aeid AS aeid,\n" +
                "       BNS.aeforeignid as aeforeignid,\n" +
                "       BNS.bnmaxrc as bnmaxrc,\n" +
                "       BNS.bnmincr as bnmincr,\n" +
                "       BNS.bnminvalue as bnminvalue,\n" +
                "       BNS.bnmaxvalue as bnmaxvalue\n" +
                "FROM BNS$COMPARATIORANGE BNS\n" +
                "Inner Join BNS$BONUSPLAN BP on BNS.aeforeignid = BP.bnsmid\n" +
                "WHERE BP.bnspid = :planid")
        List<BonusModelCompaRatioRangeBean> getComparationRange(String planid);
    }
}
