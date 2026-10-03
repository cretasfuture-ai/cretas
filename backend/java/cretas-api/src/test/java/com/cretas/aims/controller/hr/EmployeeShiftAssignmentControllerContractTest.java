package com.cretas.aims.controller.hr;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.hr.AttendanceShift;
import com.cretas.aims.entity.hr.EmployeeShiftAssignment;
import com.cretas.aims.entity.hr.enums.ShiftAssignmentStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.hr.AttendanceShiftRepository;
import com.cretas.aims.repository.hr.EmployeeShiftAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmployeeShiftAssignmentControllerContractTest {
    private static final String FACTORY = "F006";
    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    @Mock EmployeeShiftAssignmentRepository assignments;
    @Mock AttendanceShiftRepository shifts;
    @Mock UserRepository users;
    private EmployeeShiftAssignmentController controller;

    @BeforeEach
    void setUp() {
        controller = new EmployeeShiftAssignmentController(assignments, shifts, users);
    }

    private AttendanceShift shift(String factory, boolean active) {
        return AttendanceShift.builder().id("early").factoryId(factory).isActive(active).build();
    }

    private User employee(long id, String factory, boolean active) {
        User user = new User();
        user.setId(id);
        user.setFactoryId(factory);
        user.setIsActive(active);
        return user;
    }

    private void allowShift() {
        when(shifts.findByIdAndFactoryId("early", FACTORY))
                .thenReturn(Optional.of(shift(FACTORY, true)));
    }

    private void allowEmployee(long id) {
        when(users.findByIdAndFactoryId(id, FACTORY))
                .thenReturn(Optional.of(employee(id, FACTORY, true)));
    }

    private Map<String, Object> single(Object id) {
        return Map.of("userId", id, "workDate", DAY.toString(), "shiftId", "early", "notes", "audit");
    }

    @Test
    void currentFactoryActiveEmployeeAndShiftCreateOnlyScopedAssignment() {
        allowShift();
        allowEmployee(11);
        when(assignments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = controller.upsert(FACTORY, single(11));
        assertEquals(200, result.getStatusCode().value());
        var captor = ArgumentCaptor.forClass(EmployeeShiftAssignment.class);
        verify(assignments).save(captor.capture());
        EmployeeShiftAssignment saved = captor.getValue();
        assertEquals(FACTORY, saved.getFactoryId());
        assertEquals(11L, saved.getUserId());
        assertEquals(DAY, saved.getWorkDate());
        assertEquals("early", saved.getShiftId());
        assertEquals(ShiftAssignmentStatus.SCHEDULED, saved.getStatus());
    }

    @Test
    void employeeFromAnotherFactoryCannotCreateOrReadExistingAssignment() {
        allowShift();
        when(users.findByIdAndFactoryId(11L, FACTORY)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> controller.upsert(FACTORY, single(11L)));
        verifyNoInteractions(assignments);
        verify(users).findByIdAndFactoryId(11L, FACTORY);
        verify(users, never()).findById(any());
    }

    @Test
    void incorrectFactoryRepositoryReturnIsRejectedBeforeAssignmentAccess() {
        allowShift();
        when(users.findByIdAndFactoryId(11L, FACTORY))
                .thenReturn(Optional.of(employee(11, "F007", true)));
        assertThrows(BusinessException.class, () -> controller.upsert(FACTORY, single(11L)));
        verifyNoInteractions(assignments);
    }

    @Test
    void wrongEmployeeIdRepositoryReturnIsRejectedBeforeAssignmentAccess() {
        allowShift();
        when(users.findByIdAndFactoryId(11L, FACTORY))
                .thenReturn(Optional.of(employee(12, FACTORY, true)));
        assertThrows(BusinessException.class, () -> controller.upsert(FACTORY, single(11L)));
        verifyNoInteractions(assignments);
    }

    @Test
    void inactiveEmployeeCannotBeNewlyAssigned() {
        allowShift();
        when(users.findByIdAndFactoryId(11L, FACTORY))
                .thenReturn(Optional.of(employee(11, FACTORY, false)));
        assertThrows(BusinessException.class, () -> controller.upsert(FACTORY, single(11L)));
        verifyNoInteractions(assignments);
    }

    @Test
    void inactiveShiftCannotBeNewlyAssigned() {
        when(shifts.findByIdAndFactoryId("early", FACTORY))
                .thenReturn(Optional.of(shift(FACTORY, false)));
        assertThrows(BusinessException.class, () -> controller.upsert(FACTORY, single(11L)));
        verifyNoInteractions(assignments, users);
    }

    @Test
    void incorrectShiftFactoryRepositoryReturnIsRejected() {
        when(shifts.findByIdAndFactoryId("early", FACTORY))
                .thenReturn(Optional.of(shift("F007", true)));
        assertThrows(BusinessException.class, () -> controller.upsert(FACTORY, single(11L)));
        verifyNoInteractions(assignments, users);
    }

    @Test
    void incorrectShiftIdRepositoryReturnIsRejected() {
        AttendanceShift wrong = shift(FACTORY, true);
        wrong.setId("different-shift");
        when(shifts.findByIdAndFactoryId("early", FACTORY)).thenReturn(Optional.of(wrong));
        assertThrows(BusinessException.class, () -> controller.upsert(FACTORY, single(11L)));
        verifyNoInteractions(assignments, users);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveEmployeeIdIsRejectedWithoutAnyRepositoryAccess(long id) {
        assertThrows(BusinessException.class, () -> controller.upsert(FACTORY, single(id)));
        verifyNoInteractions(assignments, shifts, users);
    }

    @ParameterizedTest
    @ValueSource(doubles = {11.5, Double.NaN, Double.POSITIVE_INFINITY})
    void nonIntegralEmployeeIdIsNeverTruncated(double id) {
        assertThrows(BusinessException.class, () -> controller.upsert(FACTORY, single(id)));
        verifyNoInteractions(assignments, shifts, users);
    }

    @Test
    void bulkLaterInvalidEmployeeCannotSaveAnEarlierValidEmployee() {
        allowShift();
        allowEmployee(11);
        when(users.findByIdAndFactoryId(12L, FACTORY)).thenReturn(Optional.empty());
        assertThrows(BusinessException.class, () -> controller.bulkAssign(FACTORY,
                Map.of("userIds", List.of(11L, 12L), "dates", List.of(DAY.toString()), "shiftId", "early")));
        verifyNoInteractions(assignments);
    }

    @Test
    void bulkLaterInvalidDateCannotSaveAnEarlierValidDate() {
        allowShift();
        allowEmployee(11);
        assertThrows(BusinessException.class, () -> controller.bulkAssign(FACTORY,
                Map.of("userIds", List.of(11L), "dates", List.of(DAY.toString(), "not-a-date"), "shiftId", "early")));
        verifyNoInteractions(assignments);
    }

    @Test
    void bulkDateWrongTypeIsBusinessErrorWithoutAnySave() {
        allowShift();
        allowEmployee(11);
        assertThrows(BusinessException.class, () -> controller.bulkAssign(FACTORY,
                Map.of("userIds", List.of(11L), "dates", List.of(DAY.toString(), 123), "shiftId", "early")));
        verifyNoInteractions(assignments);
    }

    @Test
    void validBulkCreatesAllRequestedScopedCells() {
        allowShift();
        allowEmployee(11);
        allowEmployee(12);
        when(assignments.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = controller.bulkAssign(FACTORY, Map.of("userIds", List.of(11L, 12L),
                "dates", List.of(DAY.toString(), DAY.plusDays(1).toString()), "shiftId", "early"));
        Map<?, ?> body = assertInstanceOf(Map.class, result.getBody());
        Map<?, ?> data = assertInstanceOf(Map.class, body.get("data"));
        assertEquals(4, data.get("created"));
        assertEquals(0, data.get("updated"));
        var captor = ArgumentCaptor.forClass(EmployeeShiftAssignment.class);
        verify(assignments, times(4)).save(captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(row -> FACTORY.equals(row.getFactoryId())));
        assertEquals(List.of(11L, 11L, 12L, 12L), captor.getAllValues().stream()
                .map(EmployeeShiftAssignment::getUserId).toList());
    }

    @Test
    void existingOwnCellIsUpdatedWithoutCreatingAnotherCell() {
        allowShift();
        allowEmployee(11);
        EmployeeShiftAssignment row = EmployeeShiftAssignment.builder().id("existing-own")
                .factoryId(FACTORY).userId(11L).workDate(DAY).shiftId("old").build();
        when(assignments.findByFactoryIdAndUserIdAndWorkDate(FACTORY, 11L, DAY)).thenReturn(Optional.of(row));
        when(assignments.save(row)).thenReturn(row);
        controller.upsert(FACTORY, single(11L));
        verify(assignments).save(row);
        assertEquals("existing-own", row.getId());
        assertEquals("early", row.getShiftId());
        assertEquals(ShiftAssignmentStatus.SCHEDULED, row.getStatus());
    }

    @Test
    void foreignExistingCellIsNotModifiedWhenRepositoryReturnsWrongRow() {
        allowShift();
        allowEmployee(11);
        EmployeeShiftAssignment foreign = EmployeeShiftAssignment.builder().id("foreign")
                .factoryId("F007").userId(11L).workDate(DAY).shiftId("old").build();
        when(assignments.findByFactoryIdAndUserIdAndWorkDate(FACTORY, 11L, DAY)).thenReturn(Optional.of(foreign));
        assertThrows(BusinessException.class, () -> controller.upsert(FACTORY, single(11L)));
        verify(assignments, never()).save(any());
        assertEquals("old", foreign.getShiftId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "date"})
    void wrongExistingEmployeeOrDateIsRejectedWithoutModifyingTheRow(String mismatch) {
        allowShift();
        allowEmployee(11);
        EmployeeShiftAssignment wrong = EmployeeShiftAssignment.builder().id("wrong-own-cell")
                .factoryId(FACTORY).userId(mismatch.equals("user") ? 12L : 11L)
                .workDate(mismatch.equals("date") ? DAY.plusDays(1) : DAY).shiftId("old").build();
        when(assignments.findByFactoryIdAndUserIdAndWorkDate(FACTORY, 11L, DAY)).thenReturn(Optional.of(wrong));
        assertThrows(BusinessException.class, () -> controller.upsert(FACTORY, single(11L)));
        verify(assignments, never()).save(any());
        assertEquals("old", wrong.getShiftId());
    }

    @Test
    void bulkUpdatesExistingOwnCellAndCountsItAsUpdated() {
        allowShift();
        allowEmployee(11);
        EmployeeShiftAssignment row = EmployeeShiftAssignment.builder().id("existing-bulk-own")
                .factoryId(FACTORY).userId(11L).workDate(DAY).shiftId("old").build();
        when(assignments.findByFactoryIdAndUserIdAndWorkDate(FACTORY, 11L, DAY)).thenReturn(Optional.of(row));
        when(assignments.save(row)).thenReturn(row);
        var result = controller.bulkAssign(FACTORY, Map.of("userIds", List.of(11L),
                "dates", List.of(DAY.toString()), "shiftId", "early"));
        Map<?, ?> body = assertInstanceOf(Map.class, result.getBody());
        Map<?, ?> data = assertInstanceOf(Map.class, body.get("data"));
        assertEquals(1, data.get("updated"));
        assertEquals(0, data.get("created"));
        verify(assignments).save(row);
        assertEquals("existing-bulk-own", row.getId());
        assertEquals("early", row.getShiftId());
    }

    @Test
    void foreignAssignmentCannotBeDeleted() {
        EmployeeShiftAssignment foreign = EmployeeShiftAssignment.builder().id("foreign").factoryId("F007").build();
        when(assignments.findById("foreign")).thenReturn(Optional.of(foreign));
        assertEquals(404, controller.delete(FACTORY, "foreign").getStatusCode().value());
        verify(assignments, never()).delete(any(EmployeeShiftAssignment.class));
    }

    @Test
    void ownHistoricalAssignmentCanStillBeClearedWithoutRequiringActiveShift() {
        EmployeeShiftAssignment own = EmployeeShiftAssignment.builder().id("own").factoryId(FACTORY).build();
        when(assignments.findById("own")).thenReturn(Optional.of(own));
        assertEquals(200, controller.delete(FACTORY, "own").getStatusCode().value());
        verify(assignments).delete(own);
        verifyNoInteractions(shifts, users);
    }

    @Test
    void existingModuleAndActionPermissionContractIsPreserved() throws Exception {
        assertEquals("hr_employee", EmployeeShiftAssignmentController.class.getAnnotation(RequireModule.class).value());
        assertArrayEquals(new String[]{"hr:read_write"}, EmployeeShiftAssignmentController.class
                .getMethod("upsert", String.class, Map.class).getAnnotation(RequirePermission.class).value());
        assertArrayEquals(new String[]{"hr:read_write"}, EmployeeShiftAssignmentController.class
                .getMethod("bulkAssign", String.class, Map.class).getAnnotation(RequirePermission.class).value());
        assertArrayEquals(new String[]{"hr:read_write"}, EmployeeShiftAssignmentController.class
                .getMethod("delete", String.class, String.class).getAnnotation(RequirePermission.class).value());
    }
}
