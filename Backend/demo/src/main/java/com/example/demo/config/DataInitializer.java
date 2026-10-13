package com.example.demo.config;

import com.example.demo.entity.Account;
import com.example.demo.entity.Card;
import com.example.demo.entity.Loan;
import com.example.demo.entity.User;
import com.example.demo.repository.AccountRepository;
import com.example.demo.repository.CardRepository;
import com.example.demo.repository.LoanRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.AccountProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Random;

/**
 * Seeds demo users and financial records on startup.
 *
 * <p>Idempotent: each block is skipped when its marker user already exists, so it is safe to
 * restart without creating duplicates. Enable/disable with {@code app.seed.enabled} and control
 * how many extra dummy customers are created with {@code app.seed.dummy-customers}.</p>
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final String DEMO_PASSWORD = "password123";
    private static final String[] DUMMY_NAMES = {
            "rahul", "priya", "arjun", "sneha", "vikram", "ananya",
            "rohit", "kavya", "imran", "neha", "dev", "meera"
    };
    private static final String[] LOAN_PRODUCTS = {
            "HOME_LOAN", "PERSONAL_LOAN", "AUTO_LOAN", "EDUCATION_LOAN", "GOLD_LOAN"
    };

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final LoanRepository loanRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountProvisioningService accountProvisioningService;
    private final boolean seedEnabled;
    private final int dummyCustomers;

    public DataInitializer(UserRepository userRepository,
                           AccountRepository accountRepository,
                           CardRepository cardRepository,
                           LoanRepository loanRepository,
                           PasswordEncoder passwordEncoder,
                           AccountProvisioningService accountProvisioningService,
                           @Value("${app.seed.enabled:true}") boolean seedEnabled,
                           @Value("${app.seed.dummy-customers:12}") int dummyCustomers) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.loanRepository = loanRepository;
        this.passwordEncoder = passwordEncoder;
        this.accountProvisioningService = accountProvisioningService;
        this.seedEnabled = seedEnabled;
        this.dummyCustomers = dummyCustomers;
    }

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("Seeding disabled (app.seed.enabled=false). Skipping demo data.");
            return;
        }
        seedCoreUsers();
        seedDummyCustomers();
    }

    private void seedCoreUsers() {
        if (!userRepository.existsByUsername("alice")) {
            User alice = userRepository.save(new User("alice", passwordEncoder.encode(DEMO_PASSWORD), "USER"));
            accountRepository.save(new Account(alice.getId(), "5010020045678901", "INR", new BigDecimal("125430.75")));
            accountRepository.save(new Account(alice.getId(), "5010020045678902", "INR", new BigDecimal("18250.00")));
            cardRepository.save(new Card(alice.getId(), "CREDIT", "4321",
                    new BigDecimal("200000.00"), new BigDecimal("45250.75"), "ACTIVE"));
            cardRepository.save(new Card(alice.getId(), "DEBIT", "9876",
                    new BigDecimal("100000.00"), new BigDecimal("0.00"), "ACTIVE"));
            loanRepository.save(new Loan(alice.getId(), "HOME_LOAN",
                    new BigDecimal("3000000.00"), new BigDecimal("8.75"), new BigDecimal("2750000.00"), "ACTIVE"));
            loanRepository.save(new Loan(alice.getId(), "PERSONAL_LOAN",
                    new BigDecimal("500000.00"), new BigDecimal("13.50"), new BigDecimal("185000.00"), "ACTIVE"));
            log.info("Seeded demo user 'alice' (password123) with accounts, cards and loans.");
        }

        if (!userRepository.existsByUsername("admin")) {
            userRepository.save(new User("admin", passwordEncoder.encode("admin123"), "ADMIN"));
            log.info("Seeded admin user 'admin' (admin123).");
        }

        userRepository.findByUsername("admin").ifPresent(admin -> {
            if (accountRepository.findByUserId(admin.getId()).isEmpty()
                    && cardRepository.findByUserId(admin.getId()).isEmpty()
                    && loanRepository.findByUserId(admin.getId()).isEmpty()) {
                accountProvisioningService.provisionStarterData(admin.getId());
                log.info("Provisioned starter records for 'admin'.");
            }
        });
    }

    private void seedDummyCustomers() {
        if (dummyCustomers <= 0) {
            return;
        }
        int target = Math.min(dummyCustomers, DUMMY_NAMES.length);
        if (userRepository.existsByUsername(DUMMY_NAMES[0])) {
            log.info("Dummy customers already present; skipping dummy seed.");
            return;
        }

        Random rnd = new Random(42L);
        long accountSeq = 5_010_020_046_000_001L;
        int accounts = 0;
        int cards = 0;
        int loans = 0;

        for (int i = 0; i < target; i++) {
            User u = userRepository.save(new User(DUMMY_NAMES[i], passwordEncoder.encode(DEMO_PASSWORD), "USER"));

            for (int a = 0; a < 1 + rnd.nextInt(3); a++) {
                accountRepository.save(new Account(u.getId(), String.valueOf(accountSeq++), "INR",
                        money(5000, 195_000, rnd)));
                accounts++;
            }

            for (int c = 0; c < rnd.nextInt(3); c++) {
                String type = rnd.nextBoolean() ? "CREDIT" : "DEBIT";
                BigDecimal limit = money(50_000, 150_000, rnd);
                BigDecimal outstanding = "CREDIT".equals(type) ? pct(limit, rnd.nextInt(60)) : new BigDecimal("0.00");
                String status = rnd.nextInt(10) == 0 ? "BLOCKED" : "ACTIVE";
                cardRepository.save(new Card(u.getId(), type, String.format("%04d", rnd.nextInt(10_000)),
                        limit, outstanding, status));
                cards++;
            }

            for (int l = 0; l < rnd.nextInt(3); l++) {
                BigDecimal principal = money(100_000, 2_900_000, rnd);
                BigDecimal rate = BigDecimal.valueOf(800 + rnd.nextInt(701), 2); // 8.00% .. 15.00%
                BigDecimal outstanding = pct(principal, 30 + rnd.nextInt(70));
                String status = rnd.nextInt(10) < 7 ? "ACTIVE" : "CLOSED";
                loanRepository.save(new Loan(u.getId(), LOAN_PRODUCTS[rnd.nextInt(LOAN_PRODUCTS.length)],
                        principal, rate, outstanding, status));
                loans++;
            }
        }

        log.info("Seeded {} dummy customers (password '{}'): {} accounts, {} cards, {} loans.",
                target, DEMO_PASSWORD, accounts, cards, loans);
    }

    private static BigDecimal money(int base, int spread, Random rnd) {
        long whole = base + rnd.nextInt(spread);
        return BigDecimal.valueOf(whole).add(BigDecimal.valueOf(rnd.nextInt(100), 2))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal amount, int percent) {
        return amount.multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
