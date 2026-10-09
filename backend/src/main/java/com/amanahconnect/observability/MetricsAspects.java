package com.amanahconnect.observability;

import com.amanahconnect.billing.BillingDtos.GenerateResult;
import com.amanahconnect.common.error.ApiException;
import com.amanahconnect.mail.EmailSender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Business metrics, recorded around the code instead of inside it, so the services stay readable.
 *
 * <ul>
 *   <li>{@code amanah.auth.attempts{type,result}}: logins, second factors and refreshes; result is success, invalid_credentials, locked, rate_limited or error</li>
 *   <li>{@code amanah.email.processed{result}}: sent, retrying, failed (per run of the sender)</li>
 *   <li>{@code amanah.invoices.generated}: invoices created by fee-plan generation, and {@code amanah.invoice.generation} timing</li>
 *   <li>{@code amanah.job.duration{job,result}}: every scheduled job</li>
 * </ul>
 * No tag ever holds an email address, id or other personal data: the cardinality is fixed and small.
 */
@Aspect
@Component
public class MetricsAspects {

    private final MeterRegistry registry;

    public MetricsAspects(MeterRegistry registry) {
        this.registry = registry;
    }

    @Around("execution(* com.amanahconnect.auth.AuthService.login(..))")
    public Object login(ProceedingJoinPoint call) throws Throwable {
        return attempt("login", call);
    }

    @Around("execution(* com.amanahconnect.auth.AuthService.loginWithSecondFactor(..))")
    public Object secondFactor(ProceedingJoinPoint call) throws Throwable {
        return attempt("second_factor", call);
    }

    @Around("execution(* com.amanahconnect.auth.AuthService.refresh(..))")
    public Object refresh(ProceedingJoinPoint call) throws Throwable {
        return attempt("refresh", call);
    }

    private Object attempt(String type, ProceedingJoinPoint call) throws Throwable {
        String result = "success";
        try {
            return call.proceed();
        } catch (ApiException e) {
            result = switch (e.code()) {
                case INVALID_CREDENTIALS -> "invalid_credentials";
                case ACCOUNT_LOCKED -> "locked";
                case RATE_LIMITED -> "rate_limited";
                default -> "refused";
            };
            throw e;
        } catch (RuntimeException e) {
            result = "error";
            throw e;
        } finally {
            registry.counter("amanah.auth.attempts", "type", type, "result", result).increment();
        }
    }

    @Around("execution(* com.amanahconnect.mail.EmailSender.runOnce())")
    public Object emailRun(ProceedingJoinPoint call) throws Throwable {
        Object out = call.proceed();
        if (out instanceof EmailSender.Result r) {
            registry.counter("amanah.email.processed", "result", "sent").increment(r.sent());
            registry.counter("amanah.email.processed", "result", "retrying").increment(r.retrying());
            registry.counter("amanah.email.processed", "result", "failed").increment(r.failed());
        }
        return out;
    }

    @Around("execution(* com.amanahconnect.billing.InvoiceGenerationService.generate(..))")
    public Object invoices(ProceedingJoinPoint call) throws Throwable {
        Timer.Sample sample = Timer.start(registry);
        String result = "ok";
        try {
            Object out = call.proceed();
            if (out instanceof GenerateResult r) registry.counter("amanah.invoices.generated").increment(r.created());
            return out;
        } catch (Throwable t) {
            result = "error";
            throw t;
        } finally {
            sample.stop(registry.timer("amanah.invoice.generation", "result", result));
        }
    }

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object job(ProceedingJoinPoint call) throws Throwable {
        long start = System.nanoTime();
        String result = "ok";
        try {
            return call.proceed();
        } catch (Throwable t) {
            result = "error";
            throw t;
        } finally {
            String job = call.getSignature().getDeclaringType().getSimpleName() + "." + call.getSignature().getName();
            registry.timer("amanah.job.duration", "job", job, "result", result).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }
}
