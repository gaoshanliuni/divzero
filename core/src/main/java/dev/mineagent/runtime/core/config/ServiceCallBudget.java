package dev.mineagent.runtime.core.config;

import java.util.Map;
import java.util.Set;

/** Dispatch attempts, not tokens, cost or successful business operations. */
public record ServiceCallBudget(int dailyLimit, int requestLimit, boolean paused, int taskLimit) {
    public ServiceCallBudget(int dailyLimit,int requestLimit,boolean paused){this(dailyLimit,requestLimit,paused,0);}
    public static final String TASK = "services.budget.taskAttempts";
    public static final String DAILY = "services.budget.dailyAttempts";
    public static final String REQUEST = "services.budget.requestAttempts";
    public static final String PAUSED = "services.budget.paused";
    public static final Set<String> KEYS = Set.of(DAILY, REQUEST, PAUSED, TASK);
    public static final Set<String> ERRORS = Set.of("SERVICE_BUDGET_PAUSED", "SERVICE_BUDGET_DAILY_LIMIT",
            "SERVICE_BUDGET_REQUEST_LIMIT", "SERVICE_BUDGET_REPLAY", "SERVICE_BUDGET_CAPACITY",
            "SERVICE_BUDGET_UNAVAILABLE", "SERVICE_BUDGET_NOT_CONFIGURED", "SERVICE_BUDGET_CONFIG_INVALID", "SERVICE_BUDGET_TASK_CONTEXT", "SERVICE_BUDGET_TASK_UNRESOLVED", "SERVICE_BUDGET_TASK_LIMIT");

    public static String responseError(dev.mineagent.runtime.api.worker.WorkerEnvelope response, String fallback) {
        if (response == null || !response.type().equals("error")) return fallback;
        String code = String.valueOf(response.payload().get("code"));
        return ERRORS.contains(code) ? code : fallback;
    }

    public static ServiceCallBudget from(Map<String, String> values) {
        String paused = values.getOrDefault(PAUSED, "false");
        if (!Set.of("true", "false").contains(paused)) throw new IllegalArgumentException("SERVICE_BUDGET_CONFIG_INVALID");
        return new ServiceCallBudget(number(values, DAILY, 1_000_000), number(values, REQUEST, 10_000), Boolean.parseBoolean(paused), number(values,TASK,1_000_000));
    }

    private static int number(Map<String, String> values, String key, int maximum) {
        String raw = values.getOrDefault(key, "0");
        try {
            if (!raw.matches("[0-9]{1,7}")) throw new NumberFormatException();
            int value = Integer.parseInt(raw);
            if (value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) { throw new IllegalArgumentException("SERVICE_BUDGET_CONFIG_INVALID"); }
    }
}
