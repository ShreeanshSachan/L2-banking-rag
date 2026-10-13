package com.example.demo.config;

import com.example.demo.entity.Account;
import com.example.demo.entity.Card;
import com.example.demo.entity.Loan;
import com.example.demo.entity.User;
import com.example.demo.repository.AccountRepository;
import com.example.demo.repository.CardRepository;
import com.example.demo.repository.LoanRepository;
import com.example.demo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Seeds demo users and financial records when the database is empty.
 * If the database was already populated (e.g. by db/init.sql in Docker) this is a no-op.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final LoanRepository loanRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository,
                           AccountRepository accountRepository,
                           CardRepository cardRepository,
                           LoanRepository loanRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.loanRepository = loanRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("Database already seeded ({} users). Skipping demo data.", userRepository.count());
            return;
        }

        User alice = userRepository.save(new User("alice", passwordEncoder.encode("password123"), "USER"));
        userRepository.save(new User("admin", passwordEncoder.encode("admin123"), "ADMIN"));

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

        log.info("Seeded demo data: user 'alice' (password123) and 'admin' (admin123).");
    }
}
