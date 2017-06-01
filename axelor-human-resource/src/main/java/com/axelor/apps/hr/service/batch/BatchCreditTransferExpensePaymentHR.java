package com.axelor.apps.hr.service.batch;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.apps.account.db.AccountingBatch;
import com.axelor.apps.account.db.repo.InvoicePaymentRepository;
import com.axelor.apps.account.exception.IExceptionMessage;
import com.axelor.apps.account.service.batch.BatchCreditTransferExpensePayment;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.service.administration.GeneralService;
import com.axelor.apps.hr.db.Expense;
import com.axelor.apps.hr.db.repo.ExpenseRepository;
import com.axelor.apps.hr.service.expense.ExpenseService;
import com.axelor.db.JPA;
import com.axelor.db.Query;
import com.axelor.exception.AxelorException;
import com.axelor.exception.service.TraceBackService;
import com.axelor.i18n.I18n;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;

public class BatchCreditTransferExpensePaymentHR extends BatchCreditTransferExpensePayment {

	protected final Logger log = LoggerFactory.getLogger(getClass());
	protected final GeneralService generalService;
	protected final ExpenseRepository expenseRepo;
	protected final ExpenseService expenseService;

	@Inject
	public BatchCreditTransferExpensePaymentHR(GeneralService generalService, ExpenseRepository expenseRepo,
			ExpenseService expenseService) {
		this.generalService = generalService;
		this.expenseRepo = expenseRepo;
		this.expenseService = expenseService;
	}

	@Override
	protected void start() throws IllegalArgumentException, IllegalAccessException, AxelorException {
		super.start();
	}

	@Override
	protected void process() {
		AccountingBatch accountingBatch = batch.getAccountingBatch();
		List<Long> anomalyList = Lists.newArrayList(0L);	// Can't pass an empty collection to the query
		boolean manageMultiBanks = generalService.getGeneral().getManageMultiBanks();
		String filter = "self.ventilated = true "
				+ "AND self.paymentStatusSelect = :paymentStatusSelect "
				+ "AND self.company = :company "
				+ "AND self.user.partner.outPaymentMode = :paymentMode "
				+ "AND self.id NOT IN (:anomalyList)";

		if (manageMultiBanks) {
			filter += " AND self.bankDetails IN (:bankDetailsSet)";
		}

		Query<Expense> query = expenseRepo.all().filter(filter)
				.bind("paymentStatusSelect", InvoicePaymentRepository.STATUS_DRAFT)
				.bind("company", accountingBatch.getCompany())
				.bind("paymentMode", accountingBatch.getPaymentMode())
				.bind("anomalyList", anomalyList);

		if (manageMultiBanks) {
			Set<BankDetails> bankDetailsSet = Sets.newHashSet(accountingBatch.getBankDetails());

			if (accountingBatch.getIncludeOtherBankAccounts()) {
				bankDetailsSet.addAll(accountingBatch.getCompany().getBankDetailsSet());
			}

			query = query.bind("bankDetailsSet", bankDetailsSet);
		}

		for (List<Expense> expenseList; !(expenseList = query.fetch(FETCH_LIMIT)).isEmpty(); JPA.clear()) {
			for (Expense expense : expenseList) {
				try {
					addPayment(expense);
					incrementDone();
				} catch (Exception ex) {
					incrementAnomaly();
					anomalyList.add(expense.getId());
					query = query.bind("anomalyList", anomalyList);
					TraceBackService.trace(ex);
					ex.printStackTrace();
					log.error(String.format("Credit transfer batch for expense payment: anomaly for expense %s",
							expense.getExpenseSeq()));
				}
			}
		}

	}

	@Override
	protected void stop() {
		StringBuilder sb = new StringBuilder();
		sb.append(I18n.get(IExceptionMessage.BATCH_CREDIT_TRANSFER_REPORT_TITLE));
		sb.append(String.format(
				I18n.get(com.axelor.apps.hr.exception.IExceptionMessage.BATCH_CREDIT_TRANSFER_EXPENSE_DONE_SINGULAR,
						com.axelor.apps.hr.exception.IExceptionMessage.BATCH_CREDIT_TRANSFER_EXPENSE_DONE_PLURAL,
						batch.getDone()),
				batch.getDone()));
		sb.append(String.format(
				I18n.get(IExceptionMessage.BATCH_CREDIT_TRANSFER_ANOMALY_SINGULAR,
						IExceptionMessage.BATCH_CREDIT_TRANSFER_ANOMALY_PLURAL, batch.getAnomaly()),
				batch.getAnomaly()));
		addComment(sb.toString());
		super.stop();
	}

	@Transactional(rollbackOn = { AxelorException.class, Exception.class })
	protected void addPayment(Expense expense) throws AxelorException {
		log.debug(String.format("Credit transfer batch for expense payment: adding payment for expense %s",
				expense.getExpenseSeq()));
		expenseService.addPayment(expense);
	}

}
