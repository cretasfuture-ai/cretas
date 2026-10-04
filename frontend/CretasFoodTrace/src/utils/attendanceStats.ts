export interface AttendanceTimeStats {
  totalHours: number;
  regularHours: number;
  overtimeHours: number;
  averageHours: number;
  period: string;
}

type ApiResponseLike = {
  success?: unknown;
  data?: unknown;
  message?: unknown;
};

type JsonObject = Record<string, unknown>;

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function responseData(response: unknown, label: string): JsonObject {
  if (!isObject(response) || (response as ApiResponseLike).success !== true) {
    const message = isObject(response) && typeof response.message === 'string' ? response.message : `${label}读取失败`;
    throw new Error(message);
  }
  if (!isObject(response.data)) {
    throw new Error(`${label}返回格式无效`);
  }
  return response.data;
}

function requiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new Error(`统计字段 ${field} 缺失或无效`);
  }
  return value;
}

function requiredInteger(value: unknown, field: string): number {
  const number = requiredNumber(value, field);
  if (!Number.isInteger(number)) {
    throw new Error(`统计字段 ${field} 缺失或无效`);
  }
  return number;
}

export function parsePersonalAttendanceStats(response: unknown, period: string): AttendanceTimeStats {
  const data = responseData(response, '个人工时');
  const totalHours = requiredNumber(data.totalHours, 'totalHours');
  const regularHours = requiredNumber(data.regularHours, 'regularHours');
  const overtimeHours = requiredNumber(data.overtimeHours, 'overtimeHours');
  const workDays = requiredInteger(data.workDays ?? data.attendanceDays, 'workDays');

  return {
    totalHours,
    regularHours,
    overtimeHours,
    averageHours: workDays > 0 ? totalHours / workDays : 0,
    period,
  };
}

export function parseDepartmentAttendanceStats(
  response: unknown,
  department: string,
  period: string,
): AttendanceTimeStats {
  const normalizedDepartment = department.trim().toLowerCase();
  if (!normalizedDepartment) {
    throw new Error('部门工时返回格式无效');
  }
  const data = responseData(response, '部门工时');
  if (!isObject(data.departmentStats)) {
    throw new Error('部门工时返回格式无效');
  }

  const departmentEntries = Object.entries(data.departmentStats);
  departmentEntries.forEach(([, value]) => {
    if (!isObject(value)) throw new Error('部门工时返回格式无效');
    const totalHours = requiredNumber(value.totalHours, 'departmentStats.totalHours');
    const overtimeHours = requiredNumber(value.overtimeHours, 'departmentStats.overtimeHours');
    requiredNumber(value.averageHours, 'departmentStats.averageHours');
    if (overtimeHours > totalHours) throw new Error('部门工时返回格式无效');
  });

  const departmentEntry = departmentEntries.find(([key, value]) => (
    key.trim().toLowerCase() === normalizedDepartment
      || (isObject(value) && typeof value.departmentName === 'string' && value.departmentName.trim().toLowerCase() === normalizedDepartment)
  ));

  // The backend only creates entries for departments with clock records. Validate
  // every returned entry, then an absent selected department is a measured zero.
  if (!departmentEntry) {
    return { totalHours: 0, regularHours: 0, overtimeHours: 0, averageHours: 0, period };
  }

  const [, value] = departmentEntry;
  if (!isObject(value)) {
    throw new Error('部门工时返回格式无效');
  }
  const totalHours = requiredNumber(value.totalHours, 'departmentStats.totalHours');
  const overtimeHours = requiredNumber(value.overtimeHours, 'departmentStats.overtimeHours');
  const averageHours = requiredNumber(value.averageHours, 'departmentStats.averageHours');
  if (overtimeHours > totalHours) {
    throw new Error('部门工时返回格式无效');
  }

  return {
    totalHours,
    regularHours: totalHours - overtimeHours,
    overtimeHours,
    averageHours,
    period,
  };
}

export function parseFactoryAttendanceStats(response: unknown, period: string): AttendanceTimeStats {
  const data = responseData(response, '全厂工时');
  const totalHours = requiredNumber(data.totalHours, 'totalHours');
  const regularHours = requiredNumber(data.regularHours, 'regularHours');
  const overtimeHours = requiredNumber(data.overtimeHours, 'overtimeHours');
  const averageHours = requiredNumber(data.averageHours, 'averageHours');

  return { totalHours, regularHours, overtimeHours, averageHours, period };
}
