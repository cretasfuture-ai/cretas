package com.cretas.aims.service;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号搜索: 关键词 + 角色筛选。
 *
 * <p><b>2026-09-01 改写</b>: 原来这些断言守的是「内存里对当前页过滤」那份实现
 * (Strategy B)。那不是需求, 是当时的实现 —— 而它在工厂用户超过一页时是错的:
 * 按角色筛选只会从「本页这 10 个人」里挑, 第 2 页起的人全漏, 且 total 被重算成
 * 过滤后的条数, 看着像一个合理的小数字。LIUSHANMEN 33 个账号 / 每页 10 条正好踩中。
 *
 * <p>⇒ 两个维度都下推到 SQL, 由 {@code searchUsersByKeywordAndRole} 一条查询承担;
 * 断言随之从「过滤发生在内存」改成「过滤条件被交给了仓储层, 且 total 用仓储层的」。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplSearchTest {

    @Mock UserRepository userRepository;
    @Mock UserMapper userMapper;
    @Mock PasswordEncoder passwordEncoder;
    @Mock com.cretas.aims.repository.FactoryRepository factoryRepository;
    @Mock com.cretas.aims.utils.ExcelUtil excelUtil;

    @InjectMocks UserServiceImpl userService;

    private User salesperson;
    private User warehouseManager;
    private UserDTO salespersonDto;
    private UserDTO warehouseManagerDto;
    private PageRequest pageRequest;

    @BeforeEach
    void setUp() {
        salesperson = new User();
        salesperson.setRoleCode("salesperson");
        salesperson.setFullName("张三");
        salesperson.setUsername("zhangsan");

        warehouseManager = new User();
        warehouseManager.setRoleCode("warehouse_manager");
        warehouseManager.setFullName("李四");
        warehouseManager.setUsername("lisi");

        salespersonDto = new UserDTO();
        warehouseManagerDto = new UserDTO();

        pageRequest = PageRequest.of(1, 50);

        // lenient() — different tests exercise different role filters, so not
        // every test consumes both stubs. Strict mode would flag the unused one.
        lenient().when(userMapper.toDTO(salesperson)).thenReturn(salespersonDto);
        lenient().when(userMapper.toDTO(warehouseManager)).thenReturn(warehouseManagerDto);
    }

    @Test
    @DisplayName("空 keyword 应返回工厂所有用户（不过滤）")
    void searchUsers_emptyKeyword_returnsAll() {
        when(userRepository.searchUsersByKeywordAndRole(eq("F001"), eq(""), eq(""), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(salesperson, warehouseManager)));

        PageResponse<UserDTO> result = userService.searchUsers("F001", "", null, pageRequest);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2L);
    }

    @Test
    @DisplayName("role=salesperson 时角色条件下推给仓储层（⛔ 不在内存里过滤当前页）")
    void searchUsers_withRoleFilter_pushesRoleDownToRepository() {
        when(userRepository.searchUsersByKeywordAndRole(
                eq("F001"), eq(""), eq("salesperson"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(salesperson)));

        PageResponse<UserDTO> result = userService.searchUsers("F001", "", "salesperson", pageRequest);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0)).isSameAs(salespersonDto);
        // 要紧的那一维: role 真的作为查询条件传下去了。若又退回内存过滤,
        // 这个 stub 匹配不上 (role 会是 ""), 返回空页 ⇒ 上面 hasSize(1) 当场红。
        verify(userRepository).searchUsersByKeywordAndRole(
                eq("F001"), eq(""), eq("salesperson"), any(Pageable.class));
    }

    @Test
    @DisplayName("total 用仓储层的总数, ⛔ 不是当前页过滤后的条数")
    void searchUsers_totalComesFromRepositoryNotThePage() {
        // 仓储层说「符合条件的一共 37 个」, 但本页只取回 1 个。
        // 旧实现会把 total 重算成 1 —— 那正是「按角色筛选只看得到第 1 页」的表象。
        when(userRepository.searchUsersByKeywordAndRole(
                eq("F001"), eq(""), eq("salesperson"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(salesperson),
                        org.springframework.data.domain.PageRequest.of(0, 1), 37L));

        PageResponse<UserDTO> result = userService.searchUsers("F001", "", "salesperson", pageRequest);

        assertThat(result.getTotalElements()).isEqualTo(37L);
    }

    @Test
    @DisplayName("role=null（不传）时不过滤，与旧接口行为一致")
    void searchUsers_nullRole_returnsAll() {
        when(userRepository.searchUsersByKeywordAndRole(
                eq("F001"), eq("张"), eq(""), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(salesperson)));

        PageResponse<UserDTO> result = userService.searchUsers("F001", "张", null, pageRequest);

        assertThat(result.getContent()).hasSize(1);
    }

    // ── 缺陷 4 本身: getUserList 此前从头到尾没读过 pageRequest.getKeyword() ──
    //
    // ⚠️ 这两条钉的是「账号管理页那条真实路径」。前端调的是 GET /{factoryId}/users
    //    (即 getUserList), ⛔ 不是 /users/search —— 只测 searchUsers 会漏掉整个缺陷,
    //    而它当时恰恰是绿的 (searchUsers 一直好好的, 只是没人调它)。

    @Test
    @DisplayName("getUserList 把 pageRequest.keyword 当成搜索条件用（此前被静默丢弃）")
    void getUserList_appliesKeywordFromPageRequest() {
        pageRequest.setKeyword("lsm");
        when(userRepository.searchUsersByKeywordAndRole(
                eq("F001"), eq("lsm"), eq(""), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(salesperson)));

        PageResponse<UserDTO> result = userService.getUserList("F001", null, pageRequest);

        assertThat(result.getContent()).hasSize(1);
        // 阴性对照: 旧实现走的是 findByFactoryId, 那条路一次都不该再被走到。
        verify(userRepository, never()).findByFactoryId(eq("F001"), any(Pageable.class));
    }

    @Test
    @DisplayName("getUserList 的 role 参数同样下推")
    void getUserList_appliesRoleFilter() {
        when(userRepository.searchUsersByKeywordAndRole(
                eq("F001"), eq(""), eq("yield_operator"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(salesperson)));

        PageResponse<UserDTO> result = userService.getUserList("F001", "yield_operator", pageRequest);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("不带任何条件时两个维度都是空串（= 不过滤），行为与改动前一致")
    void getUserList_withoutConditions_filtersNothing() {
        when(userRepository.searchUsersByKeywordAndRole(
                eq("F001"), eq(""), eq(""), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(salesperson, warehouseManager)));

        PageResponse<UserDTO> result = userService.getUserList("F001", pageRequest);

        assertThat(result.getContent()).hasSize(2);
    }
}
