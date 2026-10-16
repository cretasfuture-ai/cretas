package com.cretas.aims.service;

import com.cretas.aims.dto.user.CreateUserRequest;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.impl.UserServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserOrganizationContractTest {
    private final UserRepository users=mock(UserRepository.class);
    private final Map<Long,User> rows=new HashMap<>();
    private final UserMapper mapper=new UserMapper();
    private final UserServiceImpl service=new UserServiceImpl(users,mock(FactoryRepository.class),mapper,
        mock(org.springframework.security.crypto.password.PasswordEncoder.class),mock(com.cretas.aims.utils.ExcelUtil.class));
    private final org.springframework.jdbc.core.JdbcTemplate jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
    UserOrganizationContractTest() {
        org.springframework.test.util.ReflectionTestUtils.setField(service,"organizationJdbc",jdbc);
        when(jdbc.queryForList(anyString(),any(Long.class))).thenAnswer(i->{User u=rows.get(i.getArgument(1));if(u==null)return List.of();Map<String,Object> node=new HashMap<>();node.put("factory_id",u.getFactoryId());node.put("is_active",u.getIsActive());node.put("reports_to",u.getReportsTo());return List.of(node);});
        when(jdbc.queryForList(anyString(),anyString(),any(Long.class))).thenAnswer(i->rows.values().stream().filter(u->Objects.equals(u.getFactoryId(),i.getArgument(1)) && Objects.equals(u.getReportsTo(),i.getArgument(2))).map(u->Map.<String,Object>of("id",u.getId())).toList());
        rows.put(1L,user(1,"F1"));rows.put(2L,user(2,"F1"));rows.put(3L,user(3,"F1"));rows.put(9L,user(9,"F2"));
        rows.get(1L).setDepartment("PROCESSING");rows.get(1L).setReportsTo(3L);
        when(users.findById(anyLong())).thenAnswer(i->Optional.ofNullable(rows.get(i.getArgument(0))));
        when(users.save(any(User.class))).thenAnswer(i->i.getArgument(0));
    }
    static User user(long id,String factory) { var u=new User();u.setId(id);u.setFactoryId(factory);u.setRoleCode("sales_manager");u.setIsActive(true);return u; }
    static CreateUserRequest request(String fields) throws Exception {return new ObjectMapper().readValue("{\"roleCode\":\"sales_manager\""+fields+"}",CreateUserRequest.class);}
    @Test void departmentUpdateIsPersistedAndReturnedAsEditableCode() throws Exception {
        var result=service.updateUser("F1",1L,request(",\"department\":\"QUALITY\""));
        assertThat(rows.get(1L).getDepartment()).isEqualTo("QUALITY");assertThat(result.getDepartmentCode()).isEqualTo("QUALITY");verify(users).save(rows.get(1L));
    }
    @Test void explicitNullDepartmentClearsButOmissionPreservesLegacyCode() throws Exception {
        rows.get(1L).setDepartment("legacy-department");service.updateUser("F1",1L,request(""));assertThat(rows.get(1L).getDepartment()).isEqualTo("legacy-department");
        service.updateUser("F1",1L,request(",\"department\":null"));assertThat(rows.get(1L).getDepartment()).isNull();
    }
    @Test void activeSameFactoryManagerIsPersistedAndReadBack() throws Exception {
        var result=service.updateUser("F1",1L,request(",\"reportsTo\":2"));assertThat(rows.get(1L).getReportsTo()).isEqualTo(2L);assertThat(result.getReportsTo()).isEqualTo(2L);verify(users).save(rows.get(1L));
    }
    @Test void explicitNullManagerClearsAndOmittedManagerPreserves() throws Exception {
        service.updateUser("F1",1L,request(""));assertThat(rows.get(1L).getReportsTo()).isEqualTo(3L);
        var result=service.updateUser("F1",1L,request(",\"reportsTo\":null"));assertThat(rows.get(1L).getReportsTo()).isNull();assertThat(result.getReportsTo()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"self","descendant","existing-cycle","other-factory","inactive","missing"})
    void invalidManagerIsRejectedBeforeAnyWriteOrOtherFieldChange(String kind) throws Exception {
        long target=2;int status=400;
        switch(kind) {case "self"->target=1;case "descendant"->rows.get(2L).setReportsTo(1L);case "existing-cycle"->{rows.get(2L).setReportsTo(3L);rows.get(3L).setReportsTo(2L);}case "other-factory"->{target=9;status=403;}case "inactive"->rows.get(2L).setIsActive(false);case "missing"->{target=999;status=404;}default->throw new IllegalArgumentException();}
        int expected=status;var req=request(",\"reportsTo\":"+target+",\"department\":\"QUALITY\"");
        assertThatThrownBy(()->service.updateUser("F1",1L,req)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(expected));
        verify(users,never()).save(any());assertThat(rows.get(1L).getDepartment()).isEqualTo("PROCESSING");assertThat(rows.get(1L).getReportsTo()).isEqualTo(3L);
    }
    @ParameterizedTest @ValueSource(ints={9,10})
    void reparentingChecksWholeSubtreeDepthBeforeOtherFieldsChange(int descendantDepth) throws Exception {
        long parent=1;
        for(int depth=1;depth<=descendantDepth;depth++){var child=user(100+depth,"F1");child.setReportsTo(parent);rows.put(child.getId(),child);parent=child.getId();}
        var req=request(",\"reportsTo\":2,\"department\":\"QUALITY\"");
        if(descendantDepth==10){
            assertThatThrownBy(()->service.updateUser("F1",1L,req)).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(400));
            verify(users,never()).save(any());assertThat(rows.get(1L).getReportsTo()).isEqualTo(3L);assertThat(rows.get(1L).getDepartment()).isEqualTo("PROCESSING");
        }else{
            service.updateUser("F1",1L,req);verify(users).save(rows.get(1L));assertThat(rows.get(1L).getReportsTo()).isEqualTo(2L);assertThat(rows.get(1L).getDepartment()).isEqualTo("QUALITY");
        }
    }
    @ParameterizedTest @ValueSource(strings={"dedicated","update"})
    void inactiveDescendantsCannotHideDepthUntilReactivation(String activationPath) throws Exception {
        rows.get(1L).setReportsTo(null);
        long parent=1;
        for(int depth=1;depth<=10;depth++){var child=user(100+depth,"F1");child.setReportsTo(parent);rows.put(child.getId(),child);parent=child.getId();}
        long leaf=parent;rows.get(leaf).setIsActive(false);
        assertThatThrownBy(()->service.updateUser("F1",1L,request(",\"reportsTo\":2"))).isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getCode()).isEqualTo(400));
        verify(users,never()).save(any());assertThat(rows.get(1L).getReportsTo()).isNull();
        if(activationPath.equals("dedicated"))service.activateUser("F1",leaf);
        else service.updateUser("F1",leaf,request(",\"isActive\":true"));
        assertThat(rows.get(leaf).getIsActive()).isTrue();assertThat(rows.get(1L).getReportsTo()).isNull();
    }
    @Test void dtoReadBackDoesNotLoseExistingOrganizationRelationship() {var dto=mapper.toDTO(rows.get(1L));assertThat(dto.getDepartmentCode()).isEqualTo("PROCESSING");assertThat(dto.getReportsTo()).isEqualTo(3L);}
    @Test void suppliedFlagsCannotBeForgedAndAreNotSerialized() throws Exception {
        var json=new ObjectMapper();var req=json.readValue("{\"reportsToProvided\":true,\"departmentProvided\":true}",CreateUserRequest.class);
        assertThat(req.isReportsToProvided()).isFalse();assertThat(req.isDepartmentProvided()).isFalse();var tree=json.valueToTree(req);assertThat(tree.has("reportsToProvided")).isFalse();assertThat(tree.has("departmentProvided")).isFalse();
    }
}
