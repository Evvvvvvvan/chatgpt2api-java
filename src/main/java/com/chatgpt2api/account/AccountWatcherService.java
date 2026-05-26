package com.chatgpt2api.account;

import com.chatgpt2api.config.AppConfigService;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AccountWatcherService {
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
                    accounts.refreshAccounts(new ArrayList<String>(refreshSet));
                }
                List<String> keepalive = accounts.listRefreshTokenKeepaliveTokens();
                keepalive.removeAll(expiring);
                if (!keepalive.isEmpty()) {
                    accounts.keepaliveRefreshTokens(keepalive);
                }
            } catch (RuntimeException ignored) {
                // 后台刷新失败不影响接口服务运行，下一周期继续重试。
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
