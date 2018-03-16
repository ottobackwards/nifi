package org.apache.nifi.processors.aws.wag;

import java.util.Optional;
import java.util.function.Predicate;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;

public class RequestMatcher<T> extends BaseMatcher<T> {

    private final Predicate<T> matcher;
    private final Optional<String> description;

    public RequestMatcher(Predicate<T> matcher) {
        this(matcher, null);
    }

    public RequestMatcher(Predicate<T> matcher, String description) {
        this.matcher = matcher;
        this.description = Optional.ofNullable(description);
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean matches(Object argument) {
        return matcher.test((T) argument);
    }

    @Override
    public void describeTo(Description description) {
        this.description.ifPresent(description::appendText);
    }
}
