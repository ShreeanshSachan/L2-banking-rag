package com.example.demo.controller;

import com.example.demo.dto.UserDataDtos.AccountView;
import com.example.demo.dto.UserDataDtos.BalanceResponse;
import com.example.demo.dto.UserDataDtos.CardView;
import com.example.demo.dto.UserDataDtos.LoanView;
import com.example.demo.repository.AccountRepository;
import com.example.demo.repository.CardRepository;
import com.example.demo.repository.LoanRepository;
import com.example.demo.security.BankUserPrincipal;
import com.example.demo.service.MaskingUtil;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/me")
public class UserDataController {

    private final AccountRepository accountRepository;
    private final CardRepository cardRepository;
    private final LoanRepository loanRepository;
    private final MaskingUtil maskingUtil;

    public UserDataController(AccountRepository accountRepository,
                             CardRepository cardRepository,
                             LoanRepository loanRepository,
                             MaskingUtil maskingUtil) {
        this.accountRepository = accountRepository;
        this.cardRepository = cardRepository;
        this.loanRepository = loanRepository;
        this.maskingUtil = maskingUtil;
    }

    @GetMapping("/balance")
    public BalanceResponse balance(@AuthenticationPrincipal BankUserPrincipal principal) {
        List<AccountView> accounts = accountRepository.findByUserId(principal.getUserId()).stream()
                .map(a -> new AccountView(
                        maskingUtil.maskAccountNumber(a.getAccountNumber()),
                        a.getCurrency(),
                        a.getBalance()))
                .toList();
        return new BalanceResponse(accounts);
    }

    @GetMapping("/cards")
    public List<CardView> cards(@AuthenticationPrincipal BankUserPrincipal principal) {
        return cardRepository.findByUserId(principal.getUserId()).stream()
                .map(c -> new CardView(c.getId(), c.getType(), c.getLast4(),
                        c.getCreditLimit(), c.getOutstanding(), c.getStatus()))
                .toList();
    }

    @GetMapping("/loans")
    public List<LoanView> loans(@AuthenticationPrincipal BankUserPrincipal principal) {
        return loanRepository.findByUserId(principal.getUserId()).stream()
                .map(l -> new LoanView(l.getId(), l.getProductType(), l.getPrincipal(),
                        l.getInterestRate(), l.getOutstanding(), l.getStatus()))
                .toList();
    }
}
