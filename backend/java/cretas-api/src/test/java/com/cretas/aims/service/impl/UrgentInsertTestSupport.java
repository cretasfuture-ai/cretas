package com.cretas.aims.service.impl;

import com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

/**
 * Builds the Lombok-constructor services from a type->instance map, mocking every
 * collaborator that is not supplied. Reflection on purpose: PR #912 swaps one
 * constructor dependency of UrgentInsertServiceImpl (UserRepository ->
 * CurrentOperatorResolver); these tests must not care which of the two is present.
 */
final class UrgentInsertTestSupport {

    private UrgentInsertTestSupport() {}

    /** 默认评估器: 原料够, 指纹固定为 {@link #MATERIAL_FP}; 需要别的结果的测试自己传一个进来。 */
    static final String MATERIAL_FP = "FP-MATERIAL-OK";

    static UrgentInsertMaterialAssessor okAssessor() {
        UrgentInsertMaterialAssessor assessor = Mockito.mock(UrgentInsertMaterialAssessor.class);
        Mockito.when(assessor.assess(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(new UrgentInsertMaterialAssessor.Assessment(
                        UrgentInsertMaterialAssessor.Status.OK, null, "RECIPE-1", 3,
                        java.util.List.of(), MATERIAL_FP));
        return assessor;
    }

    static <T> T construct(Class<T> type, Map<Class<?>, Object> provided) {
        try {
            Constructor<?> ctor = Arrays.stream(type.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow();
            Class<?>[] params = ctor.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = provided.containsKey(params[i]) ? provided.get(params[i])
                        : params[i] == UrgentInsertMaterialAssessor.class ? okAssessor()
                        : Mockito.mock(params[i]);
            }
            ctor.setAccessible(true);
            return type.cast(ctor.newInstance(args));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot construct " + type.getSimpleName(), e);
        }
    }
}
