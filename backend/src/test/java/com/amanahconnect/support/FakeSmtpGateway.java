package com.amanahconnect.support;

import com.amanahconnect.mail.OutgoingMail;
import com.amanahconnect.mail.SmtpFailure;
import com.amanahconnect.mail.SmtpGateway;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Stands in for SMTP in tests: remembers what would have been sent and can be told to fail. */
@Component
@Primary
public class FakeSmtpGateway implements SmtpGateway {

    public final List<OutgoingMail> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();
    private volatile Function<OutgoingMail, SmtpFailure> failure = mail -> null;

    @Override
    public String send(OutgoingMail mail) {
        SmtpFailure failed = failure.apply(mail);
        if (failed != null) throw failed;
        sent.add(mail);
        return "fake-message-" + counter.incrementAndGet();
    }

    /** Every send fails with this until {@link #reset()}. */
    public void failAll(boolean permanent, String message) {
        failure = mail -> new SmtpFailure(message, permanent, null);
    }

    /** Sends to this address fail. */
    public void failFor(String address, boolean permanent) {
        failure = mail -> mail.to().equalsIgnoreCase(address) ? new SmtpFailure("rejected " + address, permanent, null) : null;
    }

    /** The next {@code n} sends fail (temporarily), then it works again. */
    public void failNext(int n) {
        AtomicInteger left = new AtomicInteger(n);
        failure = mail -> left.getAndDecrement() > 0 ? new SmtpFailure("421 try again later", false, null) : null;
    }

    public void reset() {
        failure = mail -> null;
        sent.clear();
    }

    public List<OutgoingMail> sentTo(String address) {
        return sent.stream().filter(m -> m.to().equalsIgnoreCase(address)).toList();
    }
}
