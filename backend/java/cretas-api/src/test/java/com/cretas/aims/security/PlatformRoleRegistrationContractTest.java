package com.cretas.aims.security;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.Whitelist;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.WhitelistStatus;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.*;
import com.cretas.aims.service.mobile.MobileDeviceService;
import com.cretas.aims.service.mobile.impl.MobileAuthServiceImpl;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformRoleRegistrationContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"platform_admin", "platform_adm\u0131n", "\u017Fuper_admin", "operator"})
    void invitationCannotGrantPlatformRolesAndRefusalKeepsTheToken(String position) {
        var users = mock(UserRepository.class);
        var invitations = mock(WhitelistRepository.class);
        var tokens = mock(TempTokenService.class);
        var encoder = mock(PasswordEncoder.class);
        var service = new MobileAuthServiceImpl(users, mock(SessionRepository.class),
                mock(PlatformAdminRepository.class), invitations, encoder, mock(JwtUtil.class),
                mock(UserMapper.class), tokens, mock(TokenBlacklistService.class),
                mock(MobileDeviceService.class), mock(PermissionService.class));
        String phone = "13800138031";
        var invitation = Whitelist.builder().factoryId("F001").phoneNumber(phone)
                .name("邀请测试").status(WhitelistStatus.ACTIVE)
                .invitedRoleCode(position.equals("platform_admin")
                        ? FactoryUserRole.platform_admin : FactoryUserRole.operator)
                .position(position).addedBy(1L).build();
        when(tokens.validateAndGetPhone("temp")).thenReturn(phone);
        when(invitations.findByFactoryIdAndPhoneNumber("F001", phone)).thenReturn(Optional.of(invitation));
        var request = MobileDTO.RegisterPhaseTwoRequest.builder().tempToken("temp")
                .username(phone).factoryId("F001").password("secure123")
                .realName("邀请测试").build();
        if (!position.equals("operator")) {
            assertThatThrownBy(() -> service.registerPhaseTwo(request))
                    .hasMessageContaining("平台角色不能分配给工厂账号");
            verify(users, never()).save(any());
            verify(tokens, never()).deleteTempToken(anyString());
        } else {
            when(users.save(any(User.class))).thenAnswer(call -> {
                User saved = call.getArgument(0);
                saved.setId(501L);
                return saved;
            });
            assertThat(service.registerPhaseTwo(request).getSuccess()).isTrue();
            verify(users).save(any(User.class));
            verify(tokens).deleteTempToken("temp");
        }
    }
}
