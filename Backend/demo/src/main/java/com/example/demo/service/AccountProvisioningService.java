package com.example.demo.service;

import com.example.demo.entity.Account;
import com.example.demo.entity.Card;
import com.example.demo.entity.Loan;
import com.example.demo.repository.AccountRepository;
import com.example.demo.repository.CardRepository;
import com.example.demo.repository.LoanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

/**
 * Creates starter banking records for a user so their dashboard is never empty.
 *
 * <p>Called right after a successful registration (when {@code app.seed.provision-on-register}
 * is true) and used by the demo initializer to give the admin account some sample data.</p>
 */
@Service
public class AccountProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(AccountProvisioningService.class);
    private static final String[] LOAN_PRODUCTS = {
            "HOME_LOAN", "PERSONAL_LOAN", "AUTO_LOAN", "EDUCATION_LOAN", "GOLD_LOAN"
    };

    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final LoanRepository loanRepository;
    private final boolean provisionOnRegister;

    public AccountProvisioningService(AccountRepository accountRepository,
                                      CardRepository cardRepository,
                                      LoanRepository loanRepository,
                                      @Value("${app.seed.provision-on-register:true}") boolean provisionOnRegister) {
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.loanRepository = loanRepository;
        this.provisionOnRegister = provisionOnRegister;
    }

    /** Provisions starter data for new registrations, honouring the config toggle. */
    public void provisionForNewUser(Long userId) {
        if (!provisionOnRegister) {
            return;
        }
        provisionStarterData(userId);
    }

    /** Unconditionally creates 1-2 accounts, a card and (sometimes) a loan for the user. */
    public void provisionStarterData(Long userId) {
        Random rnd = new Random(userId * 31L + 7L);

        int accounts = 1 + rnd.nextInt(2);
        for (int i = 0; i < accounts; i++) {
            accountRepository.save(new Account(userId, uniqueAccountNumber(rnd), "INR", money(2500, 150_000, rnd)));
        }

        String type = rnd.nextBoolean() ? "CREDIT" : "DEBIT";
        BigDecimal limit = money(25_000, 175_000, rnd);
        BigDecimal outstanding = "CREDIT".equals(type) ? pct(limit, rnd.nextInt(50)) : new BigDecimal("0.00");
        cardRepository.save(new Card(userId, type, String.format("%04d", rnd.nextInt(10_000)),
                limit, outstanding, "ACTIVE"));

        if (rnd.nextInt(10) < 5) {
            BigDecimal principal = money(50_000, 950_000, rnd);
            BigDecimal rate = BigDecimal.valueOf(850 + rnd.nextInt(650), 2); // 8.50% .. 15.00%
            loanRepository.save(new Loan(userId, LOAN_PRODUCTS[rnd.nextInt(LOAN_PRODUCTS.length)],
                    principal, rate, pct(principal, 40 + rnd.nextInt(60)), "ACTIVE"));
        }

        log.info("Provisioned starter records for user id {}", userId);
    }

    private String uniqueAccountNumber(Random rnd) {
        for (int i = 0; i < 20; i++) {
            String candidate = "62" + String.format("%014d", rnd.nextLong(100_000_000_000_000L));
            if (!accountRepository.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique account number");
    }

    private static BigDecimal money(int base, int spread, Random rnd) {
        return BigDecimal.valueOf(base + rnd.nextInt(spread))
                .add(BigDecimal.valueOf(rnd.nextInt(100), 2))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal amount, int percent) {
        return amount.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
