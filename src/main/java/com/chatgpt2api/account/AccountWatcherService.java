package com.chatgpt2api.account;

import com.chatgpt2api.config.AppConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AccountWatcherService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccountWatcherService.class);
    private final AccountService accounts;
    private final AppConfigService config;
    private volatile boolean running;
    private Thread worker;

    public AccountWatcherService(AccountService accounts, AppConfigService config) {
        this.accounts = accounts;
        this.config = config;
    }

    @PostConstruct
    public void start() {
        running = true;
        worker = new Thread(this::watch, "account-watcher");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void watch() {
        while (running) {
            try {
                List<String> limited = accounts.listLimitedTokens();
                List<String> expiring = accounts.listExpiringAccessTokens();
                Set<String> refreshSet = new LinkedHashSet<String>();
                refreshSet.addAll(limited);
                refreshSet.addAll(expiring);
                if (!refreshSet.isEmpty()) {
                    LOGGER.info("[account-watcher] refreshing limited={} expiring={}", limited.size(), expiring.size());
                    accounts.refreshAccounts(new ArrayList<String>(refreshSet));
                }
                List<String> keepalive = accounts.listRefreshTokenKeepaliveTokens();
                keepalive.removeAll(expiring);
                if (!keepalive.isEmpty()) {
                    LOGGER.info("[account-watcher] keepalive refreshTokens={}", keepalive.size());
                    accounts.keepaliveRefreshTokens(keepalive);
                }
            } catch (RuntimeException exception) {
                LOGGER.error("[account-watcher] cycle failed: {}", exception.getMessage(), exception);
            }
            waitForNextCycle();
        }
    }

    private void waitForNextCycle() {
        long interval = Math.max(1, config.getRefreshAccountIntervalMinute()) * 60L * 1000L;
        try {
            Thread.sleep(interval);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
