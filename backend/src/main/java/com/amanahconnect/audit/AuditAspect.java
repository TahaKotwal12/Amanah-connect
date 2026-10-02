package com.amanahconnect.audit;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/**
 * Implements {@link Audited}. The aspect itself owns (or joins) the transaction, so the audited
 * change and its audit row are atomic no matter how it is ordered relative to {@code @Transactional}.
 */
@Aspect
@Component
public class AuditAspect {

    private final AuditService auditService;
    private final AuditRedactor redactor;
    private final PlatformTransactionManager transactions;

    public AuditAspect(AuditService auditService, AuditRedactor redactor, PlatformTransactionManager transactions) {
        this.auditService = auditService;
        this.redactor = redactor;
        this.transactions = transactions;
    }

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint call, Audited audited) throws Throwable {
        TransactionStatus status = transactions.getTransaction(new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_REQUIRED));
        try {
            Object result = call.proceed();
            Map<String, Object> after = redactor.redactToMap(result);
            auditService.record(
                    audited.action(),
                    entityType(audited, result, call),
                    entityId(result, after),
                    null,
                    after);
            transactions.commit(status);
            return result;
        } catch (Throwable failure) {
            if (!status.isCompleted()) {
                transactions.rollback(status);
            }
            throw failure;
        }
    }

    private static String entityType(Audited audited, Object result, ProceedingJoinPoint call) {
        if (!audited.entityType().isBlank()) {
            return audited.entityType();
        }
        return result != null ? result.getClass().getSimpleName() : call.getSignature().getDeclaringType().getSimpleName();
    }

    private static UUID entityId(Object result, Map<String, Object> redacted) {
        if (result instanceof UUID id) {
            return id;
        }
        if (result != null) {
            for (String name : new String[] {"id", "getId"}) {
                try {
                    Method accessor = result.getClass().getMethod(name);
                    if (accessor.invoke(result) instanceof UUID id) {
                        return id;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // try the next accessor
                }
            }
        }
        if (redacted != null && redacted.get("id") instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                // not a UUID: record without an entity id
            }
        }
        return null;
    }
}
