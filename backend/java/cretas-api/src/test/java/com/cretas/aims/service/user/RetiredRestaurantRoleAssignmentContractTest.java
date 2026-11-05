package com.cretas.aims.service.user;

import com.cretas.aims.dto.user.CreateUserRequest;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.impl.UserServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import com.cretas.aims.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RetiredRestaurantRoleAssignmentContractTest {

    @Mock private UserRepository userRepository;
    @Mock private FactoryRepository factoryRepository;
    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ExcelUtil excelUtil;

    @InjectMocks
    private UserServiceImpl service;

    @ParameterizedTest
    @EnumSource(value = FactoryUserRole.class, names = "restaurant_.*", mode = EnumSource.Mode.MATCH_ALL)
    void createRejectsEveryRetiredRestaurantRoleBeforePersistence(FactoryUserRole role) {
        CreateUserRequest request = requestWithRole(role);

        assertThatThrownBy(() -> service.createUser("F006", request))
                .hasMessageContaining("餐饮专属角色已退役");

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private static CreateUserRequest requestWithRole(FactoryUserRole role) {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("retired-role-user");
        request.setRoleCode(role);
        return request;
    }
}
