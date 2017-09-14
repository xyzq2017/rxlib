package org.rx.validator;

import java.lang.annotation.*;

@Target({ ElementType.TYPE, ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface ValidFlag {
    static final int ParameterValues = 1;
    static final int Method          = 1 << 1;
    static final int All             = ParameterValues | Method;

    int value() default All;
}
