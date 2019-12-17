package io.renren.common.validator;

import io.renren.common.exception.RRException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import java.util.Set;

/**
 * hibernate-validator校验工具类
 * 参考文档：http://docs.jboss.org/hibernate/validator/5.4/reference/en-US/html_single/
 */
public class ValidatorUtils {
    private static final Logger logger = LoggerFactory.getLogger(ValidatorUtils.class);

    private static Validator validator;

    static {
        //得到一个验证器实例
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    /**
     * 校验对象
     * @param object    待校验对象
     * @param groups    待校验的组
     */
    public static void validateEntity(Object object, Class<?>... groups) {
        logger.debug("开始校验对象...");

        //把对象放到验证器的验证方法中，用Set存储违背约束的对象
        Set<ConstraintViolation<Object>> constraintViolations = validator.validate(object, groups);
        if (!constraintViolations.isEmpty()) {
            StringBuilder msg = new StringBuilder();
            for (ConstraintViolation<Object> constraint : constraintViolations) {
                msg.append(constraint.getMessage()).append("<br>");
                logger.debug("msg内容为： " + msg.toString());
            }
            throw new RRException(msg.toString());
        }
    }
}
