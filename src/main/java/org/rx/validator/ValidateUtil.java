package org.rx.validator;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.ConstructorSignature;
import org.aspectj.lang.reflect.MethodSignature;
import org.rx.SystemException;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.executable.ExecutableValidator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import org.rx.Logger;
import org.rx.util.StringBuilder;
import org.rx.util.ThrowableFunc;

import java.util.Set;

import static org.rx.Contract.toJsonString;

/**
 * http://www.cnblogs.com/pixy/p/5306567.html
 */
public class ValidateUtil {
    /**
     * 验证bean实体 @Valid deep valid
     *
     * @param bean
     */
    public static void validateBean(Object bean) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        for (ConstraintViolation<Object> violation : validator.validate(bean)) {
            doThrow(violation);
        }
    }

    private static boolean hasFlags(int flags, int values) {
        return (flags & values) == values;
    }

    private static void doThrow(ConstraintViolation<Object> violation) {
        String pn = violation.getPropertyPath().toString(), vm = violation.getMessage();
        throw new ConstraintException(pn, vm,
                String.format("%s.%s%s", violation.getRootBeanClass().getSimpleName(), pn, vm));
    }

    public static void validateConstructor(Constructor member, Object[] parameterValues, boolean validateValues) {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ExecutableValidator executableValidator = validator.forExecutables();
        Set<ConstraintViolation<Object>> result = executableValidator.validateConstructorParameters(member,
                parameterValues);
        for (ConstraintViolation<Object> violation : result) {
            doThrow(violation);
        }
        if (validateValues && parameterValues != null) {
            for (Object parameterValue : parameterValues) {
                validateBean(parameterValue);
            }
        }
    }

    public static Object validateMethod(Method member, Object instance, Object[] parameterValues,
                                        boolean validateValues, ThrowableFunc returnValueFuc)
            throws Throwable {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        ExecutableValidator executableValidator = validator.forExecutables();
        for (ConstraintViolation<Object> violation : executableValidator.validateParameters(instance, member,
                parameterValues)) {
            doThrow(violation);
        }
        if (validateValues && parameterValues != null) {
            for (Object parameterValue : parameterValues) {
                validateBean(parameterValue);
            }
        }

        if (returnValueFuc == null) {
            return null;
        }
        Object retVal;
        for (ConstraintViolation<Object> violation : executableValidator.validateReturnValue(instance, member,
                retVal = returnValueFuc.invoke(null))) {
            doThrow(violation);
        }
        return retVal;
    }

    /**
     * Annotation expression只对method有效
     * 
     * @param joinPoint
     * @return
     * @throws Throwable
     */
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        Class targetType = joinPoint.getTarget().getClass();
        Signature signature = joinPoint.getSignature();
        Executable member;
        if (signature instanceof ConstructorSignature) {
            member = ((ConstructorSignature) signature).getConstructor();
        } else {
            member = ((MethodSignature) signature).getMethod();
        }

        StringBuilder msg = new StringBuilder();
        msg.setPrefix(String.format("[Validating] %s.%s ", targetType.getSimpleName(), signature.getName()));
        try {
            msg.appendLine("begin check..");
            EnableValid attr = member.getAnnotation(EnableValid.class);
            if (attr == null) {
                attr = (EnableValid) targetType.getAnnotation(EnableValid.class);
                if (attr == null) {
                    msg.appendLine("exit..");
                    return joinPoint.proceed();
                }
            }
            msg.appendLine("begin validate args=%s..", toJsonString(joinPoint.getArgs()));

            int flags = attr.value();
            boolean validateValues = hasFlags(flags, EnableValid.ParameterValues);
            if (hasFlags(flags, EnableValid.Method)) {
                if (signature instanceof ConstructorSignature) {
                    ConstructorSignature cs = (ConstructorSignature) signature;
                    validateConstructor(cs.getConstructor(), joinPoint.getArgs(), validateValues);
                    return onProcess(joinPoint, msg);
                }

                MethodSignature ms = (MethodSignature) signature;
                return validateMethod(ms.getMethod(), joinPoint.getTarget(), joinPoint.getArgs(), validateValues,
                        p -> onProcess(joinPoint, msg));
            }

            if (validateValues) {
                for (Object parameterValue : joinPoint.getArgs()) {
                    validateBean(parameterValue);
                }
            }
            return onProcess(joinPoint, msg);
        } catch (Exception ex) {
            msg.appendLine("validate fail %s..", ex.getMessage());
            return onException(ex);
        } finally {
            msg.appendLine("end validate..");
            Logger.info(msg.toString());
        }
    }

    protected Object onProcess(ProceedingJoinPoint joinPoint, StringBuilder msg) throws Throwable {
        return joinPoint.proceed();
    }

    protected Object onException(Exception ex) {
        throw SystemException.wrap(ex);
    }
}
